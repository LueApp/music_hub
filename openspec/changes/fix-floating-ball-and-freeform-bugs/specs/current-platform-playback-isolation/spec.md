## ADDED Requirements

### Requirement: Playback state read only from current platform's controller

`MediaMonitorService.getPlaybackInfo()` SHALL return playback state (position, duration, playing/paused) sourced exclusively from the `MediaController` whose package equals the platform package most recently launched by `PlaybackService` (tracked as `currentPlatformPackage`). Controllers belonging to any other music-app or video-app package SHALL be ignored, regardless of whether they report `STATE_PLAYING`.

#### Scenario: Bilibili video session ignored while NetEase is current platform
- **GIVEN** Tutti has launched a NetEase song and `currentPlatformPackage = com.netease.cloudmusic`
- **AND** the Bilibili app (`tv.danmaku.bili`) has a `STATE_PLAYING` MediaController from a separately watched video
- **WHEN** `getPlaybackInfo()` is called
- **THEN** the returned state reflects the NetEase controller (or last-known NetEase position when none is live) and never the Bilibili controller

#### Scenario: Pause from Tutti does not let foreign session take over the ring
- **GIVEN** the user pauses the current NetEase song via Tutti's floating-ball pause button
- **AND** a QQ Music or Bilibili session is `STATE_PLAYING` in the background
- **WHEN** the floating window's next `updateProgress()` tick runs (≤500 ms later)
- **THEN** the progress ring stays anchored on the NetEase paused position and does not advance to a foreign session's position

#### Scenario: Progress ring falls back to last-known position when no current-platform session
- **GIVEN** the current platform's controller has been destroyed (e.g. the music app was killed) and no NetEase MediaController exists
- **WHEN** the floating window polls progress
- **THEN** the ring shows the last-known position of the launched song with the paused icon, and does not pick up any other platform's playback

#### Scenario: Platform switch updates the binding atomically
- **WHEN** `PlaybackService` launches a song on a different platform (NetEase → QQ Music)
- **THEN** `currentPlatformPackage` updates to the new platform's package *before* the next `updateProgress()` tick, so the ring switches sources without ever briefly reflecting the prior platform's stale state

### Requirement: FloatingWindowService never queries foreign controllers directly

`FloatingWindowService.updateProgress()` and `updateProgressFromRemote()` SHALL only consume the filtered output of `getPlaybackInfo()`. They MUST NOT iterate `MediaSessionManager.getActiveSessions()` themselves nor consult any controller whose package is not `currentPlatformPackage`.

#### Scenario: No bypass path exists
- **WHEN** the floating-window codebase is searched for `getActiveSessions(`, `MediaController(` or per-package controller lookups outside of `MediaMonitorService`
- **THEN** no call site in `FloatingWindowService` reads a controller directly; all playback queries route through `getPlaybackInfo()` (or a similarly-filtered helper)

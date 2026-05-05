## ADDED Requirements

### Requirement: Intermediate pause during same-platform NetEase transitions

When PlaybackService performs a same-platform NetEase-to-NetEase song switch in portrait mode, the system SHALL send pause commands to NetEase Cloud Music at intermediate intervals between the initial deep link send and the 2s re-send, to silence any auto-advanced third song.

#### Scenario: Portrait mode NetEase-to-NetEase transition silences third song

- **WHEN** PlaybackService launches a new NetEase song while the previous song was also NetEase, and the device is in portrait mode
- **THEN** the system sends `pausePackage("com.netease.cloudmusic")` at approximately 500ms and 1200ms after the initial deep link launch, AND still re-sends the deep link at 2s

#### Scenario: Pause commands do not affect the target song

- **WHEN** intermediate pause commands are sent at 500ms and 1200ms after the deep link launch
- **THEN** the target song (loaded via deep link) is not affected because it has not yet started playback (NetEase deep link initialization takes 1-3s)

#### Scenario: Landscape mode retains existing behavior

- **WHEN** PlaybackService launches a new NetEase song while in landscape mode (or landscape workaround is active)
- **THEN** the system sends pause commands at 500ms and 1200ms (existing behavior) and does NOT re-send the deep link (existing behavior)

### Requirement: Unified pause logic for portrait and landscape NetEase transitions

The intermediate pause commands (at 500ms and 1200ms) SHALL be sent for ALL same-platform NetEase transitions regardless of orientation. The only orientation-dependent behavior SHALL be whether the deep link re-send occurs (portrait only).

#### Scenario: Both orientations use same pause timing

- **WHEN** a same-platform NetEase transition occurs in either portrait or landscape mode
- **THEN** pause commands are sent at 500ms and 1200ms in both cases

#### Scenario: Re-send only in portrait mode

- **WHEN** a same-platform NetEase transition occurs in portrait mode
- **THEN** the deep link is re-sent at 2s after the initial send
- **WHEN** a same-platform NetEase transition occurs in landscape mode
- **THEN** the deep link is NOT re-sent

### Requirement: Transition logging for diagnostics

The system SHALL log each intermediate pause attempt and its timing for diagnostic purposes, using the existing `PlaybackService` TAG.

#### Scenario: Pause actions are logged

- **WHEN** an intermediate pause command is sent during a NetEase transition
- **THEN** a debug log entry is written with the pause timing (e.g., "Pausing NetEase auto-advance at 500ms") and whether the pause was for portrait or landscape mode

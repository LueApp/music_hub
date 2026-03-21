## Context

After the `bilibili-app-deep-link` change, Bilibili content now opens in the native Bilibili app instead of the browser. However, two playback issues remain:

1. **Resume from last position**: The Bilibili app remembers where the user last stopped a video and resumes from there. Music Hub expects each song to start fresh. The current deep link format `bilibili://video/{id}` has no start position parameter.

2. **No media session monitoring**: `MediaMonitorService` excludes `tv.danmaku.bili` from its `targetPackages`, so Bilibili media sessions are never monitored. The original reasons documented in code were: (a) video content doesn't suit sequential playback, (b) Bilibili doesn't report proper duration metadata, (c) auto-advance doesn't make sense for video. These assumptions need to be revisited since Music Hub explicitly adds Bilibili content to playlists for sequential playback.

Additionally, `PlaybackService` has two Bilibili exclusion guards: in `songFinishedReceiver` (prevents auto-advance) and in `schedulePlaybackTimeout()` (prevents timeout-based skipping).

## Goals / Non-Goals

**Goals:**
- Bilibili videos start from the beginning every time they're launched via Music Hub
- MediaMonitorService detects when Bilibili content finishes playing
- Auto-advance works for Bilibili content in playlists
- Playback timeout detection applies to Bilibili content

**Non-Goals:**
- No changes to NetEase or QQ Music handling
- No changes to floating window UI or remote control behavior
- Not trying to solve the case where Bilibili truly doesn't report duration — we'll add monitoring and assess in practice

## Decisions

### 1. Append `?start_progress=0` to video deep links

**Decision**: Add `?start_progress=0` to `bilibili://video/{id}` deep links to force playback from the beginning. The parameter value is in milliseconds; `0` means start of video.

**Rationale**: Reverse engineering of Bilibili's mobile web-to-app redirect flow shows the Bilibili app accepts a `start_progress` query parameter on `bilibili://video/` URIs. Setting it to `0` overrides the resume-from-last-position behavior.

**Alternatives considered**:
- **Intent extras (`player.extra.startProgress`)**: Less documented, may not work across Bilibili versions.
- **Clear Bilibili's app data**: Destructive and requires special permissions.

**Scope**: Applies to video deep links only (`bilibili://video/`). Audio deep links (`bilibili://music/detail/`) are unaffected — audio content typically starts from the beginning by default.

### 2. Add `tv.danmaku.bili` to MediaMonitorService's targetPackages

**Decision**: Include the Bilibili package in the set of monitored media sessions.

**Rationale**: The original exclusion reasons are no longer fully applicable:
- "Users watch videos, not listen to sequential audio" — The user has explicitly added Bilibili content to a Music Hub playlist, signaling they want sequential playback.
- "Bilibili doesn't report proper duration metadata" — This needs real-world verification. If duration isn't reported, the existing song-end detection will simply not trigger (graceful degradation). Adding monitoring is low-risk.
- "Auto-advance doesn't make sense for video content" — It does when the content is in a Music Hub playlist.

**Risk**: If Bilibili's MediaSession reports incorrect or missing duration data, song-end detection may not trigger reliably. This is acceptable — the playback timeout mechanism (15 seconds) provides a safety net, and the user can always manually advance.

### 3. Remove Bilibili exclusion guards in PlaybackService

**Decision**: Remove the Bilibili-specific `return` in `songFinishedReceiver` (line 88-90) and the Bilibili check in `schedulePlaybackTimeout()` (line 654).

**Rationale**: These guards exist solely because MediaMonitorService didn't monitor Bilibili. Once monitoring is enabled, these guards block the auto-advance chain unnecessarily.

## Risks / Trade-offs

- **`start_progress=0` is undocumented** → Mitigation: Based on observed Bilibili app behavior. If a future Bilibili version ignores the parameter, playback still works (just resumes from last position). No crash risk.

- **Bilibili may not report `METADATA_KEY_DURATION` reliably** → Mitigation: If duration is 0 or missing, the existing `isSongFinished()` logic requires `duration > 0` to trigger, so it will simply not auto-advance. The playback timeout (15 seconds) catches the case where playback genuinely fails. Worst case: user manually taps Next for Bilibili content (same as current behavior).

- **Position polling may not work for Bilibili** → Mitigation: The early detection via position polling (`pollCurrentPosition()`) depends on `PlaybackState.position` progressing. If Bilibili doesn't update position, early detection won't fire, but state-change or metadata-change detection may still work. Graceful degradation.

## Why

When a song passes the pre-launch availability check but still fails to play at runtime (e.g., QQ Music "resource unavailable" error, VIP-only content, temporary server errors), the playback queue gets stuck indefinitely. MediaMonitorService never receives a "song finished" signal from the error state, so auto-advance never triggers. The user must manually skip the song. This also means the failure is silently lost — there's no record of which songs failed or why.

## What Changes

- Add a post-launch playback timeout in PlaybackService: if no PLAYING state is detected within N seconds after launching a deep link, treat the song as failed and auto-skip to the next song
- Record playback failures (song info, platform, timestamp, reason) via logging so the user/developer can identify problematic songs
- Integrate with the existing `consecutiveSkips` counter to prevent infinite skip loops when multiple songs fail at runtime
- Show a toast notification when a song is skipped due to playback timeout (distinct from the pre-launch unavailability skip message)

## Non-goals

- No persistent database storage of failure history (log-only for now)
- No automatic retry logic for failed songs
- No UI for viewing/managing failed song history
- No changes to the pre-launch availability check (that system works correctly for its scope)
- No changes to Bilibili handling (Bilibili doesn't use MediaMonitorService auto-advance)

## Capabilities

### New Capabilities
- `playback-timeout-detection`: Post-launch timeout mechanism that detects when a launched song fails to start playing within a configurable window, triggers auto-skip, and logs the failure

### Modified Capabilities
<!-- No existing specs to modify — the pre-launch availability check remains unchanged -->

## Impact

- **PlaybackService**: New timeout logic after `doLaunchSong()`, integration with existing skip counter
- **MediaMonitorService**: Needs to expose a way for PlaybackService to query whether playback has started (or provide a callback/broadcast when PLAYING state is first detected for a new song)
- **Affected platforms**: QQ Music primarily (observed failure), but the timeout applies generically to all platforms (NetEase, QQ Music)
- **No new permissions required**
- **No database schema changes**

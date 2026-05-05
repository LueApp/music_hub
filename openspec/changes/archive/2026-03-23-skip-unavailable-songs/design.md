## Context

When PlaybackService launches a song via deep link, it relies on MediaMonitorService to detect when the song finishes and trigger auto-advance. If the deep link opens an error page (song deleted, region-locked, taken down), MediaMonitorService never sees a valid playback state transition, and the queue stalls indefinitely.

The existing `fetchMetadata()` methods on each PlatformHandler already call the same APIs that can indicate availability, but this information was never used at playback time.

## Goals / Non-Goals

**Goals:**
- Check song availability via platform API before launching a deep link
- Auto-skip unavailable songs and notify the user with a toast
- Prevent infinite skip loops when many consecutive songs are unavailable
- Fail open on network errors (assume available) to avoid blocking playback
- Work in all three app modes (standalone, player, controller)

**Non-Goals:**
- Persisting unavailability status to the database
- Cross-platform song replacement (finding the same song on another platform)
- Pre-scanning the entire queue ahead of time
- Caching availability results

## Decisions

### 1. Pre-launch check vs. post-launch detection

**Decision**: Check availability before launching the deep link.

**Alternatives considered**:
- Post-launch detection via MediaMonitorService timeout: Would require a timeout heuristic (how long to wait before declaring failure?), and the music app would already be foregrounded showing an error page.
- Hybrid (check + timeout fallback): Adds complexity for marginal benefit since the API check is reliable for known failure modes.

**Rationale**: Pre-launch avoids the bad UX of opening the music app to an error page entirely. The API calls are lightweight (single HTTP request) and the same endpoints already used by `fetchMetadata()`.

### 2. Async check with coroutine scope on PlaybackService

**Decision**: Add a `SupervisorJob` + `CoroutineScope` to PlaybackService, run the availability check in `serviceScope.launch {}`.

**Rationale**: `checkSongAvailability()` is a suspend function (network I/O). The existing `launchCurrentSong()` is called from the main thread. Using a coroutine scope tied to the service lifecycle ensures proper cancellation on service destroy.

### 3. Fail-open on errors

**Decision**: If the availability check throws an exception (network timeout, DNS failure, etc.), treat the song as available and proceed with launch.

**Rationale**: A network blip shouldn't prevent playback. The worst case is the same behavior as before this change — the deep link opens and the user sees the music app's own error handling.

### 4. Consecutive skip safety limit

**Decision**: Stop playback after 10 consecutive unavailable songs (`MAX_CONSECUTIVE_SKIPS = 10`).

**Rationale**: Without a limit, a playlist of entirely unavailable songs would loop through the queue repeatedly. The counter resets to 0 whenever a song successfully passes the availability check.

### 5. Platform-specific availability signals

**Decision**: Each platform handler overrides `checkSongAvailability()` with platform-specific API checks rather than relying on the default `fetchMetadata()` fallback.

| Platform | API | Unavailability Signal |
|----------|-----|----------------------|
| NetEase | `/api/v3/song/detail` | `privileges[0].st < 0` |
| QQ Music | `musicu.fcg` (`get_song_detail_yqq`) | `track_info.fnote == 4001` |
| Bilibili | `/x/web-interface/view` (video), `/audio/music-service-c/web/song/info` (audio) | Response `code != 0` |

**Rationale**: Each platform has distinct API response structures and failure codes. The default fallback (check if metadata returns a title) would miss cases like NetEase songs that return metadata but have `st < 0` privilege restrictions.

## Risks / Trade-offs

- **[Added latency]** → Each song launch now has an HTTP round-trip (~100-500ms). Mitigated by the fact that the existing pause-before-launch delay (100-300ms) already exists, and the check runs concurrently with user perception of the transition.

- **[API rate limiting]** → Rapid skipping through many unavailable songs could trigger platform rate limits. Mitigated by the 10-skip safety limit which stops playback before excessive API calls.

- **[False positives from API changes]** → Platform APIs may change their response format, causing the check to incorrectly flag songs as unavailable. Mitigated by fail-open design: if parsing fails (exception), the song is assumed available.

- **[Remote control mode]** → In controller mode, the availability check runs on the controller phone, but the deep link launches on the player phone. The check uses public APIs so this works correctly regardless of which device runs it.

## Context

PlaybackService uses a binary timeout system: 5s (warm) if the target app has an active MediaController, 25s (cold) if not. When the timeout fires, it checks `getPlaybackInfo()?.isPlaying == true` — if something is playing, it assumes the correct song started.

Three bugs were identified via log analysis:

1. **NetEase landscape workaround creates false warm starts**: The workaround uses `FLAG_ACTIVITY_CLEAR_TASK`, effectively restarting NetEase from scratch. But the old MediaController still exists briefly, so `hasActiveController()` returns true → 5s timeout. NetEase with CLEAR_TASK needs ~10-15s.

2. **Rapid cross-platform switching exhausts warm timeout**: When song A times out and the system quickly launches song B on the same platform, the app is still processing A's deep link. 5s isn't enough for B because the app is mid-transition.

3. **No song identity verification**: The timeout check only asks "is something playing?" — not "is the RIGHT song playing?" When a timed-out song starts playing late, the next song's timeout check passes because it sees playback from the wrong song. This creates a permanent desync: UI shows song N+2 but audio plays song N+1.

## Goals / Non-Goals

**Goals:**
- Eliminate false timeouts caused by landscape workaround and cross-platform delays
- Detect and recover from song identity mismatch (wrong song playing)
- Keep the timeout system simple and debuggable

**Non-Goals:**
- Changing the landscape workaround mechanism itself
- Handling NetEase's auto-advance behavior (already handled by double-send)
- Guaranteed zero false timeouts (some edge cases will always exist)

## Decisions

### Decision 1: Use landscape workaround state to override timeout to cold-start

When `DeepLinkLauncher.landscapeWorkaroundActive` is true and the target is NetEase, always use `PLAYBACK_TIMEOUT_COLD_MS` regardless of controller state.

**Why**: The landscape workaround with CLEAR_TASK destroys the existing activity, making the controller state irrelevant. The app must fully reinitialize.

**Alternative considered**: Add a third timeout tier (e.g., 15s for "warm but landscape"). Rejected — unnecessary complexity. Cold-start timeout (25s) is safe and already tested.

### Decision 2: Add cross-platform switch timeout boost

When `isPlatformSwitch` is true, use `PLAYBACK_TIMEOUT_COLD_MS` instead of warm timeout. Cross-platform switches involve pausing one app and launching another — even "warm" apps need extra time during this transition.

**Why**: The log shows QQ Music needed ~8s to start `不吐不快` after a rapid NetEase→QQ switch, but warm timeout was only 5s. Cross-platform switches are inherently slower due to pause commands, focus changes, and deep link processing.

**Alternative considered**: Increase warm timeout to 8-10s globally. Rejected — would slow down same-platform timeout detection for genuinely unavailable songs.

### Decision 3: Add song title verification in timeout check

When the timeout fires and `isPlaying == true`, compare the MediaSession metadata title against the expected song title. If they don't match, treat it as a timeout failure (the wrong song is playing).

**Implementation**: Add a `title` field to `PlaybackInfo`. In the timeout runnable, after confirming `isPlaying`, check if the title contains or matches the expected song title. Use fuzzy matching (contains check) since MediaSession titles may include extra metadata.

**Why**: This directly prevents the desync bug. Without this, a late-starting timed-out song tricks the next song's timeout check.

### Decision 4: Add desync recovery via metadata monitoring

After a timeout skip, if MediaMonitorService detects the OLD (timed-out) song starting to play, pause it immediately and re-launch the current song's deep link.

**Implementation**: When a timeout skip occurs, store the timed-out song's title in a `lastTimedOutSongTitle` field. In the metadata change callback, if the new title matches the timed-out song AND the current platform matches, pause and re-send the current song's deep link.

**Why**: Even with longer timeouts, edge cases can still cause late starts. This provides a safety net that corrects the desync automatically.

**Alternative considered**: Just verify title in timeout check without recovery. Rejected — if the wrong song starts playing 1s after the timeout check passes, we'd still be desynced. Active recovery is needed.

### Decision 5: Reset consecutiveSkips counter for landscape/cross-platform timeouts

When a timeout occurs due to landscape workaround or cross-platform switch delays (not a genuinely unavailable song), do NOT increment `consecutiveSkips`. These are infrastructure delays, not song availability failures.

**Rejected**: Too complex to distinguish reliably. The current counter with MAX=10 is generous enough. Keep it simple.

## Risks / Trade-offs

- **[Risk] Title matching may fail for songs with special characters or remixes** → Mitigation: Use contains-based matching and normalize whitespace. Log mismatches for debugging.
- **[Risk] Desync recovery re-launch may cause brief audio interruption** → Acceptable trade-off vs. playing the wrong song for 4+ minutes.
- **[Risk] Cross-platform switch always using cold timeout means 25s wait for genuinely unavailable songs on warm apps** → Acceptable — cross-platform switches are less frequent, and 25s is still reasonable.

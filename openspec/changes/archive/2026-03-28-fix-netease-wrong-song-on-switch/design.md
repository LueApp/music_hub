## Context

When Music Hub advances to the next song within NetEase Cloud Music (same-platform switch), the following race condition occurs:

1. `MediaMonitorService` detects the song is at ~99% completion (early song-end detection) and broadcasts `ACTION_SONG_FINISHED`
2. `PlaybackService` receives the broadcast, increments the queue index, and calls `launchCurrentSong()`
3. `launchCurrentSong()` → `doLaunchSong()` calls `pauseAllMedia(targetPackage)`. Since this is a same-platform switch (NetEase → NetEase), the repeated-pause scheduling is skipped.
4. The deep link (`orpheus://song/<id>`) is sent after a 100ms delay
5. Meanwhile, NetEase's internal playback continues for the remaining ~1.5s, then NetEase auto-advances to its own next song in its internal queue
6. NetEase's auto-advance overwrites the deep link destination — the wrong song plays

The core issue: **the single pause command at step 3 is insufficient because NetEase hasn't finished the song yet** (early detection fires before the actual end). NetEase ignores the pause/stop transport control and continues playing until its natural end, then auto-advances.

Evidence from logs:
- `09:25:22.265` — Early song end at 99.3% (1519ms remaining)
- `09:25:23.009` — `pauseAllMedia` called, same-platform switch, no repeated pause
- `09:25:23.127` — Deep link `orpheus://song/1336990977` sent for "质数的孤独"
- `09:25:25.252` — Metadata shows "给自己的情书" (NetEase's auto-advanced song)

## Goals / Non-Goals

**Goals:**
- Prevent NetEase from auto-advancing to its own next song during same-platform switches
- Ensure the deep link for Music Hub's intended next song takes effect reliably
- Applies in standalone and player modes (MediaMonitorService runs on the player phone)

**Non-Goals:**
- Changing early song-end detection thresholds
- Fixing QQ Music or Bilibili same-platform switching (not affected by this bug)
- Modifying controller mode behavior (controller doesn't run MediaMonitorService)

## Decisions

### Decision 1: Schedule repeated pause for same-platform NetEase switches

**Choice**: Enable repeated pause scheduling for same-platform switches when the target platform is NetEase, similar to the existing cross-platform repeated-pause pattern.

**Rationale**: The cross-platform repeated-pause pattern already solves the equivalent problem (old platform auto-advancing). The same mechanism can prevent NetEase from auto-advancing during same-platform switches. The early song-end detection fires ~1-2s before the actual end, so repeated pause attempts at 500ms intervals for ~2s should catch NetEase's auto-advance.

**Alternative considered**: Delay the deep link launch until after the song naturally finishes. Rejected because: (a) the exact remaining time is uncertain, (b) this would add noticeable latency to song transitions, (c) it defeats the purpose of early detection (which exists to beat the target app's auto-advance).

**Alternative considered**: Disable early song-end detection for same-platform NetEase switches entirely, relying on metadata-change detection instead. Rejected because metadata-change detection is less reliable and slower — by the time it fires, NetEase has already auto-advanced and started playing its own next song, creating an audible blip.

### Decision 2: Target the pause at the current (old) platform controller

**Choice**: For same-platform switches, set `switchingFromPackage` to the target package (since source = target) and schedule 3-4 repeated pause attempts over ~2s after the initial pause. The deep link launch continues as before (100ms delay).

**Rationale**: The repeated pause will catch NetEase's auto-advance when it happens (~1.5s after early detection). The deep link will then land on a paused NetEase instance, which will navigate to the correct song.

**Implementation detail**: We need to be careful not to pause the *new* song after it starts playing via deep link. The repeated pause should stop once `onNewSongStarted()` clears the manual control state (3s after launch). Since the deep link fires at ~100ms and repeated pauses run at 500ms intervals up to ~2s, there should be no conflict — by the time the new song starts playing (several seconds after deep link), the repeated pauses have already finished.

## Risks / Trade-offs

- **[Risk] Repeated pause may pause the newly launched song** → Mitigated by limiting repeated pause to 2s window, which ends before the deep link's target song typically starts playing (NetEase takes 2-5s to load a song from a deep link). Additionally, `onNewSongStarted()` clears `switchingFromPackage` after 3s.
- **[Risk] Pause commands may not fully prevent auto-advance** → NetEase may ignore transport controls in some states. If this happens, the fix provides a best-effort improvement. Can be observed via logcat.
- **[Trade-off] Slightly more aggressive pausing** → May cause a brief pause flicker if the song finishes naturally very close to the early detection. Acceptable given the current behavior (wrong song playing) is much worse.

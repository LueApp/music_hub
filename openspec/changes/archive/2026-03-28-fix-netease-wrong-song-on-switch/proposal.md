## Why

When Music Hub switches songs within NetEase Cloud Music (same-platform switch), the early song-end detection fires ~1.5s before the song actually ends. During this window, NetEase's internal queue auto-advances to its own next song, which races with Music Hub's deep link launch. The result: the wrong song plays (NetEase's next-in-queue instead of Music Hub's intended next song). For example, switching from "心许百年" to "质数的孤独" results in "给自己的情书" playing instead.

## What Changes

- **Pause NetEase before deep link on same-platform switches**: Currently, `pauseAllMedia()` sends pause/stop commands but skips repeated pause scheduling for same-platform switches. However, the initial pause is not enough — NetEase auto-advances after the current song naturally ends (~1.5s after early detection). The fix must ensure NetEase is paused before the deep link is sent, and that the pause is timed to prevent NetEase's auto-advance from winning the race.
- **Add a brief delay or repeated pause for same-platform NetEase switches**: Similar to the cross-platform repeated-pause pattern already used, but targeted at preventing NetEase's internal auto-advance during the ~1.5s gap between early song-end detection and natural song end.

## Non-goals

- Changing the early song-end detection thresholds (99%/2s remaining is appropriate for beating the target app's auto-advance)
- Modifying QQ Music or Bilibili switching behavior (this bug is NetEase-specific due to its aggressive internal auto-advance)
- Eliminating the foreground task switch (known Android limitation)

## Capabilities

### New Capabilities

_None — this is a bug fix within existing playback switching logic._

### Modified Capabilities

_None — no spec-level requirement changes, only implementation-level fix in the same-platform song switching flow._

## Impact

- **Affected code**: `MediaMonitorService.kt` (pause logic for same-platform switches), potentially `PlaybackService.kt` (launch timing)
- **Platform affected**: NetEase Cloud Music (网易云音乐) only
- **No new permissions or dependencies required**

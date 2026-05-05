## Why

When switching between two NetEase songs in the Music Hub queue, NetEase Cloud Music briefly plays a "third song" — its own internal next track — before the second deep link override takes effect. The user hears ~1-2 seconds of an unrelated song between intended tracks (e.g., going from "下一次爱情来的时候" to "淡化", a third song plays in between). This happens because NetEase auto-advances to its own queue's next song ~600ms-1.5s after the current song ends, and the current double-send approach (re-send deep link after 2s) leaves a window where the third song is audible.

## What Changes

- Improve same-platform NetEase transition logic to suppress the auto-advanced third song before it becomes audible
- Add a pre-emptive pause of NetEase between the first deep link send and the re-send, silencing any auto-advanced song during the gap
- Tighten the re-send timing window from 2s to a shorter interval informed by observed NetEase auto-advance behavior
- Add logging to track auto-advance timing for future tuning

## Non-goals

- Fixing the landscape mode third-song issue (already handled separately via pause approach)
- Changing cross-platform switching behavior (NetEase → QQ Music, etc.)
- Eliminating the double-send approach entirely (still needed to override NetEase's internal queue)
- Background song switching (known Android limitation)

## Capabilities

### New Capabilities

- `netease-transition-silence`: Suppress audible third-song bleed during same-platform NetEase-to-NetEase transitions in portrait mode by pausing NetEase between the initial and re-sent deep links

### Modified Capabilities

_(none — no existing spec-level requirements are changing)_

## Impact

- **PlaybackService.kt**: Modified double-send logic in `doLaunchSong()` — adds intermediate pause commands between first and second deep link sends
- **MediaMonitorService.kt**: May need a `pausePackage()` call tuning or new helper for targeted same-platform pause during transition window
- **Platforms affected**: NetEase Cloud Music only (same-platform transitions)
- **No new permissions or dependencies required**

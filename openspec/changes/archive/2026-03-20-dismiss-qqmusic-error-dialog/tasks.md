## 1. PlayerAccessibilityService: Add dialog dismissal

- [x] 1.1 Add `dismissErrorDialog()` method to `PlayerAccessibilityService` that: finds `com.tencent.qqmusic:id/close_btn` node and clicks it, or falls back to `performGlobalAction(GLOBAL_ACTION_BACK)`
- [x] 1.2 Add companion method `dismissQQMusicDialog()` that safely calls `getInstance()?.dismissErrorDialog()` and returns whether the service was available

## 2. PlaybackService: Integrate dismissal into timeout skip

- [x] 2.1 In the playback timeout runnable, when the song is QQ Music and is being skipped: call `PlayerAccessibilityService.dismissQQMusicDialog()` before `playNext()`
- [x] 2.2 Add a 500ms delay between the dismiss call and `playNext()` to let the dialog close animation complete

## 3. Verification

- [x] 3.1 Build passes: `pixi run build`
- [x] 3.2 Manual test: play an unavailable QQ Music song → verify dialog is dismissed automatically before next song launches
- [x] 3.3 Manual test: play a working QQ Music song → verify no false dismissal occurs

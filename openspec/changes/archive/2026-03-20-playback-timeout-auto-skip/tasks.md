## 1. Playback Timeout in PlaybackService

- [x] 1.1 Add timeout constants and state fields: `PLAYBACK_TIMEOUT_MS = 15000L`, `playbackTimeoutRunnable: Runnable?`, `lastLaunchedSongId: Long` in `PlaybackService`
- [x] 1.2 Implement `schedulePlaybackTimeout(song: Song)` method that posts a delayed runnable to check `MediaMonitorService.getInstance()?.getPlaybackInfo()?.isPlaying`. Skip scheduling if `song.platform == Platforms.BILIBILI` or `RemoteMode.isController()`
- [x] 1.3 Implement `cancelPlaybackTimeout()` method that removes the pending runnable from the handler
- [x] 1.4 In the timeout runnable: verify `song.id == lastLaunchedSongId`, check playback state, log failure at WARN level, increment `consecutiveSkips`, show toast "跳过: {title} (播放超时)", call `playNext()` (or stop if max skips reached)
- [x] 1.5 Call `cancelPlaybackTimeout()` at the start of `launchCurrentSong()` to cancel any pending timeout from the previous song
- [x] 1.6 Call `schedulePlaybackTimeout(song)` at the end of `doLaunchSong()` after the deep link is launched
- [x] 1.7 Call `cancelPlaybackTimeout()` in `stop()` and `clearQueue()` to clean up on explicit stop
- [x] 1.8 Reset `consecutiveSkips = 0` when the timeout check finds playback IS active (song started successfully)

## 2. Verification

- [x] 2.1 Build passes: `pixi run build`
- [ ] 2.2 Manual test: launch a known-failing QQ Music song → verify toast "跳过: ... (播放超时)" appears after ~15s and next song plays
- [ ] 2.3 Manual test: launch a working song → verify no timeout skip occurs
- [ ] 2.4 Manual test: rapid next/previous → verify no stale timeout triggers

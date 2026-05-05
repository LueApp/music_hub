## 1. Add title to PlaybackInfo

- [x] 1.1 Add `title: String?` field to `MediaMonitorService.PlaybackInfo` data class
- [x] 1.2 Populate `title` from `MediaMetadata.METADATA_KEY_TITLE` in `getPlaybackInfo()` when returning an active controller

## 2. Fix timeout duration logic

- [x] 2.1 Add `isPlatformSwitch` parameter to `schedulePlaybackTimeout()` (pass from `doLaunchSong`)
- [x] 2.2 Override to cold timeout when `DeepLinkLauncher.landscapeWorkaroundActive && platform == netease`
- [x] 2.3 Override to cold timeout when `isPlatformSwitch == true`
- [x] 2.4 Update log message to include timeout reason ("warm start", "cold start", "cold start (landscape workaround)", "cold start (cross-platform)")

## 3. Add song identity verification to timeout check

- [x] 3.1 In the timeout runnable, when `isPlaying == true`, compare `playbackInfo.title` against `song.title` using contains matching
- [x] 3.2 If title mismatch: log warning with expected vs actual title, treat as timeout failure (skip to next)
- [x] 3.3 If title matches: reset `consecutiveSkips` as before

## 4. Add desync recovery

- [x] 4.1 Add `lastTimedOutSongTitle: String?` field to PlaybackService, set it when a timeout skip occurs
- [x] 4.2 Add a method `scheduleDesyncRecovery()` that checks playback title against `lastTimedOutSongTitle` at 3s and 6s after skip
- [x] 4.3 Wire recovery: scheduled checks after timeout skip, cancelled on new song launch via `cancelPlaybackTimeout()`
- [x] 4.4 When desync detected: pause current playback, clear `lastTimedOutSongTitle`, re-launch current song's deep link
- [x] 4.5 Clear `lastTimedOutSongTitle` on successful playback start (timeout check passes with correct title)

## 5. Test and verify

- [x] 5.1 Build and deploy
- [ ] 5.2 Test cross-platform switch (QQ→NetEase→QQ) with landscape mode - verify cold timeout used
- [ ] 5.3 Test unavailable song skip still works (genuine unavailable songs should still timeout and skip)
- [ ] 5.4 Test same-platform warm start still uses 5s timeout
- [ ] 5.5 Verify desync recovery by checking logs when a timed-out song starts playing late

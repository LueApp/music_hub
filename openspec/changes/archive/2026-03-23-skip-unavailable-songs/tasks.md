## 1. Interface and Data Model

- [x] 1.1 Add `SongAvailability` data class and `checkSongAvailability()` to PlatformHandler

## 2. Platform Checks

- [x] 2.1 NetEase: check `privileges[0].st < 0`
- [x] 2.2 QQ Music: check `track_info.fnote == 4001`
- [x] 2.3 Bilibili: check API response code for video/audio

## 3. PlaybackService

- [x] 3.1 Add coroutine scope, consecutive skip counter, and async check in `launchCurrentSong()`

## 4. Verification

- [x] 4.1 Build passes
- [ ] 4.2 Manual test: unavailable song gets skipped with toast
- [ ] 4.3 Manual test: available songs play normally

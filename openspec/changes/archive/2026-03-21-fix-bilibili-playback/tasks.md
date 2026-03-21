## 1. Fix Bilibili video start position

- [x] 1.1 Update `generateDeepLink()` in `android-app/app/src/main/java/com/musichub/platform/BilibiliPlatform.kt` to append `?start_progress=0` to video deep links (`bilibili://video/{id}?start_progress=0`). Audio deep links remain unchanged.
- [x] 1.2 Update `convertLegacyBilibiliDeepLink()` in `android-app/app/src/main/java/com/musichub/service/DeepLinkLauncher.kt` to append `?start_progress=0` when converting legacy HTTPS video URLs to `bilibili://` scheme.

## 2. Enable Bilibili media session monitoring

- [x] 2.1 Add `Platforms.PACKAGE_NAMES[Platforms.BILIBILI]` to the `targetPackages` set in `onActiveSessionsChanged()` in `android-app/app/src/main/java/com/musichub/service/MediaMonitorService.kt` (line ~196). Update the comment to reflect the change.
- [x] 2.2 Remove the Bilibili exclusion guard in `songFinishedReceiver` in `android-app/app/src/main/java/com/musichub/service/PlaybackService.kt` (lines 85-91: the `if (currentSong?.platform == Platforms.BILIBILI)` block).
- [x] 2.3 Remove the `song.platform == Platforms.BILIBILI` check from `schedulePlaybackTimeout()` in `PlaybackService.kt` (line ~654), keeping only the controller mode check.

## 3. Build verification and testing

- [x] 3.1 Run `pixi run build` and verify no compilation errors
- [x] 3.2 Deploy to device and test: launch a Bilibili video from a playlist — verify it starts from the beginning (not resuming from last position)
- [x] 3.3 Deploy to device and test: let a Bilibili video finish playing — verify Music Hub auto-advances to the next song in the queue

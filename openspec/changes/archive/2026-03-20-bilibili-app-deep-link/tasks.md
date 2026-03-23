## 1. Update BilibiliPlatform deep link generation

- [x] 1.1 Update `generateDeepLink()` in `android-app/app/src/main/java/com/musichub/platform/BilibiliPlatform.kt` to return `bilibili://video/{BVid}` for BV-format videos, `bilibili://video/av{avid}` for AV-format videos, and `bilibili://music/detail/{auid}` for audio content
- [x] 1.2 Verify `generateFallbackUrl()` in `BilibiliPlatform.kt` remains unchanged (still returns HTTPS URLs)

## 2. Add Bilibili-specific handling in DeepLinkLauncher

- [x] 2.1 Add a helper method `convertLegacyBilibiliDeepLink()` in `android-app/app/src/main/java/com/musichub/service/DeepLinkLauncher.kt` that converts legacy HTTPS `bilibili.com` deep links to `bilibili://` scheme (for existing database entries)
- [x] 2.2 Update `launchNormal()` and `launchForLockedScreen()` in `DeepLinkLauncher.kt` to call the conversion method before creating the intent, and to set `intent.setPackage("tv.danmaku.bili")` when the deep link starts with `bilibili://`
- [x] 2.3 Add fallback logic: if launching a `bilibili://` intent throws an exception (app not installed), fall through to the HTTPS fallback URL

## 3. Build verification and testing

- [x] 3.1 Run `pixi run build` and verify no compilation errors
- [x] 3.2 Deploy to device with `pixi run deploy` and test: share a Bilibili video link → verify it opens in the Bilibili app (not browser) *(manual device test)*
- [x] 3.3 Test existing Bilibili songs already in the database open in the Bilibili app (legacy HTTPS link conversion) *(manual device test)*

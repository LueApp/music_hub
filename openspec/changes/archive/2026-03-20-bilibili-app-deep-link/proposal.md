## Why

Bilibili content currently opens in the browser instead of the Bilibili app, even when the app is installed. This happens because `BilibiliPlatform.generateDeepLink()` produces plain `https://www.bilibili.com/...` URLs and `DeepLinkLauncher` fires a generic `ACTION_VIEW` intent without setting a target package. Android's default intent resolution often picks the browser over the Bilibili app for these HTTPS URLs. NetEase and QQ Music don't have this problem because they use custom URI schemes (`orpheus://`, `qqmusic://`).

## What Changes

- Use Bilibili's native `bilibili://video/{id}` custom URI scheme as the primary deep link for video content, instead of HTTPS URLs
- Set `intent.setPackage("tv.danmaku.bili")` in `DeepLinkLauncher` for Bilibili links to explicitly target the Bilibili app
- Fall back to the existing HTTPS URL (browser) only when the Bilibili app is not installed or the custom scheme launch fails
- Handle both BV and AV video ID formats in the `bilibili://` scheme
- Handle audio content (`bilibili://music/detail/{id}` or fallback to HTTPS since audio scheme support is less documented)

## Non-goals

- No changes to NetEase or QQ Music deep link handling
- No changes to how Bilibili metadata is fetched or songs are stored in the database
- No changes to MediaMonitorService behavior for Bilibili content
- No new Android permissions required

## Capabilities

### New Capabilities
- `bilibili-native-deep-link`: Use Bilibili's native `bilibili://` URI scheme and explicit package targeting to open content in the Bilibili app first, falling back to browser only when the app is unavailable.

### Modified Capabilities

## Impact

- **BilibiliPlatform.kt**: `generateDeepLink()` will return `bilibili://video/{id}` URLs instead of `https://www.bilibili.com/video/{id}`. `generateFallbackUrl()` remains unchanged (HTTPS URLs for browser fallback).
- **DeepLinkLauncher.kt**: Add Bilibili-specific intent handling with `setPackage("tv.danmaku.bili")` and fallback logic when the app is not installed.
- **Stored data**: Existing songs in the database have HTTPS deep links stored. Migration or runtime conversion needed so existing Bilibili songs also benefit from the new behavior.
- **No new dependencies or permissions required**.

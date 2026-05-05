## Context

Bilibili content in Music Hub currently opens in the browser rather than the Bilibili app (`tv.danmaku.bili`), even when it's installed. This happens because:

1. `BilibiliPlatform.generateDeepLink()` returns plain `https://www.bilibili.com/...` URLs (identical to `generateFallbackUrl()`)
2. `DeepLinkLauncher` fires a generic `ACTION_VIEW` intent without `setPackage()` for Bilibili links
3. Android's default intent resolution for HTTPS URLs often picks the browser over the native app, especially on newer Android versions where App Links verification is stricter

In contrast, NetEase uses `orpheus://` and QQ Music uses `qqmusic://` custom URI schemes, which reliably resolve to their respective apps.

## Goals / Non-Goals

**Goals:**
- Bilibili video content opens directly in the Bilibili app when it's installed
- Bilibili audio content opens in the Bilibili app when possible
- Graceful fallback to browser when the Bilibili app is not installed
- Existing Bilibili songs already in the database work with the new behavior (no database migration needed)

**Non-Goals:**
- No changes to NetEase or QQ Music deep link handling
- No changes to metadata fetching, media monitoring, or song storage
- No database schema migration (we handle this at runtime)

## Decisions

### 1. Use `bilibili://video/{id}` custom URI scheme for video deep links

**Decision**: Change `generateDeepLink()` to return `bilibili://video/{BVid}` or `bilibili://video/av{avid}` for video content.

**Rationale**: The Bilibili Android app registers `bilibili://video/{id}` as an intent filter and supports both BV and AV ID formats. This is the same pattern used by NetEase (`orpheus://`) and QQ Music (`qqmusic://`) — a custom URI scheme guarantees app resolution without disambiguation dialogs.

**Alternatives considered**:
- **`setPackage()` with HTTPS URLs**: Would force the Bilibili app to handle the URL, but requires checking if the app is installed first and doesn't solve the problem if Android prevents HTTPS URLs from being routed to specific apps (Android 12+ verified links behavior).
- **`bilibili://browser/?url=...`**: Opens the URL in Bilibili's in-app browser rather than navigating to the video page natively — suboptimal UX.

### 2. Use `bilibili://music/detail/{auid}` for audio deep links, with HTTPS fallback

**Decision**: For audio content (`audio:{id}`), attempt `bilibili://music/detail/{auid}`. If no handler is found, fall back to the HTTPS URL.

**Rationale**: Bilibili's audio deep link scheme is less well-documented than video. The `bilibili://music/detail/{id}` route is referenced in some sources but not universally confirmed. Using it as primary with HTTPS fallback is the safest approach.

### 3. Add `setPackage("tv.danmaku.bili")` in DeepLinkLauncher for Bilibili custom scheme links

**Decision**: When the deep link starts with `bilibili://`, set the intent package to `tv.danmaku.bili` to explicitly target the Bilibili app.

**Rationale**: Even with custom URI schemes, Android could theoretically show a disambiguation dialog if another app registers the same scheme. Explicitly setting the package eliminates this. The existing `isAppInstalled()` method in DeepLinkLauncher can verify the app is present before attempting.

### 4. Handle existing database entries at runtime, not via Room migration

**Decision**: Existing Bilibili songs stored in the database have HTTPS deep links (e.g., `https://www.bilibili.com/video/BVxxx`). Rather than a database migration, `DeepLinkLauncher` will detect Bilibili HTTPS URLs at launch time and convert them to `bilibili://` scheme on the fly. The `generateDeepLink()` change ensures all newly added songs get the correct format.

**Rationale**: Room migrations add complexity and version tracking. Since we can trivially detect and convert Bilibili URLs at runtime (check if URL contains `bilibili.com/video/` or `bilibili.com/audio/`), runtime conversion is simpler and less error-prone. Existing database records remain valid as fallback URLs.

**Alternatives considered**:
- **Room migration**: Update all Bilibili deep links in the database. Adds schema version bump and migration code. Fragile if the deep link format changes again.
- **Update on access**: Convert and write-back the deep link when a song is loaded. Adds unnecessary database writes.

## Risks / Trade-offs

- **`bilibili://video/` scheme may not work on all Bilibili app versions** → Mitigation: Fall back to HTTPS URL if the custom scheme intent throws an exception or if the app is not installed. The fallback path already exists in `DeepLinkLauncher.launchNormal()`.

- **Audio deep link scheme is less documented** → Mitigation: Use `bilibili://music/detail/{id}` as primary but rely on HTTPS fallback. Audio content is less common in the app's usage.

- **Runtime URL conversion adds a small code path in DeepLinkLauncher** → Mitigation: The conversion is a simple string check and replace, isolated to one method. Minimal complexity.

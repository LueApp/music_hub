## Why

Music Hub currently supports NetEase Cloud Music, QQ Music, and Bilibili. Many Chinese users keep their music on Kugou (酷狗音乐) and cannot share Kugou songs or playlists into the app. Adding Kugou as a fourth platform unifies the user's library across the four largest mainland Chinese music apps and lets users import shared Kugou songlist links such as `https://m.kugou.com/songlist/gcid_3zljhp4bz2z02f/...` directly into Music Hub.

## What Changes

- Add a new `KugouPlatform` handler implementing `PlatformHandler` (URL parsing, deep link generation, metadata fetch, availability check, playlist fetch).
- Recognize Kugou single-song URLs (`m.kugou.com/song/...`, `www.kugou.com/song/...`, hash-based share links) and playlist/songlist URLs (`m.kugou.com/songlist/gcid_<id>/`, `www.kugou.com/yueku/...`).
- Generate `kugou://` custom-scheme deep links targeting `com.kugou.android`, with HTTPS fallback to `m.kugou.com` when the Kugou app is not installed.
- Register Kugou in `Platforms` constants (`Platforms.KUGOU`, package `com.kugou.android`, display name `酷狗音乐`).
- Wire Kugou into `LinkParser`, `DeepLinkLauncher`, `PlaybackService`, `MediaMonitorService`, `ShizukuLauncher`, and the share-receiver flow so it behaves like an existing platform.
- Surface Kugou in the UI: platform badge / icon in `SongAdapter`, `QueueAdapter`, `SkipLogAdapter`, `ChartAdapter`, `SelectableSongAdapter`, `DiscoverSongAdapter`; filters in `LibraryFragment`, `ImportFromLibraryFragment`, `PlaylistDetailFragment`; "Add Song" / "Manage Sources" platform handling.
- Add Kugou drawables (`ic_kugou.xml`, `bg_badge_kugou.xml`), a Kugou brand color, and Chinese strings.
- No new Android permissions; no schema migration (existing `(platform, platform_song_id)` unique constraint already accommodates a new `platform = "kugou"` value).

## Non-goals

- No Kugou login / authenticated personal-recommendation support in this change. Kugou auth (cookies, MV/VIP-only handling) is deferred — `PlatformAuthManager.SUPPORTED_PLATFORMS` stays `[netease, qqmusic]`.
- No background-only song switching for Kugou (same Android limitation as other platforms — Kugou will be foregrounded during playback).
- No Kugou-specific landscape/orientation workaround (only NetEase needs that).
- No Kugou MV / video-only content handling — songs only.
- No new "ShareSongs" Kugou-specific accessibility behavior beyond what already works for QQ Music.

## Capabilities

### New Capabilities
- `kugou-platform`: A complete Kugou Music platform handler — URL parsing (song + songlist), `kugou://` deep link generation with HTTPS fallback, metadata fetching from Kugou's web API, availability checking, and playlist (songlist) import.

### Modified Capabilities

_None._ The existing `PlatformHandler` interface and `Platforms` registry are designed to be extended; no other capability's specified behavior changes. Per-platform behaviors that already enumerate `NETEASE / QQMUSIC / BILIBILI` (e.g. `MediaMonitorService` watched packages, `DeepLinkLauncher` package targeting) are extended in the same shape, not modified at the requirement level.

## Impact

- **New file**: `android-app/app/src/main/java/com/musichub/platform/KugouPlatform.kt` (~500 LOC, mirrors `BilibiliPlatform.kt` shape).
- **Modified**: `platform/PlatformHandler.kt` (add `KUGOU` constant + map entries), `platform/LinkParser.kt` (add to `handlers` list + Kugou short-URL domains like `t1.kugou.com`), `platform/DiscoveryApi.kt` (Kugou playlist square / charts hooks).
- **Modified services**: `service/DeepLinkLauncher.kt` (add `isKugouLink` + `setPackage("com.kugou.android")`), `service/PlaybackService.kt` (`getHandlerForPlatform` switch), `service/MediaMonitorService.kt` (Kugou added to watched packages list), `service/ShizukuLauncher.kt` (`musicAppPackages` + `packageForDeepLink`), `service/FloatingWindowService.kt` (icon resolution).
- **Modified UI**: 6 adapters (badge resolution), 4 fragments (filter chips / platform branching), `auth/PlatformLoginActivity.kt` (display name only — no login flow yet), `ui/viewmodel/ManageSourcesViewModel.kt` (handler dispatch).
- **New resources**: `res/drawable/ic_kugou.xml`, `res/drawable/bg_badge_kugou.xml`, color entry in `res/values/colors.xml`, strings in `res/values/strings.xml`.
- **Modified resources**: `res/values/arrays.xml` (platform-filter arrays), 3 layout files (filter chip group), and `res/layout/fragment_home.xml` (Kugou source filter).
- **Networking**: Kugou web API (`mobilecdnbj.kugou.com`, `m.kugou.com`, `wwwapi.kugou.com`) accessed via existing OkHttp client — no new dependency, no new permission. Cleartext is already enabled (`usesCleartextTraffic="true"`).
- **Database**: No schema change; new `Song.platform = "kugou"` rows reuse the existing unique index on `(platform, platform_song_id)`.
- **Build**: No Gradle dependency changes. APK size grows slightly from the new vector icon + Kotlin file.

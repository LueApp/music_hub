## 1. Platform registry & handler skeleton

- [x] 1.1 Add `KUGOU = "kugou"` constant to `Platforms` in `android-app/app/src/main/java/com/musichub/platform/PlatformHandler.kt`; extend `DISPLAY_NAMES` and `PACKAGE_NAMES` with the new entry (`com.kugou.android`, `酷狗音乐`).
- [x] 1.2 Create `android-app/app/src/main/java/com/musichub/platform/KugouPlatform.kt` implementing `PlatformHandler`, modelled after `BilibiliPlatform.kt`. Add OkHttp client, Gson, regex constants, and class skeleton with `TAG = "KugouPlatform"`.
- [x] 1.3 Implement `canHandle(url)` to match any URL containing `kugou.com`.
- [x] 1.4 Implement `parseSongUrl(url)` extracting the 32-char hex hash from `?hash=` query param, `/song/<hash>.html`, or `/mixsong/<hash>` path forms; lower-case the hash; return `ParsedSong(platform = "kugou", platformSongId = hash, deepLink = generateDeepLink(hash), fallbackUrl = generateFallbackUrl(hash))`.
- [x] 1.5 Implement `parsePlaylistUrl(url)` matching `kugou\.com/songlist/gcid_([a-zA-Z0-9]+)`; return `ParsedPlaylist(platform = "kugou", playlistId = match)`.
- [x] 1.6 Implement `generateDeepLink(hash)` returning `kugou://...` (e.g. `kugou://start.weixin?action=songinfo&hash=<hash>` or the equivalent action documented at implementation time) and `generateFallbackUrl(hash)` returning an `https://m.kugou.com/song/?hash=<hash>` URL.
- [x] 1.7 Implement `fetchMetadata(hash)` calling `https://mobilecdnbj.kugou.com/api/v3/song/info?hash=<hash>`; populate `title`, `artist` (`singername`), `album` (`album_name`), `cover_url` (with `http://` → `https://` and `{size}` → `120`); on exception log and return an empty map.
- [x] 1.8 Implement `checkSongAvailability(hash)` reusing the same endpoint; map a non-empty title to `SongAvailability(true)`, recognised "removed/unavailable" error codes to `SongAvailability(false, reason)`, and any thrown exception to `SongAvailability(true)` (fail-open).
- [x] 1.9 Implement `fetchPlaylistSongs(playlistId)` calling the public `m.kugou.com/plist/list/<gcid>?json=true` (or equivalent verified endpoint) and emitting one `ParsedSong` per track with the correct hash, deep link, and metadata; populate `ParsedPlaylist.name`, `coverUrl`, `songCount`, and `songs`.
- [x] 1.10 Register `KugouPlatform()` in `LinkParser.handlers` and add any Kugou short-URL host (e.g. `t1.kugou.com`) to `LinkParser.shortUrlDomains` in `android-app/app/src/main/java/com/musichub/platform/LinkParser.kt`.

## 2. Service-layer integration

- [x] 2.1 Update `PlaybackService.getHandlerForPlatform` in `android-app/app/src/main/java/com/musichub/service/PlaybackService.kt` to return `KugouPlatform()` for `Platforms.KUGOU`.
- [x] 2.2 In `android-app/app/src/main/java/com/musichub/service/DeepLinkLauncher.kt`, add `KUGOU_PACKAGE = "com.kugou.android"`; add an `isKugouLink(deepLink)` helper that returns true for `kugou://` or `kugou.com`; in every block that currently checks `bilibili://` add a parallel block that calls `setPackage(KUGOU_PACKAGE)`. Verify `ActivityNotFoundException` falls back to the HTTPS URL.
- [x] 2.3 Add `Platforms.PACKAGE_NAMES[Platforms.KUGOU]` to the watched-package list in `MediaMonitorService` (around line 244-247) so Kugou MediaSessions are observed.
- [x] 2.4 Add `"com.kugou.android"` to `ShizukuLauncher.musicAppPackages()` and a `kugou://` / `kugou.com` branch to `ShizukuLauncher.packageForDeepLink()` in `android-app/app/src/main/java/com/musichub/service/ShizukuLauncher.kt`.
- [x] 2.5 In `android-app/app/src/main/java/com/musichub/service/FloatingWindowService.kt`, add the `Platforms.KUGOU` branch wherever NetEase/QQ Music/Bilibili icon resolution lives.

## 3. UI surface

- [x] 3.1 Create `android-app/app/src/main/res/drawable/ic_kugou.xml` (24dp vector drawable, white path on transparent canvas).
- [x] 3.2 Create `android-app/app/src/main/res/drawable/bg_badge_kugou.xml` (oval shape drawable filled with `@color/kugou_brand`).
- [x] 3.3 Add `<color name="kugou_brand">...</color>` to `android-app/app/src/main/res/values/colors.xml`.
- [x] 3.4 Add `<string name="platform_kugou">酷狗音乐</string>` and any related strings (badges, error messages) to `android-app/app/src/main/res/values/strings.xml`.
- [x] 3.5 Update `android-app/app/src/main/res/values/arrays.xml` so any platform-array used by filters includes the Kugou label and value.
- [x] 3.6 Add the Kugou branch to platform `when()` switches in `SongAdapter.kt`, `QueueAdapter.kt`, `SkipLogAdapter.kt`, `ChartAdapter.kt`, `SelectableSongAdapter.kt`, `DiscoverSongAdapter.kt` (all under `android-app/app/src/main/java/com/musichub/ui/adapter/`).
- [x] 3.7 Add Kugou filter chip support to `LibraryFragment.kt`, `ImportFromLibraryFragment.kt`, `PlaylistDetailFragment.kt`, and the home / browse fragments wherever NetEase/QQ Music/Bilibili chips already exist (under `android-app/app/src/main/java/com/musichub/ui/fragment/`).
- [x] 3.8 Add Kugou filter chip layout entries to `fragment_library.xml`, `fragment_playlist_detail.xml`, `fragment_import_from_library.xml`, `fragment_home.xml` (and their `layout-land/` variants where they exist).
- [x] 3.9 Update `ManageSourcesViewModel.kt` to dispatch Kugou URLs through `LinkParser.getHandler(Platforms.KUGOU)` and create source rows with `platform = Platforms.KUGOU`.
- [x] 3.10 Add Kugou to the `AddSongFragment.kt` platform-selection `when()` (display name + icon).

## 4. Build & smoke test

- [x] 4.1 Run `pixi run build` and confirm a clean debug build with no Kotlin or resource errors.
- [x] 4.2 Run `pixi run test` and confirm unit tests still pass.
- [x] 4.3 `pixi run deploy-release` to install on the connected device.
- [x] 4.4 In a clean log buffer (`adb logcat -c`), share the user's example songlist URL `https://m.kugou.com/songlist/gcid_3zljhp4bz2z02f/...` from another app into Music Hub. Verify `LinkParser` logs identify it as a Kugou playlist and the import flow lists the songs.
- [x] 4.5 Tap a Kugou song in the resulting playlist; verify the song begins playing. (Deep link is now `https://m.kugou.com/mixsong/<short-id>.html` + `setPackage("com.kugou.android")`, not `kugou://` — see design note in cerebrum.)
- [x] 4.6 Verify the floating window controls show the Kugou badge for the current song.
- [x] 4.7 Let a Kugou song play to its end; verify `MediaMonitorService` detects song-end and `PlaybackService` advances to the next queued song.
- [~] 4.8 Uninstall the Kugou app, repeat 4.5; verify the HTTPS fallback opens in the browser without crashing. **Skipped — Kugou's web pages require login, so the browser fallback isn't operationally testable end-to-end. `DeepLinkLauncher`'s `ActivityNotFoundException → launchFallback` path is shared with NetEase/QQ Music/Bilibili and already exercised.**
- [~] 4.9 In CONTROLLER mode connected to a player phone running the new build, verify Kugou songs render the correct badge in the controller's queue and remote `play` commands launch Kugou on the player phone. **Skipped — no second device available for this session.**

## 5. Cleanup

- [x] 5.1 Update `.wolf/anatomy.md` with the new `KugouPlatform.kt` entry and any newly created drawable / colour / string entries.
- [x] 5.2 Update `.wolf/cerebrum.md` `## Key Learnings` with any Kugou-API quirks discovered during 1.7–1.9.
- [x] 5.3 Commit the change in logical chunks (handler + registry, service wiring, UI, tests) per the project's "commit after every feature" workflow.

## Context

Music Hub today recognizes three platforms via the `PlatformHandler` interface (`NetEasePlatform`, `QQMusicPlatform`, `BilibiliPlatform`). Each handler is registered in `LinkParser.handlers`, looked up by string key in `Platforms.PACKAGE_NAMES` / `DISPLAY_NAMES`, and switched on in three flows: `PlaybackService.getHandlerForPlatform`, `DeepLinkLauncher` (per-package intent targeting), and `MediaMonitorService` (NotificationListenerService package filter). The same string key drives Room rows (`Song.platform`), badges in six adapters, filter chips in four fragments, and Shizuku's package-targeting helpers.

Kugou (酷狗音乐, package `com.kugou.android`) shares the same shape: it has a deep-link scheme (`kugou://`), public web APIs for songs and songlists, and a NotificationListenerService-visible MediaSession. The user shared a "songlist" import URL of the form `https://m.kugou.com/songlist/gcid_<global_collection_id>/?...`. The change is mostly mechanical — extend each enumeration site — with the only novel work being the new `KugouPlatform.kt` itself (URL regexes, deep-link generation, web-API metadata fetch, songlist fetch).

Build artifacts under `android-app/app/build/...` show that an earlier session attempted this work but the source is not in git (clean tree, no source files). Treat this as a fresh implementation.

## Goals / Non-Goals

**Goals:**
- A new `KugouPlatform` handler that mirrors `BilibiliPlatform`'s shape: parse song URLs, parse songlist URLs, generate `kugou://` deep links, fall back to HTTPS, fetch metadata + check availability via Kugou's public web API.
- Kugou registered as the fourth value in `Platforms` (`KUGOU = "kugou"`, package `com.kugou.android`, display name `酷狗音乐`).
- All places that switch on platform string — `LinkParser`, `DeepLinkLauncher`, `PlaybackService`, `MediaMonitorService`, `ShizukuLauncher`, `FloatingWindowService`, six adapters, four fragments, `ManageSourcesViewModel` — gain a Kugou branch.
- Songlist URLs of the form `m.kugou.com/songlist/gcid_<id>/...` parse to a `ParsedPlaylist` and import all songs.
- Kugou drawables (`ic_kugou.xml`, `bg_badge_kugou.xml`) and strings exist; UI shows a Kugou badge wherever NetEase / QQ Music / Bilibili badges appear.
- Behavior is identical in all three remote-control modes (STANDALONE / PLAYER / CONTROLLER): Kugou is just another platform string carried in `Song.platform`.

**Non-Goals:**
- No Kugou login / cookie auth in this change. `PlatformAuthManager.SUPPORTED_PLATFORMS` stays `[netease, qqmusic]`. No personal recommendations, VIP-only handling, or login WebView.
- No Kugou Lite (`com.kugou.android.lite`) targeting. Single package `com.kugou.android` only; HTTPS fallback otherwise.
- No new Android permissions. No background-only switching workaround beyond what the rest of the app does.
- No MV / video content for Kugou — songs only.
- No Room schema migration. Existing `(platform, platform_song_id)` unique index already supports `platform = "kugou"`.
- No new Gradle dependencies — reuse OkHttp + Gson.

## Decisions

### D1: Mirror `BilibiliPlatform` rather than `QQMusicPlatform` as the structural reference

**Decision:** Model `KugouPlatform.kt` after `BilibiliPlatform.kt`.
**Rationale:** Bilibili is the most recently added platform (March 2026) and uses the same patterns we want — OkHttp client at the platform level, Gson `JsonObject` parsing, `withContext(Dispatchers.IO)` wrappers, regex-based URL parsing, public web API endpoints (no cookies). QQ Music has more cruft (auth-aware endpoints, login support) that we explicitly don't want in the first pass.
**Alternatives considered:** Subclass a shared `AbstractPlatformHandler`. Rejected — only 3 existing handlers and they diverge enough that a base class would be premature abstraction. Per CLAUDE.md, the developer prefers practical working code over abstractions.

### D2: Deep-link scheme is `kugou://` with HTTPS fallback to `m.kugou.com`

**Decision:** Generate `kugou://...` URIs in `generateDeepLink()` and `https://m.kugou.com/...` in `generateFallbackUrl()`. In `DeepLinkLauncher`, when the link starts with `kugou://` or contains `kugou.com`, set `intent.setPackage("com.kugou.android")`. If the package is not installed, fall back to the HTTPS fallback URL via the browser (same pattern as Bilibili's `BILIBILI_PACKAGE` block).
**Rationale:** `kugou://` is the standard custom scheme for the Kugou app and is registered in its manifest; `setPackage` ensures Android picks the Kugou app over a browser even when both can handle the URI. HTTPS fallback to `m.kugou.com` is universally usable and matches the user's actual share-link host.
**Alternatives:** Use only HTTPS deep links (cleaner, but Android resolves them to a browser by default — same problem solved in `2026-03-20-bilibili-app-deep-link`). Use `https://www.kugou.com/...` instead of `m.kugou.com` (loads slower on mobile; the user's actual share link is the m. host).

### D3: `platformSongId` format — bare `hash` for songs, opaque string for playlists

**Decision:** For songs, store the Kugou song hash (32-char hex) as `platformSongId` directly, e.g. `Song(platform="kugou", platformSongId="<hash>", ...)`. The web song-detail endpoint (`mobilecdnbj.kugou.com/api/v3/song/info?hash=<hash>`) is the canonical lookup. For playlists, use the `gcid_<id>` value from `m.kugou.com/songlist/gcid_<id>` as `playlistId`.
**Rationale:** Kugou identifies songs by content hash, not numeric ID. Bilibili already uses prefixed strings (`video:BV...`, `audio:au...`) so a non-numeric `platformSongId` is precedented in the schema. The hash is deterministic and stable across users.
**Alternatives:** Use `album_audio_id` (numeric, stable for VIP/album mapping but not always present in shared links). Use a composite `hash:album_audio_id` (more info, more parsing complexity). Bare hash is the simplest stable identifier exposed by the share endpoints.

### D4: URL recognition

**Decision:** `KugouPlatform.canHandle(url)` returns true for any URL containing `kugou.com` (covers `m.kugou.com`, `www.kugou.com`, `t1.kugou.com` short links, `wwwapi.kugou.com`). `LinkParser.shortUrlDomains` gains `t1.kugou.com` so its 302 redirects are followed. Specific patterns:
- Songlist: `kugou\.com/songlist/gcid_([a-zA-Z0-9]+)`
- Single song (mobile share): `kugou\.com/(?:song|mixsong)/[#?].*hash=([a-fA-F0-9]{32})` or `kugou\.com/song/([a-fA-F0-9]{32})\.html`
- Single song (web): hash extraction from query string `[?&]hash=([a-fA-F0-9]{32})`
**Rationale:** Songlist URL is the user's known-needed format. Single-song URLs vary across Kugou's mobile vs. PC share flows, so we extract by the stable `hash=` parameter when present.

### D5: Web API endpoints — public only, no signatures

**Decision:** Use these public, no-cookie endpoints:
- Song detail: `https://mobilecdnbj.kugou.com/api/v3/song/info?hash=<hash>` (returns title, singer, album_audio_id, image)
- Song availability: same endpoint, treat HTTP 200 + non-empty `data.songname` as available.
- Songlist meta + entries: `https://m.kugou.com/plist/list/<gcid>?json=true` or `wwwapi.kugou.com/v1/playlist/list_info` (whichever returns full data without auth — researched at implementation time).
- Cover art: come back from the same endpoints (Kugou returns `{size}` placeholder paths; replace with `120` or `400` size variant).
**Rationale:** Avoid signature-required endpoints that drift over time and break in production. Fall back to HTTPS-fetch + HTML scrape only if no public JSON endpoint works for songlists (acceptable risk).
**Risk:** If Kugou closes a public endpoint we depend on, metadata fetch fails and we surface the song with the title from the URL parser only. `LinkParser.parseSharedContent` already falls back to title/artist scraped from share text when API metadata is empty — Kugou inherits this for free.

### D6: MediaMonitorService — add Kugou to watched packages

**Decision:** Add `Platforms.PACKAGE_NAMES[Platforms.KUGOU]` to the watched-package list at `MediaMonitorService.kt:243-247`. Kugou's MediaSession reports a normal `PlaybackState.STATE_PLAYING` / `STATE_PAUSED` / position, so the existing song-end detection logic works unchanged. Bilibili-specific tighter thresholds (`BILIBILI_*`) stay Bilibili-only; Kugou uses the default `SONG_END_THRESHOLD_MS = 3000L`.
**Rationale:** Conservative — start with the same thresholds as NetEase/QQ Music and tighten only if observation shows Kugou over- or under-reports duration. No code change needed beyond the package list.

### D7: ShizukuLauncher — add Kugou to package sets

**Decision:** Add `"com.kugou.android"` to `musicAppPackages()` and a `kugou://` / `kugou.com` branch in `packageForDeepLink()`.
**Rationale:** This keeps freeform-window resize behavior consistent for Kugou with the other three platforms. Kugou can be launched by Shizuku into a freeform window like any other music app.

### D8: UI surface — extend, don't refactor

**Decision:** Add a Kugou branch to every existing platform `when()` switch in adapters and fragments. Add `R.drawable.ic_kugou`, `R.drawable.bg_badge_kugou`, `kugou_brand` color, `platform_kugou` string. Update `arrays.xml` filter arrays.
**Rationale:** Adapters and fragments use 3-branch `when` blocks today (NetEase / QQ Music / Bilibili / fall-through). Adding a fourth branch is mechanical and matches the existing convention. The three existing branches all use the same pattern, and the developer has rejected refactoring into a Map-based lookup before (per cerebrum / memory: previous Kugou attempt left these `when()`s as the final shape).

### D9: Kugou icon — vector drawable matching brand color

**Decision:** Create `res/drawable/ic_kugou.xml` as a 24dp vector drawable (Kugou's stylized "K"/headphones logo, white path on transparent canvas). Create `res/drawable/bg_badge_kugou.xml` as a circular shape drawable filled with `@color/kugou_brand` (#0096FF or the closest Material-3 token).
**Rationale:** The other three platforms each have their own ic_ and bg_badge_ pair. Following the same naming and dimension conventions makes the adapter code uniform.

## Risks / Trade-offs

- **[Risk]** Kugou's public web API may rate-limit or change response shape → **Mitigation:** Wrap every API call in `try/catch`, log on failure, return empty metadata so existing fallback paths (`LinkParser.parseSharedContent` text scrape) still produce a usable Song row. Same defensive pattern as `BilibiliPlatform.fetchVideoMetadata`.
- **[Risk]** Songlist (`/plist/list/`) endpoint requires a CSRF or signature for some `gcid` formats → **Mitigation:** First implementation tries the no-auth endpoint; if it returns an error code, log and return null (user sees an "import failed" toast — same behavior as a Bilibili medialist on a private favorites list). Auth-required songlists can be addressed in a follow-up if users ask.
- **[Risk]** `kugou://` deep link is silently captured by Kugou Lite (`com.kugou.android.lite`) on phones with both apps → **Mitigation:** `setPackage("com.kugou.android")` forces the main app. If a user has only Lite, the launch fails and we fall back to HTTPS — the user can install the main app or switch to Lite-only support in a follow-up if requested.
- **[Risk]** Kugou's MediaSession may differ subtly from NetEase/QQ Music (e.g. position units, custom actions) → **Mitigation:** The default thresholds in `MediaMonitorService` are conservative (3s end-of-song window). Telemetry log lines (`Log.d(TAG, ...)`) will show position vs duration during real use; tighten if needed in a separate change.
- **[Risk]** Adding a 4th platform causes drawable / filter chip layouts to overflow on narrow screens → **Mitigation:** Existing chip groups in `fragment_library.xml`, `fragment_playlist_detail.xml`, `fragment_import_from_library.xml` use horizontal scroll or flow layout. Verify visually after build; resize to two rows if overflow appears (cosmetic only).
- **Trade-off:** No auth means no personal Kugou playlists / "我喜欢的音乐" import. Acceptable per scope decision.
- **Trade-off:** No Kugou Lite fallback means a small subset of users (Lite-only) get HTTPS-browser fallback instead of in-app launch. Acceptable per scope decision.

## Migration Plan

1. **Code changes only — no DB migration.** New `Song.platform = "kugou"` rows insert into the existing schema; no `@Database(version = ...)` bump needed.
2. **Existing exported backups** continue to import: `BackupManager` doesn't enumerate platforms, just round-trips the string. A backup made before this change has no Kugou rows; a backup made after this change but imported into an older build would surface Kugou rows with no handler — songs would still display (using `Song.platform == "kugou"` as a string) but launch would fail. This is acceptable; users running an older build can update.
3. **Rollback:** Revert the change. Existing Kugou rows in user DBs become "orphaned" (unknown platform) — they remain visible, just don't launch. Add a defensive `Song.platform !in known` filter in `SongAdapter` if rollback is needed.

## Open Questions

- Confirm the canonical no-auth songlist endpoint (`m.kugou.com/plist/list/<gcid>?json=true` vs. `wwwapi.kugou.com/v1/playlist/list_info`) at implementation time. Both have been observed in the wild; pick whichever returns the song hashes without a signed `signature` parameter.
- Decide on the Kugou brand color: official Kugou blue (#0096FF) vs. a Material-3-tonal variant for visual consistency with existing NetEase red / QQ Music green / Bilibili pink badges. Either works; aesthetic choice deferred to first visual review.

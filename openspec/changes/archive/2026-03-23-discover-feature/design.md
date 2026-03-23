## Context

Music Hub currently only handles songs users explicitly add via shared URLs or playlist imports. There is no way to browse or discover new music within the app. The platform APIs (NetEase, QQ Music, Bilibili) all offer public chart/ranking data and curated playlist browsing without authentication, plus personalized recommendations with authentication.

The app already has the networking infrastructure (OkHttp per platform handler), the data pipeline (`ParsedSong` → `Song` entity), and playlist sync (`SyncSource` + `PlaylistSyncEngine`). The discovery feature builds on these foundations.

Current bottom navigation has 4 tabs (Home, Library, Playlists, Settings). `BottomNavigationView` supports up to 5 items, so one more tab can be added.

## Goals / Non-Goals

**Goals:**
- Add a Discover tab for browsing charts, curated playlists, and personal recommendations across platforms
- Reuse existing platform handler API patterns and data models
- Enable "preview then add" workflow using deep links for previewing and existing add-to-library pipeline for saving
- Support all three app modes (standalone, player, controller) — discovery always runs locally since it's about browsing, not playback control

**Non-Goals:**
- Music streaming or downloading
- Platform catalog search
- Persistent/cached discovery data in Room (all discovery data is in-memory)
- Bilibili personal recommendations (no music-specific recommendation API)

## Decisions

### 1. In-memory data model (no new Room entities)

Discovery results are transient browsing data, not user-owned content. They are fetched on demand and held in ViewModel `StateFlow`. Songs only enter Room when the user explicitly adds them via the existing `repository.insertSong()` path.

**Alternative considered:** Caching charts in Room for offline access. Rejected because charts update frequently, the data is small, and adding Room entities + migrations adds complexity for minimal benefit.

### 2. ViewPager2 + TabLayout for section navigation

The Discover fragment uses `ViewPager2` with a `TabLayout` to switch between Charts, Browse, and For You sections. Each section is a nested fragment.

**Alternative considered:** Single scrolling page with all sections stacked. Rejected because it would load all sections at once (including auth-gated recommendations) and wouldn't scale well.

### 3. Extend existing platform handlers rather than a separate DiscoveryApi class

New discovery methods (chart fetching, browse, recommendations) are added directly to `NetEasePlatform`, `QQMusicPlatform`, and `BilibiliPlatform` since they share the same OkHttp client, headers, and JSON parsing patterns. A lightweight `DiscoveryApi` object acts as a coordinator/router that delegates to the right platform handler.

**Alternative considered:** Completely separate `DiscoveryApi` class with its own HTTP clients. Rejected because it would duplicate OkHttp setup and header configuration already in each platform handler.

### 4. WebView-based login for auth (Stage 3)

A single `PlatformLoginActivity` parameterized by platform name. Loads the platform's web login page in a WebView, monitors `CookieManager` for auth cookies, extracts and stores them on success.

**Alternative considered:** OAuth flows or QR code login. OAuth isn't offered by these platforms for third-party use. QR code login is possible but adds complexity (polling, rendering QR) with no clear benefit over WebView.

### 5. EncryptedSharedPreferences for cookie storage

Auth cookies stored via `EncryptedSharedPreferences` from `androidx.security:security-crypto`. Simple key-value storage keyed by platform name. No Room involvement.

**Alternative considered:** Plain SharedPreferences. Rejected because auth cookies are sensitive credentials that should be encrypted at rest.

### 6. Charts use hardcoded IDs, categories use a mix

NetEase chart IDs are hardcoded (these are well-known, stable playlist IDs). NetEase browse categories start hardcoded (华语, 电子, 古风, etc.) but can optionally fetch the full category list from the API. QQ Music toplists are fetched dynamically via the `GetAll` API since their IDs change.

### 7. Behavior across app modes

Discovery is a **local-only** feature in all modes:
- **Standalone/Player**: Full functionality — browse charts, curated playlists, recommendations; add songs to local library; preview via deep link
- **Controller**: Discovery works the same (browsing is local), but "preview" deep links launch on the controller phone (not the player phone). Adding songs adds to the controller's local library only. This is acceptable since discovery is about finding songs, not playing them through the playback queue.

## Risks / Trade-offs

**[API stability]** → Platform APIs are unofficial and undocumented. They could change or be rate-limited without notice. Mitigation: Use the same API patterns already working in the existing platform handlers. Add error handling with user-friendly messages. Keep chart IDs configurable so they can be updated without code changes.

**[Auth cookie expiration]** → Platform login cookies expire (NetEase MUSIC_U lasts weeks-months, QQ Music cookies expire sooner). Mitigation: Detect 401/403 responses from recommendation APIs and prompt re-login. Show "session expired" state in the For You tab.

**[5-tab bottom nav crowding]** → Adding a 5th tab to the icon-only bottom nav makes targets smaller. Mitigation: Bottom nav is already `unlabeled` mode (icon-only), and 5 icons at 48dp height is a standard Android pattern. Material Design guidelines support up to 5 destinations.

**[WebView login trust]** → Users must enter their platform credentials in a WebView within our app. Mitigation: The WebView loads the official platform login URL (not a custom form). Users can verify the URL in the WebView. This is the standard approach used by all third-party music clients.

**[Gson null safety]** → New JSON response parsing with Gson bypasses Kotlin null safety. Mitigation: Use defensive null checks and default values in data classes, following the existing pattern in all platform handlers.

## Why

Music Hub currently only lets users manage songs they already know about — added via shared URLs or playlist imports. There is no way to discover new music within the app. Every major music platform (NetEase Cloud Music, QQ Music) offers charts, curated playlists, and personalized daily recommendations that surface new songs. Adding a discovery feature lets users explore new music across platforms and add what they like to their library, making Music Hub a complete music management hub rather than just a playlist organizer.

## What Changes

- Add a new "Discover" (发现) bottom navigation tab as the 5th tab
- **Stage 1 — Charts**: Browse top charts/rankings from NetEase (热歌榜, 新歌榜, 飙升榜, 原创榜), QQ Music (巅峰榜, 热歌榜), and Bilibili (音乐排行) — no authentication required
- **Stage 2 — Curated Playlists**: Browse popular public playlists by category (华语, 电子, 古风, etc.) from NetEase and QQ Music — no authentication required
- **Stage 3 — Personal Recommendations**: Fetch personalized daily recommendations (每日推荐) from NetEase and QQ Music after WebView-based login — authentication required
- "Preview then add" workflow: tap a song to deep-link into the native app for preview, then add to library or a specific playlist
- Option to sync a discovered playlist as a SyncSource (reusing existing sync infrastructure)

## Non-goals

- Streaming or downloading music within the app (the app remains a launcher)
- Bilibili personalized recommendations (Bilibili has no music-specific recommendation API)
- Search across platform catalogs (out of scope for this change)
- Social features (comments, sharing recommendations)

## Capabilities

### New Capabilities

- `platform-charts`: Fetch and display chart/ranking data from NetEase, QQ Music, and Bilibili. Includes chart list retrieval and chart song fetching.
- `platform-playlist-browse`: Browse public playlists by category from NetEase and QQ Music. Includes category listing and playlist discovery.
- `platform-auth`: WebView-based login flow for NetEase and QQ Music. Secure cookie storage and injection into authenticated API calls.
- `personal-recommendations`: Fetch personalized daily recommendations from NetEase and QQ Music using stored auth credentials.
- `discover-ui`: New Discover tab with tabbed sections (Charts, Browse, For You), song preview via deep link, and add-to-library/playlist actions.

### Modified Capabilities

(none — this feature is entirely additive)

## Impact

- **Navigation**: Bottom nav grows from 4 to 5 tabs (max supported by BottomNavigationView)
- **Platform handlers**: NetEasePlatform, QQMusicPlatform, BilibiliPlatform gain new API methods for charts, browse, and recommendations
- **Dependencies**: `androidx.security:security-crypto` added for EncryptedSharedPreferences (Stage 3 auth cookie storage)
- **Permissions**: No new Android permissions required (WebView login uses standard Android WebView)
- **Affected platforms**: NetEase Cloud Music (all 3 stages), QQ Music (all 3 stages), Bilibili (Stage 1 only — charts)

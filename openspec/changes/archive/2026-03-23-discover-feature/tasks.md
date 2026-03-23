## 1. Navigation & Foundation

- [ ] 1.1 Create `ic_discover.xml` drawable (compass/explore Material Design icon) in `res/drawable/`
- [ ] 1.2 Add string resources to `res/values/strings.xml`: `nav_discover_cn` (发现), chart names, section tab labels (排行榜, 歌单, 推荐), button labels (添加到曲库, 添加到歌单, 重试, 登录, 同步此歌单)
- [ ] 1.3 Add `nav_discover` menu item to `res/menu/bottom_navigation.xml` (5th tab with `ic_discover` icon)
- [ ] 1.4 Add `nav_discover` fragment destination to `res/navigation/nav_graph.xml` pointing to `DiscoverFragment`
- [ ] 1.5 Add `nav_chart_detail` fragment destination to nav graph with Safe Args: `chartPlatform` (String), `chartId` (String), `chartName` (String)
- [ ] 1.6 Create data classes in `platform/DiscoveryApi.kt`: `ChartInfo`, `DiscoverPlaylistInfo`, and the `DiscoveryApi` coordinator object with chart definitions (hardcoded NetEase chart IDs)
- [ ] 1.7 Build verification: `pixi run build` succeeds

## 2. Charts API (Stage 1 — Backend)

- [ ] 2.1 Add `fetchToplistAll()` method to `QQMusicPlatform.kt` — POST to `musicu.fcg` with `musicToplist.ToplistInfoServer.GetAll`, returns list of `ChartInfo`
- [ ] 2.2 Add `fetchToplistDetail(topId, num)` method to `QQMusicPlatform.kt` — POST to `musicu.fcg` with `musicToplist.ToplistInfoServer.GetDetail`, returns `ParsedPlaylist`
- [ ] 2.3 Add `fetchMusicRanking()` method to `BilibiliPlatform.kt` — GET `/x/web-interface/ranking/v2?rid=3&type=all`, returns `ParsedPlaylist` with `video:BV{id}` song IDs
- [ ] 2.4 Add chart fetch methods to `DiscoveryApi.kt`: `fetchChartList()` (returns all charts across platforms) and `fetchChartSongs(chartInfo)` (delegates to correct platform handler)
- [ ] 2.5 Build verification: `pixi run build` succeeds

## 3. Charts UI (Stage 1 — Frontend)

- [ ] 3.1 Create `item_chart_card.xml` layout: card with cover image, chart name, platform badge, update frequency
- [ ] 3.2 Create `ChartAdapter.kt` in `ui/adapter/`: `ListAdapter<ChartInfo, ViewHolder>` with `DiffUtil.ItemCallback`, `onChartClick` callback
- [ ] 3.3 Create `item_discover_song.xml` layout: song row with cover, title, artist, platform badge, play button, add (+) button
- [ ] 3.4 Create `DiscoverSongAdapter.kt` in `ui/adapter/`: `ListAdapter<ParsedSong, ViewHolder>` with `onPlayClick` and `onAddClick` callbacks
- [ ] 3.5 Create `DiscoverViewModel.kt` in `ui/viewmodel/`: StateFlows for chart list, chart songs, loading/error states; `loadCharts()` and `loadChartSongs(chartInfo)` methods
- [ ] 3.6 Create `fragment_discover.xml` layout: `TabLayout` + `ViewPager2` (start with Charts tab only)
- [ ] 3.7 Create `DiscoverFragment.kt` in `ui/fragment/`: ViewPager2 adapter with nested fragments for each section
- [ ] 3.8 Create `fragment_chart_detail.xml` layout: toolbar with chart name, RecyclerView for songs, loading/error states
- [ ] 3.9 Create `ChartDetailFragment.kt` in `ui/fragment/`: receives Safe Args, loads chart songs, displays with DiscoverSongAdapter
- [ ] 3.10 Wire "preview" action: play button launches deep link via `DeepLinkLauncher`
- [ ] 3.11 Wire "add" action: add button shows bottom sheet with "添加到曲库" and "添加到歌单" options; handles song insertion via repository and duplicate detection
- [ ] 3.12 Build and deploy: `pixi run deploy`, manually test Charts tab end-to-end

## 4. Playlist Browse API (Stage 2 — Backend)

- [ ] 4.1 Add `fetchCategoryPlaylists(category, limit, offset)` method to `NetEasePlatform.kt` — GET `/api/playlist/list?cat={category}&order=hot&limit={limit}&offset={offset}`, returns list of `DiscoverPlaylistInfo`
- [ ] 4.2 Add `fetchPlaylistSquare()` method to `QQMusicPlatform.kt` — POST to `musicu.fcg` with `playlist.PlaylistSquareServer.GetRecommendWhole`, returns list of `DiscoverPlaylistInfo`
- [ ] 4.3 Add browse methods to `DiscoveryApi.kt`: `fetchCategories()` (returns hardcoded NetEase categories), `fetchCategoryPlaylists(platform, category)`, `fetchPopularPlaylists(platform)`
- [ ] 4.4 Build verification: `pixi run build` succeeds

## 5. Playlist Browse UI (Stage 2 — Frontend)

- [ ] 5.1 Create `item_discover_playlist.xml` layout: card with cover image, playlist name, play count, creator
- [ ] 5.2 Create `DiscoverPlaylistAdapter.kt` in `ui/adapter/`: grid adapter for playlist cards with `onPlaylistClick` callback
- [ ] 5.3 Add browse state to `DiscoverViewModel.kt`: categories, playlists per category, loading/error states; `loadCategories()`, `loadCategoryPlaylists(category)` methods
- [ ] 5.4 Add Browse tab content as nested fragment within DiscoverFragment's ViewPager2: category chips + playlist grid
- [ ] 5.5 Add `nav_browse_playlist_detail` destination to nav graph with Safe Args: `platform` (String), `playlistId` (String), `playlistName` (String)
- [ ] 5.6 Create `fragment_browse_playlist_detail.xml` layout: playlist info header + RecyclerView for songs + "同步此歌单" toolbar action
- [ ] 5.7 Create `BrowsePlaylistDetailFragment.kt` in `ui/fragment/`: loads playlist songs via existing `fetchPlaylistSongs()`, displays with DiscoverSongAdapter, handles "sync this playlist" action
- [ ] 5.8 Wire "sync this playlist" action: creates `Playlist` + `SyncSource` entities, triggers immediate sync via `PlaylistSyncEngine`
- [ ] 5.9 Build and deploy: `pixi run deploy`, manually test Browse tab end-to-end

## 6. Auth Infrastructure (Stage 3 — Backend)

- [ ] 6.1 Add `androidx.security:security-crypto` dependency to `app/build.gradle.kts`
- [ ] 6.2 Create `auth/CookieStore.kt`: EncryptedSharedPreferences wrapper with `saveCookies(platform, cookies)`, `getCookies(platform)`, `clearCookies(platform)`, `hasCookies(platform)` methods
- [ ] 6.3 Create `auth/PlatformAuthManager.kt`: singleton with `isLoggedIn(platform)`, `getCookies(platform)`, `logout(platform)`, `injectAuth(requestBuilder, platform)` methods; platform-specific login URL and success cookie definitions
- [ ] 6.4 Create `res/layout/activity_platform_login.xml`: toolbar + WebView layout
- [ ] 6.5 Create `auth/PlatformLoginActivity.kt`: WebView with JavaScript enabled, monitors CookieManager for auth cookies after page loads, extracts and stores cookies on success, returns RESULT_OK/RESULT_CANCELED
- [ ] 6.6 Register `PlatformLoginActivity` in `AndroidManifest.xml`
- [ ] 6.7 Build verification: `pixi run build` succeeds

## 7. Recommendations API (Stage 3 — Backend)

- [ ] 7.1 Add `fetchDailyRecommendations(cookies)` method to `NetEasePlatform.kt` — POST to `/api/v3/discovery/recommend/songs` with MUSIC_U cookie, returns list of `ParsedSong`
- [ ] 7.2 Add `fetchRecommendations(cookies)` method to `QQMusicPlatform.kt` — POST to `musicu.fcg` with `rcmusic.RecommendSongServer.get_rcmd_song_list` and auth cookies, returns list of `ParsedSong`
- [ ] 7.3 Add recommendation methods to `DiscoveryApi.kt`: `fetchRecommendations(platform)` that checks auth, injects cookies, delegates to platform handler; handles 401/403 by clearing auth and returning auth-expired state
- [ ] 7.4 Build verification: `pixi run build` succeeds

## 8. Recommendations UI (Stage 3 — Frontend)

- [ ] 8.1 Add recommendation state to `DiscoverViewModel.kt`: per-platform login state, recommendation songs, loading/error/auth-expired states; `loadRecommendations(platform)`, `onLoginResult(platform, success)` methods
- [ ] 8.2 Add "For You" tab content as nested fragment within DiscoverFragment's ViewPager2: per-platform sections with login card (when not logged in) or recommendation song list (when logged in)
- [ ] 8.3 Wire login button: launches `PlatformLoginActivity` via `ActivityResultLauncher`, handles result to update login state and fetch recommendations
- [ ] 8.4 Wire logout action: clears cookies via `PlatformAuthManager.logout()`, updates UI to show login card
- [ ] 8.5 Handle auth-expired state: show "会话已过期，请重新登录" message with login button
- [ ] 8.6 Build and deploy: `pixi run deploy`, manually test For You tab end-to-end (login, view recommendations, preview, add)

## 9. Final Verification

- [ ] 9.1 Full build: `pixi run build` succeeds with no errors
- [ ] 9.2 Deploy and test all three sections: Charts → Browse → For You
- [ ] 9.3 Test "preview then add" flow: deep link launches, add to library works, add to playlist works, duplicate detection works
- [ ] 9.4 Test "sync this playlist" from Browse tab: local playlist created, sync completes, playlist appears in Playlists tab
- [ ] 9.5 Test in controller mode: Discover tab works locally, deep links launch on controller device

## 1. Research — Read source code for new features

- [x] 1.1 Read discover/browse feature files: `DiscoverFragment.kt`, `DiscoverViewModel.kt`, `DiscoveryApi.kt`, `BrowsePlaylistDetailFragment.kt`, `ChartDetailFragment.kt`
- [x] 1.2 Read platform auth files: `auth/PlatformAuthManager.kt`, `auth/PlatformLoginActivity.kt`, `auth/CookieStore.kt`
- [x] 1.3 Read playlist sync files: `sync/PlaylistSyncEngine.kt`, `sync/PlaylistSyncWorker.kt`, `sync/SyncScheduler.kt`, `ManageSourcesFragment.kt`
- [x] 1.4 Read updated service files for smart timeout, QQ dialog dismissal, and mini ball navigation: `PlaybackService.kt`, `PlayerAccessibilityService.kt`, `FloatingWindowService.kt`

## 2. Update patent disclosure (`docs/专利软著/1.专利技术交底书.md`)

- [x] 2.1 Update system architecture table (图1) to include new modules: auth layer, sync engine, discover/browse API, import from library
- [x] 2.2 Update service layer description to include PlayerAccessibilityService QQ dialog dismissal and smart playback timeout
- [x] 2.3 Add new workflow: discover/browse flow (platform playlist browsing, chart browsing, personal recommendations with authenticated API)
- [x] 2.4 Add new workflow: playlist sync from remote source (periodic sync via WorkManager)
- [x] 2.5 Add 关键点7: Platform authentication for personalized content (cookie-based WebView login, authenticated API calls)
- [x] 2.6 Add 关键点8: Periodic playlist sync engine (SyncSource model, WorkManager-based background sync)
- [x] 2.7 Add 关键点9: Smart playback timeout with app readiness detection (timeout adapts to app launch state)
- [x] 2.8 Add 关键点10: Accessibility-based QQ Music error dialog auto-dismissal
- [x] 2.9 Update 附图说明 to reference new architecture components

## 3. Update copyright registration (`docs/专利软著/2.软件著作权采集表.md`)

- [x] 3.1 Update source code line count from ~8900 to ~14375
- [x] 3.2 Update main features list to include: discover/browse, platform login, playlist sync, Bilibili medialist, smart timeout, mini ball double-click
- [x] 3.3 Update technical characteristics summary to mention new modules (auth, sync, discover)

## 4. Update user manual (`docs/专利软著/5.用户手册.md`)

- [x] 4.1 Update table of contents to include new sections
- [x] 4.2 Add section: Discover/Browse feature (browsing platform playlists, charts, personal recommendations)
- [x] 4.3 Add section: Platform login (how to log in to NetEase/QQ Music for personalized content)
- [x] 4.4 Add section: Playlist sync (setting up sync sources, managing sync, auto-sync behavior)
- [x] 4.5 Update section 5 (曲库管理): Add Bilibili to platform filters, mention fast scrollbar
- [x] 4.6 Update section 8.3 (迷你模式): Add double-click to navigate to next song
- [x] 4.7 Add section: Import from library (selecting songs from library to add to playlist)
- [x] 4.8 Update FAQ section with new common questions (discover, sync, login)

## 5. Verification

- [x] 5.1 Review all three updated docs for consistency and accuracy against source code

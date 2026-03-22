## 1. Data Model & Database Changes

- [x] 1.1 Add `SyncSource` entity to `data/model/Models.kt` with fields: id, playlistId, platform, remotePlaylistId, sourceUrl, lastSyncAt, lastSyncStatus, lastSyncError, createdAt. Add unique index on (playlist_id, platform, remote_playlist_id).
- [x] 1.2 Add `sync_source_id` nullable Long column to `PlaylistItem` entity in `data/model/Models.kt` to track which sync source added each item.
- [x] 1.3 Add `sync_interval_minutes` Long column (default 360) to `Playlist` entity in `data/model/Models.kt`.
- [x] 1.4 Create `SyncSourceDao` in `data/local/` with CRUD operations: insert, delete, getByPlaylistId (Flow), getByPlaylistIdList (suspend), getAllSyncedPlaylistIds, updateSyncStatus.
- [x] 1.5 Register `SyncSource` entity in `MusicHubDatabase`, add `syncSourceDao()` accessor, bump database version to 2.
- [x] 1.6 Update `PlaylistItemDao` to handle `sync_source_id`: update `addSongToPlaylist` and `addSongsToPlaylist` to accept optional syncSourceId parameter, add query to get items by sync_source_id.
- [x] 1.7 Add WorkManager dependency to `app/build.gradle.kts`: `implementation("androidx.work:work-runtime-ktx:2.9.0")`

## 2. Repository Layer

- [x] 2.1 Add `SyncSourceDao` to `MusicRepository` constructor and expose sync source operations: getSyncSourcesForPlaylist, addSyncSource, removeSyncSource, updateSyncStatus.
- [x] 2.2 Add repository method `getPlaylistItemsBySyncSource(syncSourceId)` to query playlist items added by a specific sync source.
- [x] 2.3 Add repository method `removeSyncSourceAndItems(syncSourceId)` that deletes the sync source record and all playlist items with that sync_source_id in a transaction.

## 3. Sync Engine

- [x] 3.1 Create `sync/PlaylistSyncEngine.kt` with the core sync algorithm: for a given playlist, iterate each sync source, call `PlatformHandler.fetchPlaylistSongs()`, diff against local songs, add new songs (with sync_source_id), remove stale synced songs, update sync status per source.
- [x] 3.2 Implement deduplication logic in sync engine: check `SongDao.getByPlatformId()` before inserting songs, reuse existing song IDs for playlist item creation.
- [x] 3.3 Implement error handling per source: catch exceptions from `fetchPlaylistSongs()`, update source status to "error" with message, skip removal for failed sources.

## 4. WorkManager Integration

- [x] 4.1 Create `sync/PlaylistSyncWorker.kt` extending `CoroutineWorker`, inject dependencies via application context, call `PlaylistSyncEngine` for all synced playlists.
- [x] 4.2 Create `sync/SyncScheduler.kt` utility to enqueue/cancel periodic WorkManager jobs. Support per-playlist scheduling with configurable interval and network constraint.
- [x] 4.3 Initialize sync scheduling in `MusicHubApplication.kt` on app startup — enqueue work for all playlists that have sync sources.

## 5. UI — Sync Status on Playlist Detail

- [x] 5.1 Update `fragment_playlist_detail.xml` to add a sync status section below playlist description: sync icon, last sync time text, number of sources text. Initially hidden (GONE) for non-synced playlists.
- [x] 5.2 Create `menu/menu_playlist_detail.xml` with "Sync Now" and "Manage Sources" menu items (shown only for synced playlists).
- [x] 5.3 Update `PlaylistDetailViewModel` to expose sync sources as a Flow, compute sync status (last sync time, source count), and provide a `syncNow()` method.
- [x] 5.4 Update `PlaylistDetailFragment` to observe sync sources, show/hide sync status section, handle toolbar menu actions for sync now and manage sources.

## 6. UI — Manage Sources Screen

- [x] 6.1 Create `fragment_manage_sources.xml` layout with a RecyclerView for source list and a FAB/button to add a new source.
- [x] 6.2 Create `item_sync_source.xml` layout for each source row: platform icon, source URL, last sync time, status badge, delete button.
- [x] 6.3 Create `SyncSourceAdapter` in `ui/adapter/` for the RecyclerView.
- [x] 6.4 Create `ManageSourcesFragment` in `ui/fragment/` to display and manage sync sources for a playlist. Include "Add Source" dialog that accepts a URL, parses it via `LinkParser`, and creates a `SyncSource`.
- [x] 6.5 Create `ManageSourcesViewModel` in `ui/viewmodel/` to expose sync sources and handle add/remove operations.
- [x] 6.6 Add navigation entries in `nav_graph.xml` for ManageSourcesFragment with playlistId argument.

## 7. UI — Import Flow Integration

- [x] 7.1 Update `AddSongFragment` layout to add a "Keep synced with source" checkbox (visible only when importing a playlist, not a single song).
- [x] 7.2 Update `AddSongViewModel` to accept the sync flag during playlist import, create a `SyncSource` record when enabled, and set `sync_source_id` on imported playlist items.

## 8. Strings & Resources

- [x] 8.1 Add Chinese strings to `strings.xml`: sync status labels ("上次同步", "从未同步", "同步中...", "同步失败"), menu items ("立即同步", "管理同步源"), source management labels, "Keep synced" checkbox label ("保持与源歌单同步"), result messages ("添加了 %d 首，移除了 %d 首").

## 9. Build & Test

- [x] 9.1 Verify the project builds successfully with `pixi run build`.
- [ ] 9.2 Deploy to device and manually test: import a playlist with sync enabled, verify SyncSource is created, trigger manual sync, verify new songs are added and removed songs are cleaned up.
- [ ] 9.3 Test manage sources UI: add a second source to a synced playlist, remove a source and verify its songs are cleaned up.
- [ ] 9.4 Test that non-synced playlists show no sync UI elements.

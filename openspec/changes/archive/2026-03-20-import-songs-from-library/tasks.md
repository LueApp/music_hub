## 1. Database & Repository Layer

- [x] 1.1 Add `getSongsNotInPlaylist(playlistId)` query to `SongDao` (`data/local/SongDao.kt`) — returns `Flow<List<Song>>` of all songs whose ID is not in the target playlist's `playlist_items`
- [x] 1.2 Add `searchSongsNotInPlaylist(query, playlistId)` query to `SongDao` — LIKE search on title/artist, excluding songs already in the playlist
- [x] 1.3 Add `getSongsByPlatformNotInPlaylist(platform, playlistId)` query to `SongDao` — filter by platform, excluding songs already in the playlist
- [x] 1.4 Add `searchSongsByPlatformNotInPlaylist(query, platform, playlistId)` query to `SongDao` — combined search + platform filter, excluding songs already in the playlist
- [x] 1.5 Add `addSongsToPlaylist(playlistId, songIds)` batch insert `@Transaction` method to `PlaylistItemDao` (`data/local/PlaylistItemDao.kt`) — inserts multiple songs with sequential positions after the current max
- [x] 1.6 Expose the new DAO methods through `MusicRepository` (`data/repository/MusicRepository.kt`)

## 2. UI Layout & Resources

- [x] 2.1 Create `fragment_import_from_library.xml` layout (`res/layout/`) with search bar, platform filter chips (All/NetEase/QQ Music/Bilibili), RecyclerView, select-all/deselect-all action, selection counter, confirm button, and empty state view
- [x] 2.2 Create `item_song_selectable.xml` layout (`res/layout/`) — song item with checkbox, cover art, title, artist, and platform badge
- [x] 2.3 Add Chinese string resources to `strings.xml` (`res/values/strings.xml`) — import button label, screen title, empty state messages, selection counter text, success toast, menu item text
- [x] 2.4 Add toolbar menu XML (`res/menu/menu_playlist_detail.xml`) with "从曲库导入" (Import from Library) menu item

## 3. Adapter

- [x] 3.1 Create `SelectableSongAdapter` (`ui/adapter/SelectableSongAdapter.kt`) — a `ListAdapter<Song>` with checkbox multi-select support, exposing selected song IDs via a callback or public method

## 4. ViewModel

- [x] 4.1 Create `ImportFromLibraryViewModel` (`ui/viewmodel/ImportFromLibraryViewModel.kt`) with: playlist ID parameter, search query StateFlow, platform filter StateFlow, available songs StateFlow (combining search/filter/exclusion queries), selected song IDs Set, select/deselect/selectAll/deselectAll methods, and `importSelected()` method that calls the batch insert

## 5. Fragment & Navigation

- [x] 5.1 Create `ImportFromLibraryFragment` (`ui/fragment/ImportFromLibraryFragment.kt`) — wires up the layout, adapter, ViewModel, search bar, filter chips, select-all toggle, confirm button, and controller mode branching
- [x] 5.2 Add `nav_import_from_library` destination to `nav_graph.xml` with `playlistId` argument, and add `action_detail_to_import_from_library` action from `nav_playlist_detail`
- [x] 5.3 Update `PlaylistDetailFragment` to inflate the toolbar menu and handle the "Import from Library" menu item tap — navigate to the new import fragment with the current playlist ID

## 6. Remote Control Support

- [x] 6.1 Add `addSongsToPlaylist(playlistId, songIds)` endpoint to `RemoteServer` (`remote/RemoteServer.kt`) — accepts POST with JSON body containing song IDs, calls the repository batch insert
- [x] 6.2 Add `addSongsToPlaylist(playlistId, songIds)` method to `RemoteClient` (`remote/RemoteClient.kt`) — sends the HTTP request to the player device
- [x] 6.3 Implement controller mode path in `ImportFromLibraryFragment` — fetch remote songs/playlist, compute exclusion client-side, and call `RemoteClient.addSongsToPlaylist()` on confirm

## 7. Build & Verification

- [x] 7.1 Run `pixi run build` to verify compilation succeeds with all new files
- [x] 7.2 Deploy to device with `pixi run deploy` and manually test: open a playlist, tap "Import from Library", search/filter songs, multi-select, confirm import, verify songs appear in the playlist

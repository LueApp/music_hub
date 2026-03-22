## 1. Data Layer — New DAO Queries

- [x] 1.1 Add `searchSongsInPlaylist(query, playlistId)` query to `PlaylistItemDao` — JOIN `songs` with `playlist_items`, filter by title/artist LIKE, ordered by position ASC
- [x] 1.2 Add `getSongsInPlaylistByPlatform(playlistId, platform)` query to `PlaylistItemDao` — filter by platform, ordered by position ASC
- [x] 1.3 Add `searchSongsInPlaylistByPlatform(query, playlistId, platform)` query to `PlaylistItemDao` — filter by both search query and platform, ordered by position ASC
- [x] 1.4 Add pass-through methods in `MusicRepository` for the 3 new queries

## 2. ViewModel — Search and Filter State

- [x] 2.1 Add `_searchQuery: MutableStateFlow<String>` and `_platformFilter: MutableStateFlow<String?>` to `PlaylistDetailViewModel`
- [x] 2.2 Replace the current `songs` StateFlow with `combine(_searchQuery, _platformFilter).flatMapLatest { ... }` that switches between the 4 query variants (all, search, platform, search+platform)
- [x] 2.3 Add `setSearchQuery(query)` and `setPlatformFilter(platform)` public methods

## 3. Layout — Search Bar, Chips, and Fast Scrollbar

- [x] 3.1 Add search `TextInputLayout` with `TextInputEditText` to `fragment_playlist_detail.xml`, placed between the action buttons and the divider (same style as `fragment_library.xml`)
- [x] 3.2 Add `HorizontalScrollView` with `ChipGroup` containing 4 filter chips (全部, 网易云, QQ音乐, B站) below the search bar
- [x] 3.3 Add `android:scrollbars="vertical"` and `android:fastScrollEnabled="true"` attributes to the `rvSongs` RecyclerView

## 4. Fragment — Wire Search, Filter, and Controller Mode

- [x] 4.1 Add `setupSearchAndFilter()` in `PlaylistDetailFragment` — wire `doAfterTextChanged` on search input to `viewModel.setSearchQuery()`, and `setOnCheckedStateChangeListener` on chip group to `viewModel.setPlatformFilter()`
- [x] 4.2 Update `observeData()` to show filtered song count from the `songs` StateFlow (already does this — verify it works with the new combined Flow)
- [x] 4.3 Add controller mode in-memory filtering: store full remote song list, apply search + platform filter locally on text/chip change (same pattern as `ImportFromLibraryFragment.filterRemoteSongs()`)

## 5. Build and Verify

- [x] 5.1 Run `pixi run build` and fix any compilation errors
- [x] 5.2 Deploy to device with `pixi run deploy` and manually test: fast scrollbar drag, search by title/artist, platform chip filter, combined search+filter, clearing search, controller mode search/filter

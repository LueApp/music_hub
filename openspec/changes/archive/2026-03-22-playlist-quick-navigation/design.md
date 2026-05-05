## Context

The playlist detail screen (`PlaylistDetailFragment`) shows songs in a bare `RecyclerView` with no fast scrollbar, no search bar, and no platform filter. Browsing a playlist with hundreds of songs requires tediously swiping through the entire list. Meanwhile, the Library screen (`LibraryFragment`) already has a search bar + platform filter chips using `combine().flatMapLatest()` on `MutableStateFlow` fields — a proven pattern in this codebase.

The `PlaylistItemDao` has `getSongsForPlaylist()` (returns all songs ordered by position) but no search/filter variants for songs within a playlist. The `SongDao` has `searchSongs()` for the global library but not scoped to a playlist. New DAO queries are needed.

**Standalone/Player mode**: Songs come from Room Flow queries. Search/filter will use new DAO queries that JOIN `playlist_items` with `songs`.

**Controller mode**: Songs are fetched once via `RemoteClient.fetchPlaylistSongs()` into memory. Search/filter will use in-memory filtering on the fetched list (same pattern as `ImportFromLibraryFragment`).

## Goals / Non-Goals

**Goals:**
- Add a draggable fast scrollbar to the playlist detail `RecyclerView`
- Add a search bar that filters playlist songs by title or artist in real time
- Add platform filter chips (All / NetEase / QQ Music / Bilibili)
- Work in both local mode (Room queries) and controller mode (in-memory filter)

**Non-Goals:**
- Song reordering, drag-and-drop
- Sorting by different fields
- Global search across playlists
- Changes to the floating window queue

## Decisions

### 1. Fast scrollbar approach

**Decision**: Use RecyclerView's built-in `android:scrollbars="vertical"` with `android:scrollbarStyle="outsideOverlay"` and `android:fastScrollEnabled="true"` XML attributes. This provides a draggable scrollbar thumb without any third-party library.

**Rationale**: Zero dependencies, minimal code. Android's built-in fast scroll with `LinearLayoutManager` shows a draggable thumb when the user touches the scrollbar track. Good enough for jumping to approximate positions in the list.

**Alternative considered**: Third-party fast scroller library (e.g., `me.zhanghai.android.fastscroll`) with letter/section popups. Rejected — adds a dependency for marginal benefit. Songs don't have natural alphabetical sections, so a section popup would not be useful.

### 2. Search/filter architecture

**Decision**: Follow the exact `LibraryViewModel` pattern:
- Add `_searchQuery: MutableStateFlow<String>` and `_platformFilter: MutableStateFlow<String?>` to `PlaylistDetailViewModel`
- Replace the current `songs` StateFlow with a `combine().flatMapLatest()` that switches between four new DAO queries depending on filter state
- Add new queries to `PlaylistItemDao`: `searchSongsInPlaylist()`, `getSongsInPlaylistByPlatform()`, `searchSongsInPlaylistByPlatform()`

**Rationale**: Reuses the proven pattern from `LibraryViewModel`. Room Flow queries are reactive, so results update automatically when songs are added/removed.

**Alternative considered**: In-memory filtering (filter the `songs` StateFlow list in the ViewModel). Rejected for local mode because it would lose reactivity — if the underlying playlist changes, the filter wouldn't re-trigger. Room Flow queries handle this automatically.

### 3. Controller mode filtering

**Decision**: Use in-memory filtering on the fetched remote song list, same as `ImportFromLibraryFragment.filterRemoteSongs()`. The fragment stores the full list and applies search + platform filter locally.

**Rationale**: Remote songs are fetched once via HTTP. There's no remote search API, and adding one is out of scope.

### 4. Layout placement

**Decision**: Add the search bar and chip group **between the action buttons (Play All / Shuffle) and the divider**, above the song list. This keeps the playlist header visible and puts the filter controls right above the content they filter.

**Rationale**: Matches the layout hierarchy of `fragment_library.xml` where search is at the top of the content area. Placing it after the action buttons but before the list gives a natural visual flow: header → actions → filter → songs.

### 5. Components affected

| Component | Change |
|-----------|--------|
| `PlaylistItemDao` | Add 3 new query methods: `searchSongsInPlaylist`, `getSongsInPlaylistByPlatform`, `searchSongsInPlaylistByPlatform` |
| `MusicRepository` | Add pass-through methods for the 3 new queries |
| `PlaylistDetailViewModel` | Add `_searchQuery`, `_platformFilter` MutableStateFlows; rebuild `songs` with `combine().flatMapLatest()` |
| `fragment_playlist_detail.xml` | Add search `TextInputLayout`, `ChipGroup` with 4 chips, `scrollbars` + `fastScrollEnabled` on RecyclerView |
| `PlaylistDetailFragment` | Add `setupSearchAndFilter()` wiring; controller mode in-memory filtering |
| `strings.xml` | Reuse existing search hint string (`search_songs_cn`) |

No new dependencies, no database migration (only new queries on existing tables), no new permissions.

## Risks / Trade-offs

- **[Risk] Fast scroll built-in thumb may look basic on some OEM Android skins** → Acceptable. It provides the core functionality (drag to jump) without adding dependencies. Can be upgraded to a custom thumb later if needed.

- **[Risk] Search during active playback: if the user filters the list, the "Play All" button would play only the filtered subset** → This matches `LibraryFragment` behavior where "Play All" plays the visible (filtered) list. Consistent behavior across the app.

- **[Risk] Controller mode: filtering happens on the full in-memory list, which may include stale data** → Same limitation as `ImportFromLibraryFragment`. Acceptable — user can pull-to-refresh or re-enter the screen.

## Open Questions

None.

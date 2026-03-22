## Why

Browsing long playlists is tedious — the only way to reach songs deep in the list is to slowly scroll through the entire thing. There is no fast scrollbar, no search, and no filtering. The Library screen already has search + platform filter chips, but the playlist detail screen has none of this.

## What Changes

- Add a **fast scrollbar** (draggable scroll thumb) to the playlist detail `RecyclerView`, allowing users to quickly jump to any position in the list by dragging the thumb
- Add a **search/filter bar** to the playlist detail screen — a text input that filters the song list by title or artist name in real time, reusing the same pattern already used in `LibraryFragment`
- Add **platform filter chips** (All / NetEase / QQ Music / Bilibili) below the search bar, matching the existing `LibraryFragment` chip group

## Non-goals

- Reordering songs (drag-and-drop, move to position, etc.)
- Sorting by field (alphabetical, by platform, by date added)
- Searching across playlists (global search)
- Changes to the floating window queue view

## Capabilities

### New Capabilities
- `playlist-quick-nav`: Fast scrollbar and search/filter for browsing playlist detail song lists

### Modified Capabilities
<!-- No existing spec-level behavior changes. -->

## Impact

- **UI Layer**: `PlaylistDetailFragment`, `fragment_playlist_detail.xml` — add search bar, chip group, and fast scrollbar configuration
- **ViewModel**: `PlaylistDetailViewModel` — add search query and platform filter state with `combine().flatMapLatest()` pattern (same as `LibraryViewModel`)
- **DAO**: May need a new query for searching songs within a specific playlist (filtered JOIN on `playlist_items`)
- **No new permissions** required
- **No new dependencies** — RecyclerView's built-in fast scroll or a simple custom thumb; search/filter uses existing Room + Flow patterns
- **All platforms** (NetEase, QQ Music, Bilibili) are affected only in that they appear as filter chip options
- **Controller mode**: Search/filter should work with the in-memory song list fetched via `RemoteClient` (same pattern as `ImportFromLibraryFragment`)

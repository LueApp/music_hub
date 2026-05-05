## Context

Songs in Music Hub live in a global `songs` table and are associated with playlists via a `playlist_items` junction table. Currently, the only way to add a song to a playlist is by navigating to the AddSongFragment (from PlaylistDetail's "+" FAB), pasting a URL, and parsing it. There is no way to browse the existing song library and add already-saved songs to a playlist.

The Library tab already has search and platform filtering infrastructure (`LibraryViewModel` uses `searchSongs()`, `getSongsByPlatform()`, `getAllSongs()`). The PlaylistItemDao already has `addSongToPlaylist()` for appending a single song. The pieces exist — they just need to be connected in a new UI flow.

## Goals / Non-Goals

**Goals:**
- Allow users to browse their existing song library and multi-select songs to add to a specific playlist
- Reuse existing query infrastructure (search, platform filter) for the import screen
- Exclude songs already in the target playlist from selection to prevent duplicates
- Support batch insertion of multiple songs in one operation
- Work in both standalone and controller modes

**Non-Goals:**
- Modifying the existing AddSongFragment or URL-based song addition flow
- Cross-playlist copy/move operations
- Drag-and-drop reordering during import
- Adding new platform handlers or permissions

## Decisions

### 1. New Fragment vs. Dialog

**Decision**: New `ImportFromLibraryFragment` as a full navigation destination.

**Alternatives considered**:
- **Bottom sheet dialog**: Simpler, but the library can have many songs and needs search + filter — a full-screen fragment is more usable.
- **Reuse LibraryFragment with selection mode**: Would tightly couple the library browsing experience with playlist import logic. Separate fragment keeps concerns clean.

**Rationale**: A full fragment gives enough space for a search bar, filter chips, song list with checkboxes, and a "confirm" action bar. It follows the same navigation pattern as AddSongFragment.

### 2. Song Exclusion Strategy

**Decision**: Query songs NOT already in the target playlist at the DAO level using a `NOT IN` subquery.

**Alternative considered**: Fetch all songs + playlist songs separately, filter in the ViewModel. This wastes memory and is harder to keep reactive.

**Rationale**: A single Room query like `SELECT * FROM songs WHERE id NOT IN (SELECT song_id FROM playlist_items WHERE playlist_id = :playlistId)` is efficient and keeps the exclusion logic in SQL. The result is reactive via Flow so it auto-updates if the playlist changes.

### 3. Batch Insert

**Decision**: Add a `@Transaction` method to `PlaylistItemDao` that accepts a list of song IDs and inserts them sequentially with incrementing positions.

**Alternative considered**: Insert one by one from the ViewModel in a loop. This works but isn't atomic — a failure midway could leave partial state.

**Rationale**: A `@Transaction` method ensures all-or-nothing insertion and is the Room-idiomatic approach.

### 4. Navigation Entry Point

**Decision**: Add a second button/action in `PlaylistDetailFragment` (alongside the existing "+" FAB for AddSong) that navigates to the new import screen. Reuse the existing FAB by converting it to a "speed dial" pattern (two options on tap), or add a menu/toolbar action.

**Refined approach**: Add a new navigation action `action_detail_to_import_from_library` from PlaylistDetail to the new fragment. Add an "Import from library" option — either as a second FAB, a menu item in the toolbar, or by making the existing FAB show two choices. The simplest approach: add a toolbar menu item labeled "从曲库导入" (Import from Library).

**Rationale**: The existing "+" FAB clearly means "add new song via URL." A toolbar menu item for "import from library" avoids cluttering the FAB area and is discoverable.

### 5. Controller Mode Support

**Decision**: In controller mode, the import screen fetches the remote library via `RemoteClient.fetchAllSongs()` and existing playlist songs via `RemoteClient.fetchPlaylistSongs()`, then performs the import via a new remote API endpoint `RemoteClient.addSongsToPlaylist(playlistId, songIds)`.

**Rationale**: Follows the existing pattern where every fragment branches on `RemoteMode.isController()` for remote vs. local behavior.

## Risks / Trade-offs

- **Large library performance**: If the song library is very large (1000+ songs), the `NOT IN` subquery could be slow. Mitigation: Room's SQLite is fast for this scale; can add an index on `playlist_items.song_id` if needed (already indexed via the unique constraint on `(playlist_id, song_id)`).
- **UI complexity of multi-select**: Checkbox-based multi-select in a RecyclerView requires tracking selected state in the ViewModel. Mitigation: Use a simple `Set<Long>` of selected song IDs in the ViewModel — straightforward pattern.
- **Controller mode API gap**: The remote server currently has no batch "add songs to playlist" endpoint. Mitigation: Add a simple POST endpoint to RemoteServer. Alternatively, loop single-add calls, but batch is cleaner.

## Why

Currently, songs can only be added to a playlist by pasting a URL (shared link) and parsing it via the AddSongFragment. If a user already has songs in their library that they want to organize into a playlist, they must re-find and re-share the original URL from the music platform app. This is tedious and defeats the purpose of having a centralized library. Users need a way to browse their existing song library and add songs directly to any playlist.

## What Changes

- Add a new "Import from Library" screen accessible from the playlist detail view, allowing users to browse, search, and multi-select songs from their existing library to add to a playlist
- Songs already in the target playlist are visually marked and excluded from selection to prevent duplicates
- Support search and platform filtering (reusing existing library query infrastructure) within the import flow
- Add batch insert capability to add multiple selected songs to a playlist in one operation

## Non-goals

- No changes to how songs are initially added to the library (URL parsing flow remains unchanged)
- No drag-and-drop reordering during import (existing reorder in PlaylistDetail handles this)
- No cross-playlist copy/move (out of scope; this is strictly library-to-playlist import)
- No changes to any platform handlers (NetEase, QQ Music, Bilibili)
- No new Android permissions required

## Capabilities

### New Capabilities
- `library-to-playlist-import`: Browse, search, filter, and multi-select songs from the existing song library to batch-add them into a target playlist

### Modified Capabilities
<!-- No existing specs to modify -->

## Impact

- **UI**: New fragment (`ImportFromLibraryFragment`) with its own layout, plus a new ViewModel
- **Navigation**: New nav graph destination reachable from `PlaylistDetailFragment`
- **Repository/DAO**: New query to get songs NOT already in a given playlist; batch insert method for `PlaylistItemDao`
- **No new dependencies**: Reuses existing Room, Coroutines, ViewBinding, Material 3 infrastructure
- **No platform handler changes**: All platforms unaffected
- **No permission changes**: No new Android permissions needed

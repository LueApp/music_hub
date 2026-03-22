## Why

Currently, when users import a playlist from QQ Music or NetEase Cloud Music via a shared link, the app takes a one-time snapshot of the songs. If the source playlist is later modified (songs added, removed, or reordered), the local copy becomes stale with no way to detect or pull in those changes. Users need a way to keep local playlists in sync with their evolving remote playlists.

## What Changes

- Introduce a **sync playlist** concept: a local playlist that is linked to **one or more** remote source playlist URLs and can be periodically refreshed to reflect upstream changes. A single sync playlist can aggregate songs from multiple source playlists across different platforms (e.g., one NetEase playlist + two QQ Music playlists feeding into one local playlist).
- Add a **sync mechanism** that fetches the current song list from each attached source, compares the combined result with the local copy, and applies additions/removals.
- Add **periodic background sync** using Android WorkManager to automatically refresh all synced playlists at a configurable interval.
- Add UI affordances in the playlist detail screen to:
  - Attach/detach source playlist URLs to a sync playlist
  - Manually trigger a sync refresh
  - View the last sync timestamp and sync status
  - Configure the sync interval
- During playlist import (share intent flow), offer an option to create a new sync playlist or attach the source to an existing sync playlist.

### Non-goals

- Syncing playlists **to** the source platform (write-back) — this is read-only sync
- Supporting Bilibili playlist sync (Bilibili does not currently support playlist import)
- Syncing playlist metadata (name, description, cover) — only song membership is synced
- Real-time push-based sync — this uses periodic polling

## Capabilities

### New Capabilities
- `playlist-sync`: Defines the sync playlist data model (including the many-to-many relationship between local playlists and source playlists), sync algorithm (fetch all sources → diff → apply additions/removals), periodic scheduling via WorkManager, manual sync trigger, and UI indicators for sync state.

### Modified Capabilities
<!-- No existing spec-level requirements change. The existing playlist import flow remains as-is for non-synced playlists. -->

## Impact

- **Database**: New `SyncSource` entity linking a playlist to one or more remote source URLs (with platform, remote playlist ID, last sync time). Sync-related fields on `Playlist` entity (sync enabled flag, sync interval). Requires a Room schema migration.
- **Platform handlers**: Reuses existing `fetchPlaylistSongs()` on `NetEasePlatform` and `QQMusicPlatform` — no changes needed to platform handlers.
- **New dependency**: Android WorkManager for periodic background sync scheduling.
- **Permissions**: No new permissions required (WorkManager does not need special permissions; network access is already declared).
- **Affected platforms**: NetEase Cloud Music and QQ Music (the two platforms that support playlist fetching).
- **UI**: Playlist detail fragment gains sync status indicators, a sync button, and a list of attached sources; playlist import flow gains an option to create or attach to a sync playlist.

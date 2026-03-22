## ADDED Requirements

### Requirement: Sync source data model
The system SHALL store sync sources as a separate Room entity (`sync_sources` table) with a many-to-one relationship to playlists. Each sync source record SHALL contain: playlist ID, platform identifier, remote playlist ID, source URL, last sync timestamp, last sync status, and last sync error message. The combination of (playlist_id, platform, remote_playlist_id) SHALL be unique.

#### Scenario: Attach a source to a playlist
- **WHEN** a user attaches a remote playlist URL to a local playlist
- **THEN** the system creates a `SyncSource` record linking the playlist to the remote source with status "never" and last sync timestamp 0

#### Scenario: Attach multiple sources to one playlist
- **WHEN** a user attaches a second remote playlist URL (from a different platform or different playlist ID) to the same local playlist
- **THEN** the system creates an additional `SyncSource` record, and the playlist now has two sync sources

#### Scenario: Prevent duplicate source attachment
- **WHEN** a user tries to attach a source with the same (playlist_id, platform, remote_playlist_id) as an existing record
- **THEN** the system SHALL reject the duplicate and inform the user that this source is already attached

### Requirement: Sync-origin tracking on playlist items
The system SHALL add an optional `sync_source_id` column to the `playlist_items` table (nullable Long). Items added by sync SHALL have this column set to the ID of the sync source that added them. Manually-added items SHALL have this column set to null.

#### Scenario: Song added by sync
- **WHEN** the sync process adds a new song to a playlist from a sync source
- **THEN** the resulting `PlaylistItem` record SHALL have `sync_source_id` set to the ID of the source that provided the song

#### Scenario: Song added manually
- **WHEN** a user manually adds a song to a synced playlist (via import from library or share intent)
- **THEN** the resulting `PlaylistItem` record SHALL have `sync_source_id` set to null

### Requirement: Sync algorithm fetches and diffs
The system SHALL implement a sync algorithm that, for a given playlist: (1) fetches the current song list from each attached sync source using the platform handler's `fetchPlaylistSongs()`, (2) identifies songs present in the remote but not in the local playlist (additions), (3) identifies songs with a non-null `sync_source_id` that are no longer present in any attached source (removals), and (4) applies additions and removals within a database transaction.

#### Scenario: New songs detected in remote source
- **WHEN** the remote playlist contains songs not present in the local playlist
- **THEN** the system SHALL insert the new songs into the `songs` table (deduplicating by platform + platformSongId), create `PlaylistItem` records appended at the end of the playlist, and set `sync_source_id` to the source that provided them

#### Scenario: Songs removed from remote source
- **WHEN** a song was previously added by sync (has a non-null `sync_source_id`) and is no longer present in any attached remote source for that playlist
- **THEN** the system SHALL remove the `PlaylistItem` record for that song from the playlist

#### Scenario: Manually-added songs preserved during sync
- **WHEN** a sync runs on a playlist that contains manually-added songs (sync_source_id is null)
- **THEN** those manually-added songs SHALL NOT be removed, regardless of whether they appear in any remote source

#### Scenario: Source fetch failure
- **WHEN** fetching songs from a sync source fails (network error, API error)
- **THEN** the system SHALL log the error, update the source's `last_sync_status` to "error" and `last_sync_error` with the message, and SHALL NOT remove any songs associated with that source (to avoid data loss from transient failures). Other sources for the same playlist SHALL still be processed.

#### Scenario: Sync updates source status on success
- **WHEN** a sync source is fetched and diffed successfully
- **THEN** the system SHALL update the source's `last_sync_at` to the current timestamp and `last_sync_status` to "success"

### Requirement: Periodic background sync via WorkManager
The system SHALL use AndroidX WorkManager to schedule periodic background sync for all playlists that have at least one sync source. The sync interval SHALL be configurable per playlist (stored as `sync_interval_minutes` on the Playlist entity, default 360 minutes / 6 hours). The WorkManager job SHALL require network connectivity as a constraint.

#### Scenario: Automatic periodic sync
- **WHEN** the sync interval elapses for a playlist with sync sources
- **THEN** WorkManager SHALL trigger the sync worker, which fetches and applies updates for all synced playlists

#### Scenario: Sync respects network constraint
- **WHEN** the device has no network connectivity
- **THEN** WorkManager SHALL defer the sync until connectivity is restored

#### Scenario: Sync survives app restart
- **WHEN** the app is killed or the device is restarted
- **THEN** WorkManager SHALL re-schedule the pending sync work automatically

### Requirement: Manual sync trigger
The system SHALL provide a way for users to manually trigger an immediate sync for a specific playlist from the playlist detail screen.

#### Scenario: User triggers manual sync
- **WHEN** a user taps the "Sync Now" action on a playlist detail screen
- **THEN** the system SHALL immediately run the sync algorithm for that playlist, showing a progress indicator during the operation and a result summary (e.g., "Added 3 songs, removed 1") upon completion

#### Scenario: Manual sync while no sources attached
- **WHEN** a user tries to trigger sync on a playlist with no sync sources
- **THEN** the system SHALL display a message indicating no sources are configured

### Requirement: Sync status display
The system SHALL display sync status information on the playlist detail screen for playlists that have sync sources. This SHALL include: last sync time (human-readable relative time), sync status per source, and the number of attached sources.

#### Scenario: Display last sync time
- **WHEN** a user views a synced playlist's detail screen
- **THEN** the system SHALL show the most recent sync time across all sources (e.g., "Last synced: 2 hours ago") and the number of attached sources

#### Scenario: Display never-synced state
- **WHEN** a user views a synced playlist that has never been synced
- **THEN** the system SHALL show "Not yet synced" as the status

#### Scenario: Hide sync info for non-synced playlists
- **WHEN** a user views a playlist with no sync sources
- **THEN** no sync status information SHALL be displayed

### Requirement: Manage sync sources UI
The system SHALL provide a UI to view, add, and remove sync sources for a playlist.

#### Scenario: View attached sources
- **WHEN** a user opens the "Manage Sources" screen for a synced playlist
- **THEN** the system SHALL display a list of all attached sync sources with their platform, source URL, last sync time, and status

#### Scenario: Add a new source via URL
- **WHEN** a user enters a playlist URL in the "Add Source" dialog
- **THEN** the system SHALL parse the URL using `LinkParser`, validate that it is a supported playlist URL (NetEase or QQ Music), extract the platform and remote playlist ID, and create a `SyncSource` record

#### Scenario: Remove a source
- **WHEN** a user removes a sync source from a playlist
- **THEN** the system SHALL delete the `SyncSource` record and remove all `PlaylistItem` records whose `sync_source_id` matches the removed source (cleaning up songs that were only present due to that source)

### Requirement: Import flow sync integration
When importing a playlist via share intent, the system SHALL offer the user an option to create a synced playlist. If the user opts in, a `SyncSource` record SHALL be created linking the new playlist to the remote source URL.

#### Scenario: Import with sync enabled
- **WHEN** a user imports a playlist via share intent and the "Keep synced" option is checked
- **THEN** the system SHALL create the playlist, import all songs (as today), and create a `SyncSource` record linking the playlist to the source URL. The imported `PlaylistItem` records SHALL have `sync_source_id` set to the new source's ID.

#### Scenario: Import without sync
- **WHEN** a user imports a playlist via share intent and the "Keep synced" option is unchecked
- **THEN** the system SHALL create the playlist and import songs exactly as it does today, with no `SyncSource` record created

### Requirement: Platform support scope
Playlist sync SHALL be supported for NetEase Cloud Music and QQ Music only. Bilibili playlists SHALL NOT be eligible for sync (Bilibili does not support playlist fetching).

#### Scenario: NetEase playlist sync
- **WHEN** a user attaches a NetEase Cloud Music playlist URL as a sync source
- **THEN** the system SHALL use `NetEasePlatform.fetchPlaylistSongs()` to fetch the remote song list during sync

#### Scenario: QQ Music playlist sync
- **WHEN** a user attaches a QQ Music playlist URL as a sync source
- **THEN** the system SHALL use `QQMusicPlatform.fetchPlaylistSongs()` to fetch the remote song list during sync

#### Scenario: Bilibili playlist URL rejected
- **WHEN** a user tries to attach a Bilibili URL as a sync source
- **THEN** the system SHALL reject it and display a message that Bilibili playlist sync is not supported

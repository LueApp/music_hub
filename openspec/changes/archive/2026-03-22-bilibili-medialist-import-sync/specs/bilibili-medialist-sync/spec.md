## ADDED Requirements

### Requirement: Bilibili medialist URL recognition
The system SHALL recognize Bilibili favorites/medialist URLs and extract the media_id for API calls. The following URL patterns SHALL be supported:
- `bilibili.com/medialist/detail/ml{media_id}` — primary share format
- `space.bilibili.com/{uid}/favlist?fid={media_id}` — user space format

The `parsePlaylistUrl()` method SHALL return a `ParsedPlaylist` with `platform="bilibili"` and `playlistId` set to the extracted media_id.

#### Scenario: Parse medialist share URL
- **WHEN** the user provides a URL like `https://www.bilibili.com/medialist/detail/ml3957996127`
- **THEN** the system SHALL extract media_id `3957996127` and return a `ParsedPlaylist` with `platform="bilibili"` and `playlistId="3957996127"`

#### Scenario: Parse user space favorites URL
- **WHEN** the user provides a URL like `https://space.bilibili.com/12345/favlist?fid=3957996127&ftype=create`
- **THEN** the system SHALL extract media_id `3957996127` and return a `ParsedPlaylist` with `platform="bilibili"` and `playlistId="3957996127"`

#### Scenario: Non-favorites Bilibili URL
- **WHEN** the user provides a Bilibili URL that is not a favorites/medialist URL (e.g., `https://www.bilibili.com/video/BV1xx411c7mD`)
- **THEN** `parsePlaylistUrl()` SHALL return `null`

### Requirement: Fetch Bilibili favorites folder contents
The system SHALL fetch all video items from a public Bilibili favorites folder using the API endpoint `https://api.bilibili.com/x/v3/fav/resource/list` with pagination.

Each video item SHALL be converted to a `ParsedSong` with:
- `platform` = `"bilibili"`
- `platformSongId` = `"video:{bv_id}"` (e.g., `"video:BV1CZ4y1T7gC"`)
- `title` from the media item's `title` field
- `artist` from the media item's `upper.name` field
- `coverUrl` from the media item's `cover` field
- `deepLink` and `fallbackUrl` generated via existing `BilibiliPlatform.generateDeepLink()` and `generateFallbackUrl()`

The `ParsedPlaylist` SHALL include `name`, `coverUrl`, and `songCount` from the favorites folder metadata (`data.info`).

#### Scenario: Fetch public favorites with multiple pages
- **WHEN** `fetchPlaylistSongs()` is called with a valid media_id for a public favorites folder containing 45 videos
- **THEN** the system SHALL paginate through the API (page 1 with 20 items, page 2 with 20 items, page 3 with 5 items) and return a `ParsedPlaylist` with 45 songs

#### Scenario: Fetch favorites with only video content
- **WHEN** a favorites folder contains a mix of videos (type=2) and other resource types (articles, audio collections)
- **THEN** the system SHALL only include items with `type == 2` in the returned song list and skip other types

#### Scenario: Skip invalidated/deleted items
- **WHEN** a favorites folder contains items with `attr == 9` (invalidated/deleted)
- **THEN** the system SHALL skip these items and not include them in the returned song list

#### Scenario: Handle items without BV ID
- **WHEN** a media item has an empty or null `bv_id` but has a numeric `id` (av number)
- **THEN** the system SHALL use `"video:av{id}"` as the platformSongId instead

### Requirement: Handle private favorites error
The system SHALL return a clear error when attempting to access a private favorites folder.

#### Scenario: Private favorites folder
- **WHEN** `fetchPlaylistSongs()` is called with a media_id for a private favorites folder
- **THEN** the API SHALL return `code: -403` and the system SHALL return `null` and log an appropriate error message

#### Scenario: Non-existent favorites folder
- **WHEN** `fetchPlaylistSongs()` is called with a media_id that does not exist
- **THEN** the system SHALL return `null` and log an appropriate error message

### Requirement: Bilibili medialist sync source support
The system SHALL allow Bilibili medialist URLs to be added as sync sources for playlists, enabling periodic background sync via the existing `PlaylistSyncEngine`.

The `ManageSourcesViewModel` SHALL NOT reject Bilibili URLs when they contain a valid medialist/favorites URL. Instead, it SHALL parse the URL via `BilibiliPlatform.parsePlaylistUrl()` and create a `SyncSource` record if a valid playlist is found.

#### Scenario: Add Bilibili medialist as sync source
- **WHEN** the user adds `https://www.bilibili.com/medialist/detail/ml3957996127` as a sync source for a playlist
- **THEN** the system SHALL create a `SyncSource` with `platform="bilibili"`, `remotePlaylistId="3957996127"`, and schedule periodic sync

#### Scenario: Periodic sync fetches updated Bilibili favorites
- **WHEN** the `PlaylistSyncEngine` runs a periodic sync for a playlist with a Bilibili sync source
- **THEN** the engine SHALL call `BilibiliPlatform.fetchPlaylistSongs()` with the stored remotePlaylistId, add newly discovered videos, and remove synced items no longer present in the favorites folder

#### Scenario: Bilibili non-playlist URL in manage sources
- **WHEN** the user tries to add a Bilibili video URL (not a medialist URL) as a sync source
- **THEN** the system SHALL reject the URL with an appropriate error message indicating it is not a playlist URL

### Requirement: Bilibili medialist import via AddSong flow
The system SHALL support importing Bilibili medialist URLs through the existing AddSong UI flow, including the option to keep the playlist synced.

#### Scenario: Import Bilibili medialist with sync enabled
- **WHEN** the user pastes a Bilibili medialist URL in the AddSong flow and enables "keep synced"
- **THEN** the system SHALL import all videos as songs into a new or existing playlist AND create a `SyncSource` for periodic sync

#### Scenario: Import Bilibili medialist without sync
- **WHEN** the user pastes a Bilibili medialist URL in the AddSong flow without enabling sync
- **THEN** the system SHALL import all videos as a one-time operation without creating a `SyncSource`

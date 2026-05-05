## ADDED Requirements

### Requirement: Browse playlists by category for NetEase

The system SHALL allow browsing public NetEase playlists by category. An initial set of categories SHALL be hardcoded: 华语, 欧美, 电子, 古风, 轻音乐, 说唱, 摇滚. For each category, the system SHALL fetch popular playlists via `GET /api/playlist/list?cat={category}&order=hot&limit=30`. Each playlist result SHALL include name, cover URL, play count, and playlist ID.

#### Scenario: Browse NetEase playlists by category
- **WHEN** the user selects the "华语" category on the Browse tab
- **THEN** the system fetches and displays up to 30 popular playlists with name, cover art, and play count

#### Scenario: Category selection changes results
- **WHEN** the user switches from "华语" to "电子"
- **THEN** the system fetches and displays playlists for the "电子" category

### Requirement: Browse playlists for QQ Music

The system SHALL fetch recommended/popular public playlists from QQ Music via the `musicu.fcg` endpoint using the playlist square module. Results SHALL include playlist name, cover URL, play count, and playlist ID.

#### Scenario: QQ Music popular playlists displayed
- **WHEN** the user views the Browse tab
- **THEN** QQ Music popular playlists are displayed alongside NetEase playlists

### Requirement: View songs in a browsed playlist

The system SHALL allow the user to view all songs in a selected public playlist. This SHALL reuse the existing `fetchPlaylistSongs()` method on the respective platform handler. Songs SHALL be displayed with the same "preview then add" actions as chart songs.

#### Scenario: View playlist songs
- **WHEN** the user taps on a browsed playlist
- **THEN** the system navigates to a detail view showing all songs in that playlist with preview and add actions

#### Scenario: Playlist song fetch fails
- **WHEN** fetching songs for a browsed playlist fails
- **THEN** the system displays an error state with retry button

### Requirement: Sync a browsed playlist

The system SHALL allow the user to create a local playlist synced to a discovered remote playlist. This SHALL create a `Playlist` entity and a `SyncSource` entity pointing to the remote playlist, then trigger an immediate sync via the existing `PlaylistSyncEngine`.

#### Scenario: Sync a discovered playlist
- **WHEN** the user taps "同步此歌单" on a browsed playlist detail view
- **THEN** a new local playlist is created with the remote playlist's name, a SyncSource is added, and an immediate sync is triggered

#### Scenario: Synced playlist appears in Playlists tab
- **WHEN** the user syncs a discovered playlist
- **THEN** the new playlist appears in the Playlists tab with the synced songs

### Requirement: No authentication required for playlist browsing

Playlist browsing SHALL NOT require any user authentication. All browse API calls SHALL use anonymous headers.

#### Scenario: Browse works without login
- **WHEN** the user has not logged into any platform
- **THEN** all playlist browsing features are fully accessible

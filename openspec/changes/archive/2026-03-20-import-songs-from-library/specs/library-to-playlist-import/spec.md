## ADDED Requirements

### Requirement: Import screen accessible from playlist detail
The system SHALL provide an "Import from Library" action in the PlaylistDetailFragment that navigates to a dedicated ImportFromLibraryFragment, passing the target playlist ID.

#### Scenario: User opens import screen from playlist detail
- **WHEN** user taps the "Import from Library" menu item in the playlist detail toolbar
- **THEN** the app navigates to the ImportFromLibraryFragment with the current playlist ID

#### Scenario: Import screen shows correct title
- **WHEN** the ImportFromLibraryFragment is displayed
- **THEN** the screen title SHALL indicate that the user is importing songs into the target playlist

### Requirement: Display available songs excluding those already in playlist
The system SHALL display all songs from the library that are NOT already in the target playlist. Songs already in the playlist SHALL be excluded from the list.

#### Scenario: Library has songs not in the playlist
- **WHEN** the user opens the import screen for a playlist that contains 3 of 10 library songs
- **THEN** the list SHALL display exactly 7 songs (those not already in the playlist)

#### Scenario: All library songs are already in the playlist
- **WHEN** the user opens the import screen for a playlist that contains all library songs
- **THEN** the list SHALL be empty and an empty state message SHALL be shown (e.g., "所有歌曲已在此歌单中")

#### Scenario: Library is empty
- **WHEN** the user opens the import screen and the song library is empty
- **THEN** the list SHALL be empty and an empty state message SHALL be shown (e.g., "曲库中没有歌曲")

### Requirement: Search songs in import screen
The system SHALL provide a search bar that filters the available songs by title or artist using a LIKE query, still excluding songs already in the target playlist.

#### Scenario: Search by song title
- **WHEN** the user types a search query matching a song title
- **THEN** only songs whose title or artist matches the query (and are not in the playlist) SHALL be displayed

#### Scenario: Search yields no results
- **WHEN** the user types a query that matches no available songs
- **THEN** the list SHALL be empty and an appropriate message SHALL be shown

### Requirement: Filter songs by platform in import screen
The system SHALL provide platform filter chips (All, NetEase, QQ Music, Bilibili) that filter available songs by platform, still excluding songs already in the target playlist.

#### Scenario: Filter by platform
- **WHEN** the user selects the "NetEase" filter chip
- **THEN** only NetEase songs not already in the playlist SHALL be displayed

#### Scenario: Combined search and filter
- **WHEN** the user has both a search query and a platform filter active
- **THEN** only songs matching both criteria (and not in the playlist) SHALL be displayed

### Requirement: Multi-select songs for import
The system SHALL allow users to select multiple songs via checkboxes. A selection counter SHALL show how many songs are currently selected.

#### Scenario: Select individual songs
- **WHEN** the user taps a song item or its checkbox
- **THEN** the song SHALL toggle between selected and unselected states, and the selection counter SHALL update

#### Scenario: Select all visible songs
- **WHEN** the user taps a "Select All" action
- **THEN** all currently visible (filtered) songs SHALL be selected

#### Scenario: Deselect all
- **WHEN** the user taps "Deselect All" or the selection counter when all are selected
- **THEN** all songs SHALL be deselected and the counter SHALL reset to 0

### Requirement: Confirm and batch import selected songs
The system SHALL provide a confirm button that adds all selected songs to the target playlist in a single batch operation. Songs SHALL be appended after the last existing song in the playlist.

#### Scenario: Confirm import of selected songs
- **WHEN** the user has selected 5 songs and taps the confirm/import button
- **THEN** all 5 songs SHALL be added to the playlist at positions after the current last position, the playlist's `updated_at` timestamp SHALL be updated, and the app SHALL navigate back to the playlist detail

#### Scenario: Confirm with no songs selected
- **WHEN** the user taps the confirm button with 0 songs selected
- **THEN** the confirm button SHALL be disabled or the action SHALL be ignored

#### Scenario: Success feedback
- **WHEN** the batch import completes successfully
- **THEN** a toast or snackbar SHALL display a success message indicating how many songs were added (e.g., "已添加 5 首歌曲")

### Requirement: Controller mode support
In controller mode, the import screen SHALL fetch the song library and playlist contents from the remote player device and perform the import via the remote API.

#### Scenario: Controller mode - load available songs
- **WHEN** the import screen opens in controller mode
- **THEN** it SHALL fetch all songs from `RemoteClient.fetchAllSongs()` and playlist songs from `RemoteClient.fetchPlaylistSongs()`, then display only songs not in the playlist

#### Scenario: Controller mode - confirm import
- **WHEN** the user confirms import in controller mode
- **THEN** the selected song IDs SHALL be sent to the remote player via an API call to add them to the playlist

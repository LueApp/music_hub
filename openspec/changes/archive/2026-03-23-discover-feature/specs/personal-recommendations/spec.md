## ADDED Requirements

### Requirement: Fetch NetEase daily recommendations

The system SHALL fetch personalized daily recommendations from NetEase Cloud Music via `POST /api/v3/discovery/recommend/songs` with the stored `MUSIC_U` auth cookie. The response SHALL be parsed into `ParsedSong` entries with title, artist, album, cover URL, platform song ID, and deep link.

#### Scenario: NetEase recommendations loaded
- **WHEN** the user is logged into NetEase and opens the For You tab
- **THEN** the system fetches and displays ~30 personalized daily recommended songs

#### Scenario: NetEase recommendations refreshed
- **WHEN** the user pulls to refresh on the For You tab
- **THEN** the system re-fetches NetEase daily recommendations (recommendations change daily)

#### Scenario: NetEase not logged in
- **WHEN** the user is not logged into NetEase
- **THEN** the For You section shows a login prompt card for NetEase instead of recommendations

### Requirement: Fetch QQ Music recommendations

The system SHALL fetch personalized recommendations from QQ Music via `POST musicu.fcg` with the `rcmusic.RecommendSongServer.get_rcmd_song_list` module and stored auth cookies. The response SHALL be parsed into `ParsedSong` entries.

#### Scenario: QQ Music recommendations loaded
- **WHEN** the user is logged into QQ Music and opens the For You tab
- **THEN** the system fetches and displays personalized recommended songs from QQ Music

#### Scenario: QQ Music not logged in
- **WHEN** the user is not logged into QQ Music
- **THEN** the For You section shows a login prompt card for QQ Music

### Requirement: Recommendation data is in-memory only

Recommendation results SHALL NOT be persisted to the database. They SHALL be held in the ViewModel's `StateFlow` and re-fetched when the tab is reopened or the user refreshes. Songs only enter Room when the user explicitly adds them.

#### Scenario: Recommendations not persisted
- **WHEN** the user navigates away from the Discover tab and returns
- **THEN** recommendations are re-fetched from the API (not loaded from a local cache)

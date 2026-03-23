## ADDED Requirements

### Requirement: Fetch chart list per platform

The system SHALL provide a list of available charts/rankings for each supported platform. NetEase charts SHALL be defined as hardcoded playlist IDs (飙升榜=19723756, 新歌榜=3779629, 热歌榜=3778678, 原创榜=2884035). QQ Music charts SHALL be fetched dynamically via the `musicu.fcg` endpoint using the `musicToplist.ToplistInfoServer.GetAll` module. Bilibili charts SHALL use the music zone ranking endpoint (`/x/web-interface/ranking/v2?rid=3`).

#### Scenario: NetEase chart list returned
- **WHEN** the user opens the Charts section
- **THEN** the system displays NetEase charts with their Chinese names (飙升榜, 新歌榜, 热歌榜, 原创榜) and platform badge

#### Scenario: QQ Music chart list fetched dynamically
- **WHEN** the user opens the Charts section
- **THEN** the system fetches available QQ Music toplists from the API and displays them with platform badge

#### Scenario: Bilibili music ranking available
- **WHEN** the user opens the Charts section
- **THEN** the system displays a Bilibili music ranking entry with platform badge

### Requirement: Fetch songs from a chart

The system SHALL fetch the song list for a selected chart. For NetEase, this SHALL reuse the existing `fetchPlaylistSongs()` method since charts are playlists. For QQ Music, this SHALL use the `musicToplist.ToplistInfoServer.GetDetail` module. For Bilibili, this SHALL parse the ranking response into `ParsedSong` entries with `video:BV{id}` format platform song IDs. Each song SHALL include title, artist, platform, platformSongId, deepLink, and coverUrl.

#### Scenario: NetEase chart songs loaded
- **WHEN** the user selects a NetEase chart (e.g., 热歌榜)
- **THEN** the system fetches songs using `fetchPlaylistSongs("3778678")` and displays them with title, artist, cover art, and platform badge

#### Scenario: QQ Music chart songs loaded
- **WHEN** the user selects a QQ Music toplist
- **THEN** the system fetches songs via `GetDetail` and displays them as `ParsedSong` entries with QQ Music deep links

#### Scenario: Bilibili ranking songs loaded
- **WHEN** the user selects the Bilibili music ranking
- **THEN** the system fetches videos from the music zone ranking and displays them as `ParsedSong` entries with `video:BV{id}` platform song IDs

#### Scenario: Chart fetch fails
- **WHEN** a chart song fetch fails due to network error or API error
- **THEN** the system displays an error state with a retry button and does not crash

### Requirement: No authentication required for charts

Chart fetching SHALL NOT require any user authentication. All chart API calls SHALL use the same anonymous headers (User-Agent, Referer) already used by the existing platform handlers.

#### Scenario: Charts accessible without login
- **WHEN** the user has not logged into any platform
- **THEN** all chart data is still fully accessible and functional

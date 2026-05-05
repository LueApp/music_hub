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

The system SHALL fetch personalized recommendations from QQ Music via `POST https://u.y.qq.com/cgi-bin/musicu.fcg` using the `music.recommend.RecommendFeed` / `get_recommend_feed` module with stored auth cookies (`qm_keyst`, `p_skey`, `uin`). The request SHALL include the computed `g_tk` CSRF token (derived from `p_skey` via the existing `computeGtk()` method) and the user's `uin` extracted from cookies. The response SHALL be parsed to extract daily recommended songs as `ParsedSong` entries with title, artist, album cover URL, platform song ID (songmid), deep link, and fallback URL.

If the authenticated API call fails (network error, expired session, or unparseable response), the system SHALL fall back to returning songs from the first available public chart via `fetchToplistAll()` and `fetchToplistDetail()`, logging a warning about the fallback.

#### Scenario: QQ Music recommendations loaded with valid session
- **WHEN** the user is logged into QQ Music (cookies stored) and opens the For You tab
- **THEN** the system calls the `music.recommend.RecommendFeed` / `get_recommend_feed` endpoint with authenticated cookies and `g_tk`, and displays personalized recommended songs from QQ Music

#### Scenario: QQ Music recommendations fallback on API failure
- **WHEN** the user is logged into QQ Music but the personalized recommendation API call fails
- **THEN** the system falls back to displaying songs from a public chart and logs a warning

#### Scenario: QQ Music not logged in
- **WHEN** the user is not logged into QQ Music
- **THEN** the For You section shows a login prompt card for QQ Music

### Requirement: Recommendation data is in-memory only

Recommendation results SHALL NOT be persisted to the database. They SHALL be held in the ViewModel's `StateFlow` and re-fetched when the tab is reopened or the user refreshes. Songs only enter Room when the user explicitly adds them.

#### Scenario: Recommendations not persisted
- **WHEN** the user navigates away from the Discover tab and returns
- **THEN** recommendations are re-fetched from the API (not loaded from a local cache)

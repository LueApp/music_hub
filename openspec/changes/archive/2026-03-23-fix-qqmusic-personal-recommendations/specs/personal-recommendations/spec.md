## MODIFIED Requirements

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

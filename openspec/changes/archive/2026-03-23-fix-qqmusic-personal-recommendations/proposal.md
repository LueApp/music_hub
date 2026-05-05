## Why

The Discover "For You" tab is supposed to show personalized daily recommendations from both NetEase and QQ Music. The NetEase implementation works correctly using `/api/v3/discovery/recommend/songs` with the `MUSIC_U` cookie. However, the QQ Music implementation is a stub that **ignores the auth cookies entirely** and falls back to returning songs from a public chart (the first chart in `fetchToplistAll()`). This means logged-in QQ Music users see generic chart songs instead of their personalized recommendations.

The auth infrastructure is already in place — `PlatformLoginActivity` captures QQ Music cookies (`qm_keyst`, `qqmusic_key`, `p_skey`), and `QQMusicPlatform` already has unused helper methods (`computeGtk`, `extractUin`, `extractCookie`) for authenticated API calls. The only missing piece is the actual authenticated API call to QQ Music's recommendation endpoint.

## What Changes

- Replace the stub `QQMusicPlatform.fetchDailyRecommendations()` with a real implementation that calls QQ Music's `musicu.fcg` API with authenticated parameters (`uin`, `qm_keyst` cookie, and `g_tk` CSRF token)
- Use the `music.recommend.RecommendFeed` / `get_recommend_feed` endpoint to fetch the homepage recommendation feed, then extract the daily recommendation song list
- Wire up the existing dead-code helper methods (`computeGtk`, `extractUin`, `extractCookie`) to construct authenticated requests
- Add fallback: if the personalized API fails (e.g., expired session), fall back to the current chart-based behavior with a log warning

## Non-goals

- Changing the QQ Music login flow — `PlatformLoginActivity` already captures the necessary cookies
- Modifying the UI — `ForYouTabFragment` already handles displaying QQ Music recommendations
- Adding token refresh — if the session expires, the user can re-login via the existing UI
- Supporting Bilibili recommendations — Bilibili does not have an equivalent personalized recommendation feature for music

## Capabilities

### New Capabilities

(none — the recommendation UI and auth infrastructure already exist)

### Modified Capabilities

- `personal-recommendations`: The QQ Music recommendation requirement currently specifies using `rcmusic.RecommendSongServer.get_rcmd_song_list` but the actual implementation is a chart fallback stub. The spec needs updating to reflect the correct API endpoint (`music.recommend.RecommendFeed` / `get_recommend_feed`) and the implementation needs to actually use authenticated cookies.

## Impact

- **Code**: `QQMusicPlatform.kt` — replace `fetchDailyRecommendations()` body (~20 lines changed)
- **APIs**: New dependency on QQ Music's `music.recommend.RecommendFeed` / `get_recommend_feed` authenticated endpoint via `musicu.fcg`
- **Auth**: Existing cookies (`qm_keyst`, `p_skey`, `uin`) captured by `PlatformLoginActivity` are now actually used
- **No new permissions or dependencies required**

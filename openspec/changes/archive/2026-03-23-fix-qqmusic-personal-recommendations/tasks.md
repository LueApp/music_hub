## 1. Implement Authenticated QQ Music Recommendation API Call

- [x] 1.1 Replace the stub body of `fetchDailyRecommendations(authCookies)` in `android-app/app/src/main/java/com/musichub/platform/QQMusicPlatform.kt` with a real authenticated API call to `music.recommend.RecommendFeed` / `get_recommend_feed` via `POST https://u.y.qq.com/cgi-bin/musicu.fcg?g_tk=<computed_gtk>`. Use `extractUin(authCookies)` for the `uin` field in the `comm` block, `extractCookie(authCookies, "p_skey")` + `computeGtk()` for the `g_tk` query parameter, and pass `authCookies` as the `Cookie` header.

- [x] 1.2 Parse the `get_recommend_feed` response JSON to extract recommended songs. Navigate the response structure (likely `req_0.data.shelf` or similar), find song entries, and convert each to a `ParsedSong` with `songmid` as `platformSongId`, `name` as `title`, `singer` array joined as `artist`, album `mid` for cover URL (`https://y.qq.com/music/photo_new/T002R300x300M000{albumMid}.jpg`), and generate deep link / fallback URL via existing helper methods. Log the response structure for debugging.

- [x] 1.3 Add fallback: wrap the authenticated API call in a try-catch. On any failure (network, parse, empty result), log a warning and fall back to the existing chart-based behavior (`fetchToplistAll()` + `fetchToplistDetail()`).

## 2. Build Verification and Testing

- [x] 2.1 Run `pixi run build` to verify the project compiles without errors.

- [x] 2.2 Deploy to device with `pixi run deploy`, log in to QQ Music via the For You tab, and verify personalized recommendations appear. Check `adb logcat -s QQMusicPlatform` for the API response structure and any errors.

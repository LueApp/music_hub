## Context

The "For You" tab in Discover shows personalized daily recommendations from both NetEase and QQ Music. NetEase works via `POST /api/v3/discovery/recommend/songs` with the `MUSIC_U` cookie. QQ Music's `fetchDailyRecommendations()` is a stub that ignores auth cookies and returns public chart songs instead.

The auth infrastructure already works end-to-end:
- `PlatformLoginActivity` captures QQ Music cookies (`qm_keyst`, `qqmusic_key`, `p_skey`, `uin`) via WebView
- `CookieStore` persists them in `EncryptedSharedPreferences`
- `DiscoverViewModel.loadRecommendations()` passes cookies to `QQMusicPlatform.fetchDailyRecommendations(authCookies)`
- `QQMusicPlatform` has unused helper methods: `extractCookie()`, `computeGtk()`, `extractUin()`

The only gap is the body of `fetchDailyRecommendations()` — it needs to make an authenticated `musicu.fcg` API call instead of fetching a public chart.

## Goals / Non-Goals

**Goals:**
- Replace the QQ Music recommendation stub with a real authenticated API call
- Parse the response into `ParsedSong` objects matching the existing data flow
- Graceful fallback to current chart behavior if the authenticated API fails

**Non-Goals:**
- Changing login flow, UI, or auth cookie storage
- Token refresh or session management
- Modifying any services (PlaybackService, FloatingWindowService, etc.) — this change is entirely within `QQMusicPlatform`
- Remote control mode implications — recommendations are fetched on the local device regardless of mode

## Decisions

### Decision 1: Use `music.recommend.RecommendFeed` / `get_recommend_feed` endpoint

**Choice**: Call the QQ Music homepage recommendation feed API via `musicu.fcg`.

**Request format** (follows the existing `musicu.fcg` JSON pattern used throughout `QQMusicPlatform`):
```json
POST https://u.y.qq.com/cgi-bin/musicu.fcg

{
  "comm": {
    "ct": 24,
    "cv": 0,
    "uin": "<extracted_uin>",
    "format": "json",
    "platform": "yqq.json"
  },
  "req_0": {
    "module": "music.recommend.RecommendFeed",
    "method": "get_recommend_feed",
    "param": {
      "direction": 0,
      "page": 1,
      "s_num": 0
    }
  }
}
```

**Auth**: Include the stored cookies as the `Cookie` header (same pattern as NetEase). The `uin` value is extracted from cookies using the existing `extractUin()` helper. The `g_tk` is computed from `p_skey` using `computeGtk()` and appended as a query parameter: `?g_tk=<value>`.

**Alternatives considered**:
- **`rcmusic.RecommendSongServer.get_rcmd_song_list`**: Referenced in the existing spec but undocumented in open-source implementations. Risky to use without confirmed request/response format.
- **`music.radioProxy.MbTrackRadioSvr` / `get_radio_track`**: Returns a radio-style stream, not a daily fixed list. Wrong user expectation (daily recommendations should be stable within a day).
- **Web scraping approach** (fetch HTML, extract playlist ID, fetch playlist): More fragile and requires two sequential HTTP calls.

**Rationale**: `RecommendFeed` is well-documented in multiple open-source QQ Music API libraries. It returns the homepage feed which includes daily recommendations as a section. The response contains song data directly, minimizing the number of API calls.

### Decision 2: Parse songs from the feed response

The `get_recommend_feed` response contains multiple "shelf" sections. We need to find the one containing daily recommended songs and extract the song list.

**Parsing strategy**:
1. Navigate to `req_0.data.shelf` (array of shelf sections)
2. Look for the shelf containing song data — identified by having `songlist` or similar song array
3. If the feed contains a "Daily30" / "每日推荐" section, extract songs from it
4. If we can't find a clear daily recommendation section, iterate shelves and collect songs from any shelf that contains song items
5. For each song, extract: `songmid` (platform ID), `name` (title), `singer` (artists array), `album` (with `mid` for cover URL)
6. Generate deep links using existing `generateDeepLink(songMid)` and fallback URLs using `generateFallbackUrl(songMid)`

Since the exact response structure may vary, we'll log the response structure on first successful call to aid debugging, and use defensive JSON parsing (null checks on every field).

### Decision 3: Graceful fallback on failure

If the authenticated API call fails for any reason (network error, expired session, unexpected response format), fall back to the existing chart-based behavior. This ensures the "For You" tab always shows something rather than being empty.

**Fallback chain**:
1. Try authenticated `RecommendFeed` API call
2. On failure, log a warning and fall back to `fetchToplistAll()` + `fetchToplistDetail()` (current behavior)
3. On total failure, return `emptyList()`

## Risks / Trade-offs

- **[API response structure uncertainty]** → Log the raw JSON structure on first call. Use defensive parsing. Fall back to chart on parse failure. Can iterate on the response parsing after seeing real data.
- **[Session expiry]** → No auto-refresh. User re-logs in via existing UI. The fallback ensures the tab still shows content.
- **[Rate limiting]** → QQ Music may rate-limit unauthenticated or excessive requests. Since recommendations are only fetched when the tab is opened, this is unlikely to trigger.
- **[Cookie format changes]** → The `extractCookie`, `computeGtk`, `extractUin` helpers are based on well-established QQ auth patterns used across many third-party clients. Low risk of format change.

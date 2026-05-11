## Purpose

Add Kugou Music (酷狗音乐, package `com.kugou.android`) as a fourth supported platform alongside NetEase Cloud Music, QQ Music, and Bilibili. Covers URL recognition, single-song and songlist parsing, metadata fetching, deep-link generation, availability checks, fallback paths, and how Kugou songs launch under both foreground and background (Shizuku freeform) modes — including the package-targeted `am start` that prevents HTTPS Kugou URLs from being intercepted by browsers.
## Requirements
### Requirement: Kugou is a recognized platform identifier
The system SHALL register Kugou Music (酷狗音乐) as a fourth platform alongside NetEase, QQ Music, and Bilibili. The platform identifier SHALL be the string `"kugou"`. The display name SHALL be `酷狗音乐`. The Android package name SHALL be `com.kugou.android`.

#### Scenario: Kugou is enumerable from Platforms registry
- **WHEN** code reads `Platforms.KUGOU`
- **THEN** the value SHALL equal `"kugou"`
- **AND** `Platforms.DISPLAY_NAMES["kugou"]` SHALL equal `"酷狗音乐"`
- **AND** `Platforms.PACKAGE_NAMES["kugou"]` SHALL equal `"com.kugou.android"`

#### Scenario: Kugou platform is selectable in UI filter chips
- **WHEN** the user opens the library or playlist filter that lists supported platforms
- **THEN** Kugou (酷狗音乐) SHALL appear as a selectable filter chip alongside NetEase, QQ Music, and Bilibili

### Requirement: KugouPlatform handler implements PlatformHandler interface
The system SHALL provide a `KugouPlatform` class that implements every member of `PlatformHandler` (`canHandle`, `parseSongUrl`, `parsePlaylistUrl`, `generateDeepLink`, `generateFallbackUrl`, `fetchMetadata`, `checkSongAvailability`, `fetchPlaylistSongs`). `LinkParser.handlers` SHALL include an instance of `KugouPlatform`.

#### Scenario: LinkParser dispatches Kugou URLs to KugouPlatform
- **WHEN** `LinkParser.parseSharedUrl("https://m.kugou.com/song/...")` is called
- **THEN** the system SHALL select `KugouPlatform` (because `canHandle` returns true) and invoke its `parseSongUrl`

#### Scenario: PlaybackService can resolve KugouPlatform by platform string
- **WHEN** `PlaybackService.getHandlerForPlatform("kugou")` is called
- **THEN** the returned handler SHALL be a `KugouPlatform` instance

### Requirement: Kugou URL recognition
`KugouPlatform.canHandle(url)` SHALL return true for any URL whose host contains `kugou.com` (covering `m.kugou.com`, `www.kugou.com`, `t1.kugou.com`, `wwwapi.kugou.com`, `mobilecdnbj.kugou.com`). Other handlers' `canHandle` SHALL continue to return false for these URLs.

#### Scenario: Mobile songlist URL is recognized
- **WHEN** `KugouPlatform().canHandle("https://m.kugou.com/songlist/gcid_3zljhp4bz2z02f/")` is called
- **THEN** the result SHALL be true

#### Scenario: Web song URL is recognized
- **WHEN** `KugouPlatform().canHandle("https://www.kugou.com/song/abcdef0123456789abcdef0123456789.html")` is called
- **THEN** the result SHALL be true

#### Scenario: Non-Kugou URL is not recognized
- **WHEN** `KugouPlatform().canHandle("https://music.163.com/song?id=123")` is called
- **THEN** the result SHALL be false

### Requirement: Kugou single-song URL parsing
`KugouPlatform.parseSongUrl(url)` SHALL return a `ParsedSong` with `platform = "kugou"` and `platformSongId` equal to the 32-character lowercase Kugou song hash extracted from the URL. If no hash can be extracted, it SHALL return null.

#### Scenario: Hash extracted from query parameter
- **WHEN** `parseSongUrl("https://m.kugou.com/song/?hash=ABCDEF0123456789ABCDEF0123456789&album_id=0")` is called
- **THEN** the returned `ParsedSong.platformSongId` SHALL equal `"abcdef0123456789abcdef0123456789"`
- **AND** `ParsedSong.platform` SHALL equal `"kugou"`
- **AND** `ParsedSong.deepLink` SHALL be the Kugou deep-link form for that hash
- **AND** `ParsedSong.fallbackUrl` SHALL be the HTTPS fallback for that hash

#### Scenario: Hash extracted from path
- **WHEN** `parseSongUrl("https://www.kugou.com/song/abcdef0123456789abcdef0123456789.html")` is called
- **THEN** the returned `ParsedSong.platformSongId` SHALL equal `"abcdef0123456789abcdef0123456789"`

#### Scenario: URL without recoverable hash returns null
- **WHEN** `parseSongUrl("https://m.kugou.com/")` is called
- **THEN** the result SHALL be null

### Requirement: Kugou songlist URL parsing
`KugouPlatform.parsePlaylistUrl(url)` SHALL recognize URLs of the form `m.kugou.com/songlist/gcid_<id>/...` (and equivalent www / wwwapi hosts). It SHALL return a `ParsedPlaylist` with `platform = "kugou"` and `playlistId = <id>` (the value following `gcid_`). For non-songlist URLs it SHALL return null.

#### Scenario: gcid songlist URL parses to playlist id
- **WHEN** `parsePlaylistUrl("https://m.kugou.com/songlist/gcid_3zljhp4bz2z02f/?src_cid=3zljhp4bz2z02f&uid=1132230901")` is called
- **THEN** the returned `ParsedPlaylist.playlistId` SHALL equal `"3zljhp4bz2z02f"`
- **AND** `ParsedPlaylist.platform` SHALL equal `"kugou"`

#### Scenario: Single-song URL is not treated as a playlist
- **WHEN** `parsePlaylistUrl("https://m.kugou.com/song/?hash=abcdef0123456789abcdef0123456789")` is called
- **THEN** the result SHALL be null

### Requirement: Kugou deep link generation uses kugou:// custom scheme
`KugouPlatform.generateDeepLink(platformSongId)` SHALL return a `kugou://` custom-scheme URI that the Kugou Android app handles. `generateFallbackUrl(platformSongId)` SHALL return an `https://` URL on `m.kugou.com` that loads in any browser when the Kugou app is unavailable.

#### Scenario: Deep link uses kugou scheme
- **WHEN** `generateDeepLink("abcdef0123456789abcdef0123456789")` is called
- **THEN** the returned string SHALL begin with `"kugou://"`
- **AND** it SHALL contain the song hash `"abcdef0123456789abcdef0123456789"`

#### Scenario: Fallback uses https on m.kugou.com
- **WHEN** `generateFallbackUrl("abcdef0123456789abcdef0123456789")` is called
- **THEN** the returned string SHALL begin with `"https://m.kugou.com/"`
- **AND** it SHALL contain the song hash `"abcdef0123456789abcdef0123456789"`

### Requirement: DeepLinkLauncher targets Kugou app for Kugou links
When `DeepLinkLauncher` launches a deep link that begins with `kugou://` or contains `kugou.com`, it SHALL set `intent.setPackage("com.kugou.android")` on the launch intent. If the package is not installed (`ActivityNotFoundException` or resolver returns null), it SHALL fall back to launching the HTTPS fallback URL via the system browser without crashing.

#### Scenario: Kugou link is targeted to com.kugou.android
- **WHEN** `DeepLinkLauncher` is invoked with deep link `kugou://...` and the Kugou app is installed
- **THEN** the launched intent SHALL have `package == "com.kugou.android"`
- **AND** the Kugou app SHALL come to foreground

#### Scenario: Missing Kugou app falls back to browser
- **WHEN** `DeepLinkLauncher` is invoked with a Kugou deep link and `com.kugou.android` is not installed
- **THEN** the system SHALL launch the HTTPS fallback URL
- **AND** the system SHALL NOT crash with an unhandled `ActivityNotFoundException`

### Requirement: Kugou metadata fetched from public web API
`KugouPlatform.fetchMetadata(platformSongId)` SHALL fetch song details from a public Kugou web endpoint (no authenticated cookies, no signature) and return a `Map<String, String>` containing keys `title`, `artist`, `album`, and `cover_url` when successfully fetched. On network or parse failure it SHALL log the failure and return an empty or partially-populated map without throwing.

#### Scenario: Successful metadata fetch populates fields
- **WHEN** `fetchMetadata("<valid-hash>")` is called and the API responds successfully
- **THEN** the returned map SHALL contain a non-empty `title` value
- **AND** the returned map SHALL contain a non-empty `artist` value
- **AND** any cover URL returned SHALL use HTTPS (replacing `http://` with `https://`)

#### Scenario: Network failure does not throw
- **WHEN** `fetchMetadata("<hash>")` is called and the API request fails with an exception
- **THEN** the function SHALL return an empty or partial `Map<String, String>`
- **AND** no exception SHALL propagate to the caller

### Requirement: Kugou song availability check
`KugouPlatform.checkSongAvailability(platformSongId)` SHALL return a `SongAvailability` indicating whether the song can be played. It SHALL treat HTTP success with non-empty title metadata as available; missing or error-coded responses as unavailable with a Chinese-language reason. On network exception it SHALL return `SongAvailability(true)` to avoid blocking playback.

#### Scenario: Available song returns isAvailable=true
- **WHEN** `checkSongAvailability("<valid-hash>")` is called and the API returns valid song data
- **THEN** the result SHALL have `isAvailable == true`

#### Scenario: Removed song returns isAvailable=false with reason
- **WHEN** `checkSongAvailability("<hash-of-removed-song>")` is called and the API returns an error indicating removal
- **THEN** the result SHALL have `isAvailable == false`
- **AND** the result SHALL have a non-empty Chinese reason

#### Scenario: Network failure does not block playback
- **WHEN** `checkSongAvailability("<hash>")` is called and the network request throws
- **THEN** the result SHALL have `isAvailable == true` (fail-open)

### Requirement: Kugou songlist import populates ParsedPlaylist
`KugouPlatform.fetchPlaylistSongs(playlistId)` SHALL fetch a Kugou songlist (`gcid_<id>` value) from the public web API and return a `ParsedPlaylist` containing the playlist's name, cover URL, song count, and a list of `ParsedSong` entries — one per track. On API or parse failure it SHALL log and return null.

#### Scenario: Public songlist fetches all songs
- **WHEN** `fetchPlaylistSongs("3zljhp4bz2z02f")` is called for the user's shared songlist
- **THEN** the returned `ParsedPlaylist.platform` SHALL equal `"kugou"`
- **AND** `ParsedPlaylist.songs` SHALL contain at least one `ParsedSong`
- **AND** every entry's `platform` SHALL equal `"kugou"`
- **AND** every entry's `platformSongId` SHALL be a 32-character lowercase hex hash
- **AND** every entry's `deepLink` SHALL begin with `"kugou://"`

#### Scenario: Failed fetch returns null
- **WHEN** `fetchPlaylistSongs("<invalid-id>")` is called and the API returns an error code
- **THEN** the result SHALL be null
- **AND** no exception SHALL propagate

### Requirement: MediaMonitorService observes Kugou playback
`MediaMonitorService` SHALL include `com.kugou.android` in its watched-package list so that Kugou playback state changes (play, pause, position updates, song-end) trigger the same auto-advance logic that NetEase and QQ Music use today.

#### Scenario: Kugou MediaSession is observed
- **WHEN** the Kugou app starts playing a song after being launched by Music Hub
- **THEN** `MediaMonitorService` SHALL register a `MediaController.Callback` for the `com.kugou.android` controller
- **AND** subsequent playback-state changes from Kugou SHALL be visible in `MediaMonitorService` logs

#### Scenario: Kugou song end triggers auto-advance
- **WHEN** Kugou playback reaches the end of the current song (position within the standard `SONG_END_THRESHOLD_MS` window of duration)
- **AND** Music Hub's current song is the matching Kugou song
- **THEN** `MediaMonitorService` SHALL signal song-finished
- **AND** `PlaybackService` SHALL advance to the next queued song

### Requirement: ShizukuLauncher recognises Kugou as a music app
`ShizukuLauncher.musicAppPackages()` SHALL include `"com.kugou.android"`. `ShizukuLauncher.packageForDeepLink(deepLink)` SHALL return `"com.kugou.android"` for any deep link that begins with `kugou://` or contains `kugou.com`.

#### Scenario: Kugou is in the music-app set
- **WHEN** `ShizukuLauncher.musicAppPackages()` is called
- **THEN** the returned set SHALL contain `"com.kugou.android"`

#### Scenario: kugou:// deep link maps to the Kugou package
- **WHEN** `ShizukuLauncher.packageForDeepLink("kugou://...")` is called
- **THEN** the result SHALL be `"com.kugou.android"`

#### Scenario: kugou.com URL maps to the Kugou package
- **WHEN** `ShizukuLauncher.packageForDeepLink("https://m.kugou.com/song/...")` is called
- **THEN** the result SHALL be `"com.kugou.android"`

### Requirement: Kugou UI badge appears wherever platform badges are rendered
The system SHALL display a Kugou icon and brand-coloured badge for any `Song` whose `platform` equals `"kugou"` in every UI surface that already shows a NetEase / QQ Music / Bilibili badge (song list rows, queue rows, skip-log rows, chart rows, selectable rows, discovery rows). A vector drawable `R.drawable.ic_kugou` and a circular badge background `R.drawable.bg_badge_kugou` SHALL exist.

#### Scenario: Library row shows Kugou badge
- **WHEN** the library list contains a song with `platform = "kugou"`
- **THEN** the row SHALL display `R.drawable.ic_kugou` over a `R.drawable.bg_badge_kugou` background

#### Scenario: Queue row shows Kugou badge
- **WHEN** the play queue contains a song with `platform = "kugou"`
- **THEN** the row SHALL display the Kugou badge in the platform-icon position

### Requirement: Importing the user's example Kugou songlist succeeds end-to-end
When the user shares the songlist URL `https://m.kugou.com/songlist/gcid_3zljhp4bz2z02f/?src_cid=3zljhp4bz2z02f&uid=1132230901&chl=message&...` into Music Hub via the share intent, the system SHALL recognise it as a Kugou playlist, fetch its tracks, and offer to import them into a playlist — without manual platform selection.

#### Scenario: Sharing the songlist URL triggers playlist import flow
- **WHEN** the user shares the example songlist URL into Music Hub
- **THEN** `LinkParser.isPlaylistUrl(...)` SHALL return true
- **AND** `LinkParser.parsePlaylistUrl(...)` SHALL return a non-null `ParsedPlaylist` with `platform = "kugou"`
- **AND** the `ParsedPlaylist.songs` list SHALL contain the playlist's tracks ready for import

### Requirement: Kugou songs launch in the Kugou app under background mode
When `launch_mode=background` is active and a Kugou song is launched, the system SHALL open the song in `com.kugou.android` (the Kugou Music app) — not in a browser, not in a disambiguation dialog, and not in any other HTTPS handler. This holds whether the Kugou deep link uses the `https://m.kugou.com/mixsong/<id>.html` form (per-song share URL captured from a songlist's `_song_url`) or the `https://m.kugou.com/song/?hash=<hash>` form (legacy generator output for bare-hash entries).

#### Scenario: Kugou song with mixsong deep link launches Kugou via Shizuku
- **GIVEN** Shizuku is installed, running, and granted to Music Hub
- **AND** `launch_mode` preference is `background`
- **AND** the screen is unlocked
- **WHEN** `DeepLinkLauncher.launch(context, "https://m.kugou.com/mixsong/abc123.html", fallbackUrl)` is called
- **THEN** the system SHALL invoke `am start --windowingMode 5 -a android.intent.action.VIEW -d https://m.kugou.com/mixsong/abc123.html -p com.kugou.android -f 0x10010000` via the Shizuku shell
- **AND** the resulting top freeform task SHALL belong to `com.kugou.android`
- **AND** the post-launch resize watchdog SHALL find that task by package match in `dumpsys activity activities` and move it to the off-screen bounds

#### Scenario: Kugou song with song?hash deep link launches Kugou via Shizuku
- **GIVEN** the same Shizuku/background-mode preconditions as the previous scenario
- **WHEN** `DeepLinkLauncher.launch(context, "https://m.kugou.com/song/?hash=abcdef0123456789abcdef0123456789", fallbackUrl)` is called
- **THEN** the `am start` command SHALL include `-p com.kugou.android`
- **AND** the resulting top freeform task SHALL belong to `com.kugou.android` (assuming Kugou's manifest claims the URL)

### Requirement: ShizukuLauncher resolves target package from the deep link
`ShizukuLauncher.launchFreeform(context, deepLink, bounds, boundsProvider)` SHALL resolve a target package from the deep link via the existing `packageForDeepLink` mapping. When the mapping returns a non-null package, the `am start` argv SHALL include `-p <package>` between the `-d <deepLink>` and `-f <flags>` arguments. When the mapping returns null, the `-p` argument SHALL be omitted and the command SHALL be byte-identical to its pre-change form.

#### Scenario: Known kugou.com URL resolves to com.kugou.android package
- **WHEN** `launchFreeform` is called with deepLink `"https://m.kugou.com/mixsong/x.html"`
- **THEN** `packageForDeepLink` SHALL return `"com.kugou.android"`
- **AND** the `am start` argv SHALL include `-p com.kugou.android`

#### Scenario: orpheus:// scheme still resolves to NetEase package
- **WHEN** `launchFreeform` is called with deepLink `"orpheus://song/123"`
- **THEN** `packageForDeepLink` SHALL return `"com.netease.cloudmusic"`
- **AND** the `am start` argv SHALL include `-p com.netease.cloudmusic`
- **AND** the launch SHALL succeed identically to the pre-change behavior because NetEase is the only intent-filter handler for `orpheus://`

#### Scenario: qqmusic:// scheme still resolves to QQ Music package
- **WHEN** `launchFreeform` is called with deepLink `"qqmusic://qq.com/ui/openUrl?p=..."`
- **THEN** the `am start` argv SHALL include `-p com.tencent.qqmusic`

#### Scenario: bilibili:// scheme still resolves to Bilibili package
- **WHEN** `launchFreeform` is called with deepLink `"bilibili://video/BV1xx411c7mu"`
- **THEN** the `am start` argv SHALL include `-p tv.danmaku.bili`

#### Scenario: Unknown deep link omits the package argument
- **WHEN** `launchFreeform` is called with a deepLink whose host doesn't match any platform (e.g. `"https://example.com/song/123"`)
- **THEN** `packageForDeepLink` SHALL return null
- **AND** the `am start` argv SHALL NOT include the `-p` flag
- **AND** the rest of the argv SHALL be byte-identical to its pre-change form

### Requirement: Shizuku-unavailable fallback path uses setPackage for Kugou
When Shizuku is not in `Status.READY` and `launchBackground` falls back to a regular `startActivity` Intent, the Intent SHALL have `setPackage("com.kugou.android")` applied for any Kugou deep link, so that the regular Android resolver also targets Kugou rather than a browser. This requirement preserves existing behavior already implemented in `DeepLinkLauncher.launchBackground`'s fallback branch.

#### Scenario: Shizuku not ready, Kugou Intent has setPackage applied
- **GIVEN** Shizuku status is anything other than `READY` (not installed, service not running, or permission denied)
- **AND** `launch_mode` preference is `background`
- **WHEN** `DeepLinkLauncher.launch(context, "https://m.kugou.com/mixsong/xyz.html", fallbackUrl)` is called
- **THEN** a Toast SHALL inform the user that Shizuku is unavailable and the launch fell back to full-screen
- **AND** the fallback Intent SHALL have `setPackage("com.kugou.android")` applied
- **AND** the song SHALL open in the Kugou app (fullscreen, not freeform) — not in the browser

### Requirement: Locked-screen path uses setPackage for Kugou
When `launchForLockedScreen` is invoked for a Kugou deep link, the Intent SHALL have `setPackage("com.kugou.android")` applied so the song opens in Kugou after the user unlocks the screen, rather than going through the HTTPS resolver. This requirement preserves existing behavior already implemented in `DeepLinkLauncher.launchForLockedScreen`.

#### Scenario: Screen locked, Kugou deep link routes to Kugou app
- **GIVEN** the device screen is locked (keyguard active OR screen off)
- **WHEN** `DeepLinkLauncher.launch(context, "https://m.kugou.com/mixsong/xyz.html", fallbackUrl)` is called
- **THEN** the launch SHALL go through `launchForLockedScreen` (not background or foreground)
- **AND** the resulting Intent SHALL have `setPackage("com.kugou.android")` applied
- **AND** the song SHALL open in Kugou (foregrounded) once the screen unlocks


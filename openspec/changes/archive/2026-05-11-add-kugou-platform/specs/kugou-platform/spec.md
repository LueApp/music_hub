## ADDED Requirements

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

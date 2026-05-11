## ADDED Requirements

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

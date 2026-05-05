## Requirements

### Requirement: Bilibili video deep links force playback from the beginning
The system SHALL append `?start_progress=0` to Bilibili video deep links (`bilibili://video/{id}`) to force the Bilibili app to start playback from the beginning of the video, overriding the app's resume-from-last-position behavior.

#### Scenario: BV-format video starts from beginning
- **WHEN** `generateDeepLink()` is called with platformSongId `video:BV1xx411c7mD`
- **THEN** the returned deep link SHALL be `bilibili://video/BV1xx411c7mD?start_progress=0`

#### Scenario: AV-format video starts from beginning
- **WHEN** `generateDeepLink()` is called with platformSongId `video:av170001`
- **THEN** the returned deep link SHALL be `bilibili://video/av170001?start_progress=0`

#### Scenario: Audio deep links are not affected
- **WHEN** `generateDeepLink()` is called with platformSongId `audio:12345`
- **THEN** the returned deep link SHALL be `bilibili://music/detail/12345` (no `start_progress` parameter)

### Requirement: Legacy HTTPS Bilibili deep link conversion includes start_progress
When `DeepLinkLauncher` converts legacy HTTPS Bilibili video deep links to `bilibili://` scheme at launch time, the converted link SHALL include `?start_progress=0`.

#### Scenario: Legacy BV video conversion includes start_progress
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/video/BV1xx411c7mD`
- **THEN** the system SHALL convert it to `bilibili://video/BV1xx411c7mD?start_progress=0` before launching

#### Scenario: Legacy audio conversion is unchanged
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/audio/au12345`
- **THEN** the system SHALL convert it to `bilibili://music/detail/12345` (no `start_progress` parameter)

### Requirement: MediaMonitorService monitors Bilibili media sessions
The system SHALL include `tv.danmaku.bili` in MediaMonitorService's `targetPackages` set, enabling media session monitoring for Bilibili content. This allows song-end detection and auto-advance to work for Bilibili content in playlists.

#### Scenario: Bilibili media session is registered
- **WHEN** the Bilibili app (`tv.danmaku.bili`) has an active media session
- **THEN** MediaMonitorService SHALL register a callback for that media session

#### Scenario: Bilibili song finishes and triggers auto-advance
- **WHEN** a Bilibili video finishes playing and the media session reports playback stopped near the end of the content
- **THEN** the system SHALL send a song-finished broadcast to trigger auto-advance to the next song

#### Scenario: Bilibili metadata changes before the real end
- **WHEN** a Bilibili media session reports a metadata change before the previous content is within the final second or at least 99.5% played with no more than 3 seconds remaining
- **THEN** MediaMonitorService SHALL NOT treat the metadata change as song finished

### Requirement: PlaybackService allows auto-advance for Bilibili content
The system SHALL NOT skip auto-advance when the current song is from the Bilibili platform. The Bilibili exclusion in `songFinishedReceiver` SHALL be removed so that song-finished broadcasts trigger `playNext()` for Bilibili content.

#### Scenario: Bilibili song finished triggers next song
- **WHEN** a song-finished broadcast is received and the current song is from Bilibili
- **THEN** the system SHALL call `playNext()` to advance to the next song in the queue

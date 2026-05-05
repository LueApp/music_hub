## Requirements

### Requirement: Bilibili video deep links use native URI scheme
The system SHALL generate `bilibili://video/{id}?start_progress=0` deep links for Bilibili video content. For BV-format videos, the deep link SHALL be `bilibili://video/{BVid}?start_progress=0`. For AV-format videos, the deep link SHALL be `bilibili://video/av{avid}?start_progress=0`. The `?start_progress=0` parameter forces playback from the beginning.

#### Scenario: BV-format video deep link generation
- **WHEN** `generateDeepLink()` is called with platformSongId `video:BV1xx411c7mD`
- **THEN** the returned deep link SHALL be `bilibili://video/BV1xx411c7mD?start_progress=0`

#### Scenario: AV-format video deep link generation
- **WHEN** `generateDeepLink()` is called with platformSongId `video:av170001`
- **THEN** the returned deep link SHALL be `bilibili://video/av170001?start_progress=0`

### Requirement: Legacy HTTPS Bilibili deep links are converted at launch time
When DeepLinkLauncher receives a Bilibili HTTPS deep link (from existing database entries), it SHALL convert it to the `bilibili://` scheme with `?start_progress=0` for video content at launch time before attempting to launch.

#### Scenario: Legacy BV video HTTPS link conversion
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/video/BV1xx411c7mD`
- **THEN** the system SHALL convert it to `bilibili://video/BV1xx411c7mD?start_progress=0` before launching

#### Scenario: Legacy AV video HTTPS link conversion
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/video/av170001`
- **THEN** the system SHALL convert it to `bilibili://video/av170001?start_progress=0` before launching

#### Scenario: Legacy audio HTTPS link conversion
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/audio/au12345`
- **THEN** the system SHALL convert it to `bilibili://music/detail/12345` before launching

#### Scenario: Non-Bilibili HTTPS links are not affected
- **WHEN** DeepLinkLauncher receives deep link `https://music.163.com/song?id=123`
- **THEN** the system SHALL NOT modify the deep link

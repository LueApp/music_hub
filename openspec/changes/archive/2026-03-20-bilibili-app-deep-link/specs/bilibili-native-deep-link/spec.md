## ADDED Requirements

### Requirement: Bilibili video deep links use native URI scheme
The system SHALL generate `bilibili://video/{id}` deep links for Bilibili video content instead of `https://www.bilibili.com/video/{id}` URLs. For BV-format videos, the deep link SHALL be `bilibili://video/{BVid}`. For AV-format videos, the deep link SHALL be `bilibili://video/av{avid}`.

#### Scenario: BV-format video deep link generation
- **WHEN** `generateDeepLink()` is called with platformSongId `video:BV1xx411c7mD`
- **THEN** the returned deep link SHALL be `bilibili://video/BV1xx411c7mD`

#### Scenario: AV-format video deep link generation
- **WHEN** `generateDeepLink()` is called with platformSongId `video:av170001`
- **THEN** the returned deep link SHALL be `bilibili://video/av170001`

### Requirement: Bilibili audio deep links use native URI scheme
The system SHALL generate `bilibili://music/detail/{auid}` deep links for Bilibili audio content instead of `https://www.bilibili.com/audio/au{id}` URLs.

#### Scenario: Audio deep link generation
- **WHEN** `generateDeepLink()` is called with platformSongId `audio:12345`
- **THEN** the returned deep link SHALL be `bilibili://music/detail/12345`

### Requirement: Bilibili fallback URLs remain as HTTPS
The system SHALL keep `generateFallbackUrl()` returning `https://www.bilibili.com/...` URLs for all Bilibili content types. These HTTPS URLs serve as the browser fallback when the Bilibili app is not installed.

#### Scenario: Fallback URL for BV video
- **WHEN** `generateFallbackUrl()` is called with platformSongId `video:BV1xx411c7mD`
- **THEN** the returned URL SHALL be `https://www.bilibili.com/video/BV1xx411c7mD`

#### Scenario: Fallback URL for audio
- **WHEN** `generateFallbackUrl()` is called with platformSongId `audio:12345`
- **THEN** the returned URL SHALL be `https://www.bilibili.com/audio/au12345`

### Requirement: DeepLinkLauncher targets Bilibili app package for bilibili:// links
When launching a deep link that starts with `bilibili://`, the system SHALL set the intent package to `tv.danmaku.bili` to explicitly target the Bilibili app. If the Bilibili app is not installed, the system SHALL fall back to the HTTPS fallback URL.

#### Scenario: Bilibili app is installed
- **WHEN** a `bilibili://video/BV1xx411c7mD` deep link is launched
- **AND** the Bilibili app (`tv.danmaku.bili`) is installed
- **THEN** the system SHALL create an `ACTION_VIEW` intent with package `tv.danmaku.bili` and launch it

#### Scenario: Bilibili app is not installed
- **WHEN** a `bilibili://video/BV1xx411c7mD` deep link is launched
- **AND** the Bilibili app is NOT installed
- **THEN** the system SHALL fall back to launching the HTTPS fallback URL in the browser

### Requirement: Legacy HTTPS Bilibili deep links are converted at launch time
When DeepLinkLauncher receives a Bilibili HTTPS deep link (from existing database entries), it SHALL convert it to the `bilibili://` scheme at launch time before attempting to launch. This ensures existing songs in the database benefit from the native app launch without requiring a database migration.

#### Scenario: Legacy BV video HTTPS link conversion
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/video/BV1xx411c7mD`
- **THEN** the system SHALL convert it to `bilibili://video/BV1xx411c7mD` before launching

#### Scenario: Legacy AV video HTTPS link conversion
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/video/av170001`
- **THEN** the system SHALL convert it to `bilibili://video/av170001` before launching

#### Scenario: Legacy audio HTTPS link conversion
- **WHEN** DeepLinkLauncher receives deep link `https://www.bilibili.com/audio/au12345`
- **THEN** the system SHALL convert it to `bilibili://music/detail/12345` before launching

#### Scenario: Non-Bilibili HTTPS links are not affected
- **WHEN** DeepLinkLauncher receives deep link `https://music.163.com/song?id=123`
- **THEN** the system SHALL NOT modify the deep link

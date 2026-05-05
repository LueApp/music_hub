## ADDED Requirements

### Requirement: Platform availability check interface
The PlatformHandler interface SHALL provide a `checkSongAvailability(platformSongId: String)` method that returns a `SongAvailability` result containing `isAvailable: Boolean` and `reason: String`.

The default implementation SHALL fall back to `fetchMetadata()` and consider the song available if a non-empty title is returned.

#### Scenario: Default fallback when platform has no specific check
- **WHEN** a PlatformHandler implementation does not override `checkSongAvailability()`
- **THEN** the system SHALL call `fetchMetadata()` and return available if a title exists, unavailable otherwise

### Requirement: NetEase availability check
The NetEasePlatform SHALL check song availability by calling the `/api/v3/song/detail` API and inspecting the `privileges[0].st` field. A value of `st < 0` SHALL indicate the song is unavailable.

#### Scenario: NetEase song is available
- **WHEN** the NetEase API returns a song with `privileges[0].st >= 0`
- **THEN** the system SHALL return `SongAvailability(isAvailable=true)`

#### Scenario: NetEase song is taken down
- **WHEN** the NetEase API returns a song with `privileges[0].st < 0`
- **THEN** the system SHALL return `SongAvailability(isAvailable=false, reason="歌曲已下架或无版权")`

#### Scenario: NetEase song does not exist
- **WHEN** the NetEase API returns an empty `songs` array
- **THEN** the system SHALL return `SongAvailability(isAvailable=false, reason="歌曲不存在")`

### Requirement: QQ Music availability check
The QQMusicPlatform SHALL check song availability by calling the `musicu.fcg` API with `get_song_detail_yqq` and inspecting the `track_info.fnote` field. A value of `fnote == 4001` SHALL indicate the song is taken down.

#### Scenario: QQ Music song is available
- **WHEN** the QQ Music API returns track info with `fnote != 4001` and a non-empty name
- **THEN** the system SHALL return `SongAvailability(isAvailable=true)`

#### Scenario: QQ Music song is taken down
- **WHEN** the QQ Music API returns track info with `fnote == 4001`
- **THEN** the system SHALL return `SongAvailability(isAvailable=false, reason="歌曲已下架")`

#### Scenario: QQ Music song does not exist
- **WHEN** the QQ Music API returns no `track_info` object
- **THEN** the system SHALL return `SongAvailability(isAvailable=false, reason="歌曲不存在")`

### Requirement: Bilibili availability check
The BilibiliPlatform SHALL check availability using the appropriate API based on content type: `/x/web-interface/view` for video content and `/audio/music-service-c/web/song/info` for audio content.

#### Scenario: Bilibili video is available
- **WHEN** the Bilibili video API returns `code == 0`
- **THEN** the system SHALL return `SongAvailability(isAvailable=true)`

#### Scenario: Bilibili video is not found
- **WHEN** the Bilibili video API returns `code == -404`
- **THEN** the system SHALL return `SongAvailability(isAvailable=false, reason="视频不存在")`

#### Scenario: Bilibili video is forbidden
- **WHEN** the Bilibili video API returns `code == -403`
- **THEN** the system SHALL return `SongAvailability(isAvailable=false, reason="视频无法访问")`

#### Scenario: Bilibili audio is unavailable
- **WHEN** the Bilibili audio API returns `code != 0`
- **THEN** the system SHALL return `SongAvailability(isAvailable=false)` with the API's error message

### Requirement: Pre-launch availability check in PlaybackService
The PlaybackService SHALL check song availability before launching a deep link. The check MUST run asynchronously via a coroutine scope tied to the service lifecycle.

#### Scenario: Song is available
- **WHEN** `checkSongAvailability()` returns `isAvailable=true`
- **THEN** the system SHALL proceed to launch the deep link normally

#### Scenario: Song is unavailable
- **WHEN** `checkSongAvailability()` returns `isAvailable=false`
- **THEN** the system SHALL skip the song, show a toast "跳过: {title} ({reason})", log the skip, and auto-advance to the next song

### Requirement: Fail-open on network errors
The availability check SHALL assume the song is available if the API call fails due to network errors, timeouts, or unexpected exceptions.

#### Scenario: Network timeout during check
- **WHEN** the availability check HTTP request times out
- **THEN** the system SHALL return `SongAvailability(isAvailable=true)` and proceed with launch

#### Scenario: Unexpected exception during check
- **WHEN** the availability check throws any exception
- **THEN** the system SHALL log the exception and proceed with launch as if the song were available

### Requirement: Consecutive skip safety limit
The PlaybackService SHALL track consecutive unavailable song skips and stop playback after reaching a configurable maximum (default: 10).

#### Scenario: Consecutive skip limit reached
- **WHEN** 10 consecutive songs are skipped as unavailable
- **THEN** the system SHALL stop playback and show a toast "连续多首歌曲不可用，已停止播放"

#### Scenario: Available song resets counter
- **WHEN** a song passes the availability check after previous skips
- **THEN** the consecutive skip counter SHALL reset to 0

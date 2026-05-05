## Requirements

### Requirement: Playback timeout detection
After launching a song via deep link, PlaybackService SHALL schedule a timeout check. If no active playback (PLAYING state) is detected when the timeout fires, the system SHALL treat the song as a playback failure.

#### Scenario: Song fails to play within timeout
- **WHEN** a song is launched via deep link AND no PLAYING state is detected within 15 seconds
- **THEN** the system SHALL log the failure with song title, platform, platformSongId, and "playback timeout" reason AND show a toast "跳过: {title} (播放超时)" AND auto-skip to the next song

#### Scenario: Song starts playing before timeout
- **WHEN** a song is launched via deep link AND PLAYING state is detected before the timeout fires
- **THEN** the timeout check SHALL detect active playback and take no action (normal playback continues)

#### Scenario: User manually skips before timeout fires
- **WHEN** a song is launched AND the user triggers playNext/playPrevious/stop before the timeout fires
- **THEN** the pending timeout SHALL be cancelled (or ignored when it fires by checking the current song ID no longer matches)

### Requirement: Timeout cancellation on new song launch
PlaybackService SHALL cancel any pending playback timeout when a new song launch begins (via `launchCurrentSong()`), to prevent stale timeouts from affecting the newly launched song.

#### Scenario: Rapid song switching
- **WHEN** song A is launched AND before the timeout fires, song B is launched
- **THEN** the timeout for song A SHALL be cancelled AND a new timeout SHALL be scheduled for song B

### Requirement: Consecutive skip integration
Playback timeout failures SHALL increment the same `consecutiveSkips` counter used by pre-launch availability checks. When `consecutiveSkips` reaches `MAX_CONSECUTIVE_SKIPS` (10), playback SHALL stop with toast "连续多首歌曲不可用，已停止播放".

#### Scenario: Mixed pre-launch and timeout failures
- **WHEN** 5 songs are skipped by pre-launch availability check AND 5 more songs timeout at runtime
- **THEN** the 10th skip SHALL trigger the safety stop, halting playback

#### Scenario: Successful playback resets counter
- **WHEN** a song successfully starts playing (PLAYING state detected)
- **THEN** the `consecutiveSkips` counter SHALL be reset to 0

### Requirement: Failure logging
Each playback timeout failure SHALL be logged at WARN level with tag "PlaybackService" including: song title, artist, platform, platformSongId, and the string "playback timeout".

#### Scenario: Timeout failure logged
- **WHEN** a playback timeout occurs for a QQ Music song "Example Song" with platformSongId "abc123"
- **THEN** a log entry SHALL be written: `"Playback timeout: Example Song (qqmusic/abc123) - skipping"`

### Requirement: Controller mode exclusion
Playback timeout detection SHALL NOT run when the app is in controller mode (`RemoteMode.isController() == true`), since MediaMonitorService runs on the player phone, not the controller.

#### Scenario: Controller mode skips timeout
- **WHEN** a song is launched in controller mode
- **THEN** no playback timeout SHALL be scheduled

### Requirement: Bilibili exclusion
Playback timeout detection SHALL apply to Bilibili songs, consistent with the inclusion of Bilibili in MediaMonitorService monitoring. The previous exclusion is removed.

#### Scenario: Bilibili song launched
- **WHEN** a Bilibili song is launched
- **THEN** a playback timeout SHALL be scheduled (same as NetEase and QQ Music songs)

#### Scenario: Bilibili song fails to play within timeout
- **WHEN** a Bilibili song is launched AND no PLAYING state is detected within 15 seconds
- **THEN** the system SHALL auto-skip to the next song with toast "跳过: {title} (播放超时)"

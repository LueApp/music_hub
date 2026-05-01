## Requirements

### Requirement: Playback timeout detection
After launching a song via deep link, PlaybackService SHALL schedule a timeout check. The timeout duration SHALL be determined by the following rules in priority order:

1. If `DeepLinkLauncher.landscapeWorkaroundActive` is true AND the target platform is NetEase → use `PLAYBACK_TIMEOUT_COLD_MS` (landscape workaround uses CLEAR_TASK, making warm state irrelevant)
2. If the launch is a cross-platform switch (`isPlatformSwitch == true`) → use `PLAYBACK_TIMEOUT_COLD_MS` (cross-platform switches need extra time for pause/stop + new deep link processing)
3. If the target platform is Bilibili and it has an active MediaController → use `PLAYBACK_TIMEOUT_COLD_MS` (Bilibili can keep a MediaSession alive while video-page deep links still load slowly)
4. If the target app has an active MediaController → use `PLAYBACK_TIMEOUT_WARM_MS` (warm start)
5. Otherwise → use `PLAYBACK_TIMEOUT_COLD_MS` (cold start)

If no active playback (PLAYING state with correct song title) is detected when the timeout fires, the system SHALL treat the song as a playback failure.

#### Scenario: NetEase with landscape workaround uses cold timeout
- **WHEN** a NetEase song is launched AND `DeepLinkLauncher.landscapeWorkaroundActive` is true AND NetEase has an active MediaController
- **THEN** the timeout SHALL be `PLAYBACK_TIMEOUT_COLD_MS` (not warm) AND the log SHALL indicate "cold start (landscape workaround)"

#### Scenario: Cross-platform switch uses cold timeout
- **WHEN** a QQ Music song is launched after a NetEase song (isPlatformSwitch=true) AND QQ Music has an active MediaController
- **THEN** the timeout SHALL be `PLAYBACK_TIMEOUT_COLD_MS` (not warm) AND the log SHALL indicate "cold start (cross-platform)"

#### Scenario: Bilibili warm controller uses extended timeout
- **WHEN** a Bilibili song is launched AND Bilibili has an active MediaController AND this is not a cross-platform switch
- **THEN** the timeout SHALL be `PLAYBACK_TIMEOUT_COLD_MS` (not warm) AND the log SHALL indicate "extended start (bilibili)"

#### Scenario: Same-platform warm start unchanged
- **WHEN** a QQ Music song is launched after another QQ Music song AND QQ Music has an active MediaController AND no landscape workaround is active
- **THEN** the timeout SHALL be `PLAYBACK_TIMEOUT_WARM_MS` (warm start, unchanged behavior)

#### Scenario: Song fails to play within timeout
- **WHEN** a song is launched via deep link AND no PLAYING state is detected within the timeout period
- **THEN** the system SHALL log the failure with song title, platform, platformSongId, and "playback timeout" reason AND show a toast "跳过: {title} (播放超时)" AND auto-skip to the next song

#### Scenario: Song starts playing before timeout
- **WHEN** a song is launched via deep link AND PLAYING state with matching title is detected before the timeout fires
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
- **WHEN** a song successfully starts playing (PLAYING state detected with correct title)
- **THEN** the `consecutiveSkips` counter SHALL be reset to 0

### Requirement: Failure logging
Each playback timeout failure SHALL be logged at WARN level with tag "PlaybackService" including: song title, artist, platform, platformSongId, timeout reason (cold/warm/landscape/cross-platform), and the skip count.

#### Scenario: Timeout failure with reason logged
- **WHEN** a playback timeout occurs for a NetEase song during landscape workaround
- **THEN** a log entry SHALL include the timeout type: "cold start (landscape workaround)"

### Requirement: Controller mode exclusion
Playback timeout detection SHALL NOT run when the app is in controller mode (`RemoteMode.isController() == true`), since MediaMonitorService runs on the player phone, not the controller.

#### Scenario: Controller mode skips timeout
- **WHEN** a song is launched in controller mode
- **THEN** no playback timeout SHALL be scheduled

### Requirement: Bilibili exclusion
Playback timeout detection SHALL apply to Bilibili songs, consistent with the inclusion of Bilibili in MediaMonitorService monitoring.

#### Scenario: Bilibili song fails to play within timeout
- **WHEN** a Bilibili song is launched AND no PLAYING state is detected within the timeout period
- **THEN** the system SHALL auto-skip to the next song with toast "跳过: {title} (播放超时)"

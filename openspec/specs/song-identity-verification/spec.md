## Requirements

### Requirement: Song title exposed in PlaybackInfo
MediaMonitorService.PlaybackInfo SHALL include a `title` field containing the current MediaSession metadata title. This allows callers to verify which song is actually playing.

#### Scenario: PlaybackInfo includes title from active session
- **WHEN** QQ Music is playing "不吐不快" and PlaybackService calls `getPlaybackInfo()`
- **THEN** the returned PlaybackInfo SHALL have `title = "不吐不快"` along with existing fields

#### Scenario: No active session returns null
- **WHEN** no MediaSession is active
- **THEN** `getPlaybackInfo()` SHALL return null (unchanged behavior)

### Requirement: Timeout check verifies song identity
When the playback timeout fires and detects active playback (`isPlaying == true`), PlaybackService SHALL compare the MediaSession title against the expected song title. If the titles do not match, the timeout SHALL treat this as a failure (wrong song playing).

#### Scenario: Correct song is playing
- **WHEN** timeout fires for "我有时觉得" AND MediaSession title contains "我有时觉得"
- **THEN** the timeout check SHALL pass and reset `consecutiveSkips` to 0

#### Scenario: Wrong song is playing (desync detected)
- **WHEN** timeout fires for "我有时觉得" AND MediaSession title is "不吐不快"
- **THEN** the timeout check SHALL treat this as a timeout failure AND log a warning including both expected and actual titles

#### Scenario: Title comparison uses contains matching
- **WHEN** timeout fires for "千千阕歌" AND MediaSession title is "千千阕歌 (Live Version)"
- **THEN** the timeout check SHALL pass (expected title is contained in actual title)

### Requirement: Desync recovery after timeout skip
When a playback timeout triggers a skip, PlaybackService SHALL store the timed-out song's title. If MediaMonitorService subsequently detects that the timed-out song starts playing (metadata title matches), PlaybackService SHALL pause the wrong song and re-launch the current (correct) song's deep link.

#### Scenario: Timed-out song starts playing late
- **WHEN** "不吐不快" times out and is skipped to "我有时觉得" AND QQ Music subsequently reports metadata title "不吐不快" with state PLAYING
- **THEN** PlaybackService SHALL pause QQ Music AND re-launch the deep link for "我有时觉得"

#### Scenario: Recovery clears after successful playback
- **WHEN** desync recovery fires and the correct song starts playing successfully
- **THEN** the `lastTimedOutSongTitle` SHALL be cleared to prevent further recovery attempts

#### Scenario: Recovery does not trigger for normal song changes
- **WHEN** a song naturally finishes and the next song's title differs from any timed-out title
- **THEN** no desync recovery SHALL be triggered

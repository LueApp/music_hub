## ADDED Requirements

### Requirement: Same-platform repeated pause for NetEase

When Music Hub detects a song ending via early song-end detection and the next song is on the same platform (NetEase → NetEase), MediaMonitorService SHALL schedule repeated pause attempts on the NetEase media controller to prevent NetEase's internal queue from auto-advancing before the deep link takes effect.

#### Scenario: Same-platform NetEase switch prevents auto-advance

- **WHEN** early song-end detection fires for a NetEase song and the next song in the Music Hub queue is also a NetEase song
- **THEN** MediaMonitorService schedules repeated pause commands on the NetEase media controller at intervals over a ~2s window after the initial pause

#### Scenario: Repeated pause does not interfere with newly launched song

- **WHEN** repeated pause is active and the new song's deep link has been launched
- **THEN** repeated pause attempts stop before the new song begins playback, preventing the new song from being paused

#### Scenario: Non-NetEase same-platform switches are unaffected

- **WHEN** a same-platform switch occurs for QQ Music or Bilibili
- **THEN** the existing behavior (no repeated pause for same-platform) is preserved

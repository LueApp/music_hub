## MODIFIED Requirements

### Requirement: Bilibili exclusion
Playback timeout detection SHALL apply to Bilibili songs, consistent with the inclusion of Bilibili in MediaMonitorService monitoring. The previous exclusion is removed.

#### Scenario: Bilibili song launched
- **WHEN** a Bilibili song is launched
- **THEN** a playback timeout SHALL be scheduled (same as NetEase and QQ Music songs)

#### Scenario: Bilibili song fails to play within timeout
- **WHEN** a Bilibili song is launched AND no PLAYING state is detected within 15 seconds
- **THEN** the system SHALL auto-skip to the next song with toast "跳过: {title} (播放超时)"

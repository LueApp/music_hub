## ADDED Requirements

### Requirement: Detect foreground app package name
The system SHALL detect which app is currently in the foreground.

#### Scenario: Music Hub is in the foreground
- **WHEN** system queries the foreground app while Music Hub is active
- **THEN** system returns "com.musichub" as the foreground package name

#### Scenario: Platform app is in the foreground
- **WHEN** system queries the foreground app while NetEase/QQ Music/Bilibili is active
- **THEN** system returns the platform app's package name (com.netease.cloudmusic, com.tencent.qqmusic, or tv.danmaku.bili)

#### Scenario: Other app is in the foreground
- **WHEN** system queries the foreground app while a third-party app is active
- **THEN** system returns that app's package name

### Requirement: Usage stats permission handling
The system SHALL request PACKAGE_USAGE_STATS permission when foreground app detection is needed.

#### Scenario: Permission not granted
- **WHEN** system attempts to detect foreground app AND PACKAGE_USAGE_STATS permission is not granted
- **THEN** system directs user to Settings to grant the permission

#### Scenario: Permission granted
- **WHEN** system attempts to detect foreground app AND PACKAGE_USAGE_STATS permission is granted
- **THEN** system successfully queries the foreground app using UsageStatsManager

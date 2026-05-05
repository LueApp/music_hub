## ADDED Requirements

### Requirement: Check target app readiness for timeout selection
The system SHALL check whether the target music app has an active MediaController in MediaMonitorService before selecting the playback timeout duration.

#### Scenario: Target app has active MediaController (warm start)
- **WHEN** a song is launched AND MediaMonitorService has an active MediaController for the target package
- **THEN** the playback timeout SHALL be 5 seconds

#### Scenario: Target app has no active MediaController (cold start)
- **WHEN** a song is launched AND MediaMonitorService does NOT have an active MediaController for the target package
- **THEN** the playback timeout SHALL be 15 seconds

### Requirement: MediaMonitorService exposes controller presence check
MediaMonitorService SHALL provide a method to check whether an active MediaController exists for a given package name.

#### Scenario: Query for existing controller
- **WHEN** `hasActiveController(packageName)` is called with a package that has a registered MediaController
- **THEN** the method returns true

#### Scenario: Query for non-existing controller
- **WHEN** `hasActiveController(packageName)` is called with a package that has no registered MediaController
- **THEN** the method returns false

### Requirement: Remove landscape-specific timeout override
The existing landscape-specific timeout logic (20s for NetEase + landscape) SHALL be replaced by the generic cold/warm start detection. The `isLandscapeForTimeout` check in `schedulePlaybackTimeout` SHALL be removed.

#### Scenario: NetEase in landscape uses cold-start detection
- **WHEN** a NetEase song is launched in landscape mode (CLEAR_TASK clears the activity stack)
- **THEN** the timeout is determined by MediaController presence, not by a hardcoded landscape check

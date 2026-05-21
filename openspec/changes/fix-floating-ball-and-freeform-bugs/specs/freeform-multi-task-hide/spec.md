## ADDED Requirements

### Requirement: All Tutti-launched freeform tasks stay off-screen across HOME and switch transitions

When background launch mode is active, `ShizukuLauncher` SHALL maintain off-screen freeform bounds for **every** music-app package whose freeform task Tutti has launched in the current session — not only for the package currently held in `currentTargetPkg`. After a HOME gesture, Recents dismissal, or platform switch, every such task SHALL be re-resized off-screen within ≤500 ms of becoming visible on the home/launcher surface.

The off-screen-bounds memory SHALL be keyed by package name and SHALL persist for the lifetime of the foreground service, so that a NetEase task launched earlier in the session can still be re-hidden after the user has switched to QQ Music.

#### Scenario: HOME gesture hides all prior platform tasks
- **GIVEN** the user has played a NetEase song, then switched to QQ Music
- **AND** both `com.netease.cloudmusic` and `com.tencent.qqmusic` have freeform tasks
- **WHEN** the user triggers a HOME gesture from the QQ Music freeform area
- **THEN** within 500 ms neither freeform task is visible on the home screen; both tasks have bounds that place them fully off-screen (e.g. `left >= screenWidth`)

#### Scenario: Stale-platform task re-hides itself when HyperOS surfaces it
- **GIVEN** a prior-platform freeform task (e.g. NetEase) is currently off-screen
- **AND** the active target package is now QQ Music
- **WHEN** HyperOS pulls the NetEase freeform task back into a visible square window (gesture-nav or recents transition)
- **THEN** `PlayerAccessibilityService` observes the `TYPE_WINDOWS_CHANGED` event for `com.netease.cloudmusic` and triggers an off-screen resize of that task — even though `currentTargetPkg != com.netease.cloudmusic`

#### Scenario: triggerResize is not gated on currentTargetPkg
- **WHEN** `ShizukuLauncher.triggerResize(pkg)` is called for any music-app package that has a known off-screen bounds entry
- **THEN** the call proceeds to resize the freeform task for `pkg`, regardless of whether `pkg == currentTargetPkg`

#### Scenario: Switching platforms does not orphan the prior task
- **GIVEN** NetEase has a freeform task created in this session
- **WHEN** `PlaybackService` launches a song on QQ Music (a different platform)
- **THEN** the NetEase entry in the off-screen-bounds memory is retained, and the NetEase task either (a) remains off-screen, or (b) is re-hidden within 500 ms by the accessibility-driven path if it resurfaces

### Requirement: Single-target watchdog removed or generalised

The watchdog loop in `ShizukuLauncher` SHALL either (a) iterate over every package in the off-screen-bounds memory each tick, OR (b) be replaced by event-driven re-hides from `PlayerAccessibilityService`. A watchdog that exits because `currentTargetPkg` was bumped MUST NOT leave the prior package without a re-hide source.

#### Scenario: Generation bump does not strand prior package
- **WHEN** `scheduleResize()` is called for a new package, bumping `resizeGeneration`
- **THEN** there is still at least one active mechanism (watchdog iteration or accessibility event) that can re-hide the prior package's task if it resurfaces

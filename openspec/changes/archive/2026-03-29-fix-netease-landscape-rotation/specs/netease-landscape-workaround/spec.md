## ADDED Requirements

### Requirement: Landscape detection before NetEase launch
The system SHALL detect if the device is in landscape orientation before launching a NetEase deep link. If in landscape, the system SHALL use the landscape-aware launch sequence instead of the normal launch path.

#### Scenario: Device in landscape, launching NetEase song
- **WHEN** a NetEase song is launched AND the device orientation is landscape
- **THEN** the system uses the landscape-aware launch sequence (force portrait → CLEAR_TASK launch → event-driven rotation restore)

#### Scenario: Device in portrait, launching NetEase song
- **WHEN** a NetEase song is launched AND the device orientation is portrait
- **THEN** the system uses the normal launch path without rotation manipulation or CLEAR_TASK

#### Scenario: Launching QQ Music or Bilibili song in landscape
- **WHEN** a QQ Music or Bilibili song is launched AND the device orientation is landscape
- **THEN** the system uses the normal launch path (no rotation workaround applied)

### Requirement: Force portrait before landscape-aware launch
When the landscape-aware launch sequence is triggered, the system SHALL force the device into portrait orientation before sending the deep link intent. This MUST be done by setting `ACCELEROMETER_ROTATION=0` and `USER_ROTATION=0` via `Settings.System`.

#### Scenario: Portrait rotation forced before deep link
- **WHEN** the landscape-aware launch sequence starts
- **THEN** `ACCELEROMETER_ROTATION` is set to `0` (disabled) AND `USER_ROTATION` is set to `0` (portrait) before the deep link intent is sent

#### Scenario: WRITE_SETTINGS permission not granted
- **WHEN** the landscape-aware launch sequence starts AND `WRITE_SETTINGS` permission is not granted
- **THEN** the system SHALL fall back to the normal launch path without rotation manipulation

### Requirement: CLEAR_TASK flag for fresh activity creation
When using the landscape-aware launch sequence, the deep link intent SHALL include `FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK` to force NetEase to create a fresh `PlayerActivity` with a newly registered `OrientationEventListener`.

#### Scenario: Deep link sent with CLEAR_TASK in landscape mode
- **WHEN** the landscape-aware launch sequence sends the deep link intent
- **THEN** the intent includes both `FLAG_ACTIVITY_CLEAR_TASK` and `FLAG_ACTIVITY_NEW_TASK` flags

#### Scenario: Normal launch does not use CLEAR_TASK
- **WHEN** a NetEase song is launched in portrait orientation
- **THEN** the intent uses only `FLAG_ACTIVITY_NEW_TASK` (no `CLEAR_TASK`)

### Requirement: Event-driven auto-rotation restore
After launching the deep link in landscape-aware mode, the system SHALL restore auto-rotation when NetEase's `MediaController` reports `STATE_PLAYING`. This indicates the player is loaded and the `OrientationEventListener` is registered.

#### Scenario: NetEase starts playing after landscape-aware launch
- **WHEN** the deep link is launched with landscape-aware mode AND NetEase's MediaController transitions to `STATE_PLAYING`
- **THEN** the system restores `ACCELEROMETER_ROTATION=1` (auto-rotation enabled)

#### Scenario: Playback not detected within timeout
- **WHEN** the deep link is launched with landscape-aware mode AND NetEase does not report `STATE_PLAYING` within 15 seconds
- **THEN** the system restores `ACCELEROMETER_ROTATION=1` as a safety fallback

### Requirement: Replace existing toggleAutoRotate
The existing `toggleAutoRotate()` method in `DeepLinkLauncher` SHALL be replaced by the new landscape-aware launch sequence. The old toggle-based approach (disable auto-rotate for 200ms after 1s delay) SHALL be removed.

#### Scenario: Old toggleAutoRotate no longer called
- **WHEN** a NetEase deep link is launched
- **THEN** the old `toggleAutoRotate()` method is NOT called; the new landscape detection and rotation sequence is used instead

### Requirement: NetEase double-send compatibility
The existing NetEase-to-NetEase double-send logic (second deep link after 2 seconds with `skipAutoRotate=true`) SHALL NOT use `CLEAR_TASK` on the second send. Only the first launch uses the landscape-aware sequence.

#### Scenario: Second deep link in double-send
- **WHEN** a same-platform NetEase-to-NetEase transition triggers the double-send logic
- **THEN** the first send uses the landscape-aware sequence (if in landscape) AND the second send uses `FLAG_ACTIVITY_NEW_TASK` only (no `CLEAR_TASK`, no rotation manipulation)

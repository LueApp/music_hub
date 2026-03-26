## ADDED Requirements

### Requirement: Force orientation detection on NetEase deep link launch

When launching a NetEase Cloud Music deep link (`orpheus://` scheme), the system SHALL set `Settings.System.USER_ROTATION` to match the device's current physical rotation and temporarily disable `ACCELEROMETER_ROTATION`, then re-enable auto-rotate after a stabilization period. This forces NetEase's activity to receive the correct orientation configuration on start.

#### Scenario: Device in landscape, launch NetEase deep link
- **WHEN** the device is physically in landscape orientation (rotation 90 or 270) and a NetEase deep link is launched
- **THEN** the system SHALL set `USER_ROTATION` to the current device rotation, disable `ACCELEROMETER_ROTATION` for 500ms, then restore both `ACCELEROMETER_ROTATION` and `USER_ROTATION` to their original values

#### Scenario: Device in portrait, launch NetEase deep link
- **WHEN** the device is physically in portrait orientation (rotation 0 or 180) and a NetEase deep link is launched
- **THEN** the system SHALL skip the orientation toggle entirely (no system settings are modified)

#### Scenario: Auto-rotate disabled by user
- **WHEN** the user has manually disabled auto-rotate (`ACCELEROMETER_ROTATION` = 0) and a NetEase deep link is launched
- **THEN** the system SHALL skip the orientation toggle (do not modify user's auto-rotate preference)

### Requirement: Restore system settings after orientation toggle

After the orientation toggle completes (or if an error occurs during the toggle), the system SHALL restore `USER_ROTATION` and `ACCELEROMETER_ROTATION` to their original values.

#### Scenario: Successful toggle and restore
- **WHEN** the orientation toggle sequence completes (USER_ROTATION set, auto-rotate disabled, stabilization delay elapsed)
- **THEN** the system SHALL restore `ACCELEROMETER_ROTATION` to 1 (enabled) and `USER_ROTATION` to the value it held before the toggle

#### Scenario: Error during toggle
- **WHEN** an exception occurs while modifying system settings during the toggle
- **THEN** the system SHALL attempt to restore both `ACCELEROMETER_ROTATION` and `USER_ROTATION` to their original values and log the error

### Requirement: WRITE_SETTINGS permission guard

The orientation toggle SHALL only execute when `Settings.System.canWrite()` returns true. If the permission is not granted, the toggle SHALL be skipped with a warning log.

#### Scenario: WRITE_SETTINGS not granted
- **WHEN** `Settings.System.canWrite()` returns false and a NetEase deep link is launched
- **THEN** the system SHALL log a warning and skip the orientation toggle without affecting the deep link launch

### Requirement: Non-NetEase platforms unaffected

The orientation toggle SHALL only apply to `orpheus://` deep links. Deep links for QQ Music (`qqmusic://`) and Bilibili (`bilibili://`) SHALL NOT trigger the orientation toggle.

#### Scenario: QQ Music deep link launch in landscape
- **WHEN** a QQ Music deep link is launched while the device is in landscape orientation
- **THEN** the system SHALL NOT modify `USER_ROTATION` or `ACCELEROMETER_ROTATION`

#### Scenario: Bilibili deep link launch in landscape
- **WHEN** a Bilibili deep link is launched while the device is in landscape orientation
- **THEN** the system SHALL NOT modify `USER_ROTATION` or `ACCELEROMETER_ROTATION`

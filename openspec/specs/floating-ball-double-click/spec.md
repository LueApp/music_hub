## ADDED Requirements

### Requirement: Double-click gesture detection
The floating mini ball SHALL detect double-click gestures from the user.

#### Scenario: User double-clicks the floating ball
- **WHEN** user taps the floating ball twice within 300ms
- **THEN** system triggers the double-click navigation action

#### Scenario: User single-clicks the floating ball
- **WHEN** user taps the floating ball once
- **THEN** system maintains existing single-click behavior (no change)

#### Scenario: User taps with delay between clicks
- **WHEN** user taps the floating ball twice with more than 300ms between taps
- **THEN** system treats them as two separate single-click events

### Requirement: Context-aware navigation from Tutti
The system SHALL navigate to the currently playing platform app when user double-clicks the floating ball while Tutti is in the foreground.

#### Scenario: Double-click while in Tutti with active song
- **WHEN** user double-clicks the floating ball while Tutti is in the foreground AND a song is currently playing
- **THEN** system launches the platform app (NetEase/QQ Music/Bilibili) using the existing deep link mechanism

#### Scenario: Double-click while in Tutti with no active song
- **WHEN** user double-clicks the floating ball while Tutti is in the foreground AND no song is playing
- **THEN** system takes no action (no navigation occurs)

### Requirement: Context-aware navigation from platform app
The system SHALL navigate back to Tutti when user double-clicks the floating ball while the currently playing platform app is in the foreground.

#### Scenario: Double-click while in the playing platform app
- **WHEN** user double-clicks the floating ball while the currently playing platform app (NetEase/QQ Music/Bilibili) is in the foreground
- **THEN** system launches Tutti and brings it to the foreground

### Requirement: Context-aware navigation from other apps
The system SHALL navigate to the currently playing platform app when user double-clicks the floating ball while any other app (not Tutti, not the playing platform) is in the foreground.

#### Scenario: Double-click while in a third-party app
- **WHEN** user double-clicks the floating ball while a third-party app is in the foreground AND a song is currently playing
- **THEN** system launches the platform app using the existing deep link mechanism

#### Scenario: Double-click while in a different platform app
- **WHEN** user double-clicks the floating ball while in QQ Music BUT NetEase is the currently playing platform
- **THEN** system launches NetEase using the existing deep link mechanism

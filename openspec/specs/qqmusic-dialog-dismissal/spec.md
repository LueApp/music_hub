## ADDED Requirements

### Requirement: Dismiss QQ Music error dialog on playback timeout
When a playback timeout triggers auto-skip for a QQ Music song, the system SHALL attempt to dismiss any QQ Music error dialog before launching the next song.

#### Scenario: Copyright dialog dismissed via close button
- **WHEN** a QQ Music song triggers a playback timeout AND QQ Music is showing a dialog with a close button (`com.tencent.qqmusic:id/close_btn`)
- **THEN** PlayerAccessibilityService SHALL click the close button to dismiss the dialog

#### Scenario: Dialog dismissed via BACK fallback
- **WHEN** a QQ Music song triggers a playback timeout AND QQ Music is showing a dialog but the close button is not found
- **THEN** PlayerAccessibilityService SHALL call `performGlobalAction(GLOBAL_ACTION_BACK)` to dismiss the dialog

#### Scenario: No dialog present
- **WHEN** a QQ Music song triggers a playback timeout AND QQ Music is not showing a dialog (no close button found, no blocking UI)
- **THEN** PlayerAccessibilityService SHALL perform BACK as a safe fallback, which has no harmful effect when no dialog is present

#### Scenario: Non-QQ-Music song timeout
- **WHEN** a NetEase or Bilibili song triggers a playback timeout
- **THEN** no dialog dismissal SHALL be attempted (QQ Music dialog dismissal is platform-specific)

### Requirement: Accessibility service not available
Dialog dismissal SHALL gracefully no-op when PlayerAccessibilityService is not running. The auto-skip SHALL proceed without dismissal.

#### Scenario: Service not enabled
- **WHEN** a QQ Music playback timeout triggers AND PlayerAccessibilityService is not running
- **THEN** the system SHALL skip dialog dismissal and proceed directly to launching the next song

### Requirement: Delay between dismiss and next launch
After dismissing a dialog, the system SHALL wait briefly (500ms) before launching the next song's deep link, to allow the dialog close animation to complete.

#### Scenario: Dismiss then launch with delay
- **WHEN** a dialog is dismissed AND the next song is ready to launch
- **THEN** the system SHALL wait 500ms after the dismiss action before calling `playNext()`

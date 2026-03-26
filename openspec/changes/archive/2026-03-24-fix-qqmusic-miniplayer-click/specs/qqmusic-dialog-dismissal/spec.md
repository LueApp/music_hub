## MODIFIED Requirements

### Requirement: Dismiss QQ Music error dialog on playback timeout
When a playback timeout triggers auto-skip for a QQ Music song, the system SHALL attempt to dismiss any QQ Music error dialog before launching the next song. The close button resource ID SHALL be verified against the current QQ Music version and updated if changed.

#### Scenario: Copyright dialog dismissed via close button
- **WHEN** a QQ Music song triggers a playback timeout AND QQ Music is showing a dialog with a close button (using the current verified resource ID for `close_btn`)
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

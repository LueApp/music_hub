## ADDED Requirements

### Requirement: Double-tap queue item to play
The floating window queue SHALL allow users to double-tap a song item to jump to and play that song. A double-tap is defined as two taps on the same item within 300ms.

#### Scenario: Double-tap a song in standalone/player mode
- **WHEN** the user double-taps a song item in the floating window queue while in standalone or player mode
- **THEN** the system SHALL call `PlaybackService.playAtIndex()` with the actual queue index of the tapped song, launching playback of that song

#### Scenario: Double-tap a song in controller mode
- **WHEN** the user double-taps a song item in the floating window queue while in controller (remote) mode
- **THEN** the system SHALL call `RemoteClient.playAtIndex()` with the actual queue index, sending the play command to the remote player device

#### Scenario: Double-tap respects shuffle order mapping
- **WHEN** shuffle mode is enabled and the user double-taps a song in the queue
- **THEN** the system SHALL use the actual queue index (as already remapped by QueueAdapter from display position), not the display position

#### Scenario: Single tap does not trigger playback
- **WHEN** the user taps a song item once without a second tap within 300ms
- **THEN** the system SHALL NOT start playback of that song

### Requirement: Visual feedback on first tap
The floating window queue SHALL provide visual feedback on the first tap to indicate the item is ready for a second tap to play.

#### Scenario: First tap highlights the item
- **WHEN** the user taps a song item in the floating window queue
- **THEN** the item SHALL briefly display a highlight (background color flash) to indicate it has been tapped

#### Scenario: Highlight clears after timeout
- **WHEN** the user taps a song item but does not tap again within 300ms
- **THEN** the highlight SHALL clear automatically

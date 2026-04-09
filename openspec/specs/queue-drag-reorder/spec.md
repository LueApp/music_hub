## ADDED Requirements

### Requirement: Long-press drag to reorder queue items
The floating window queue SHALL allow users to long-press a song item and drag it to a new position to reorder the playback queue.

#### Scenario: Drag a song to a new position in standalone/player mode
- **WHEN** the user long-presses a queue item and drags it to a different position while in standalone or player mode
- **THEN** the system SHALL call `PlaybackService.moveInQueue(fromIndex, toIndex)` to move the song in the queue, and the queue display SHALL update to reflect the new order

#### Scenario: Drag a song to a new position in controller mode
- **WHEN** the user long-presses a queue item and drags it to a different position while in controller (remote) mode
- **THEN** the system SHALL call `RemoteClient.moveInQueue(fromIndex, toIndex)` to send the reorder command to the remote player

#### Scenario: Currently playing song is moved
- **WHEN** the user drags the currently playing song to a new position
- **THEN** the system SHALL update `currentIndex` to the new position so playback continues uninterrupted and the correct song remains highlighted

#### Scenario: A song is moved past the currently playing song
- **WHEN** the user drags a song from before the current song to after it (or vice versa)
- **THEN** the system SHALL adjust `currentIndex` accordingly so the currently playing song's index remains correct

#### Scenario: Drag is disabled during shuffle mode
- **WHEN** shuffle mode is enabled and the user attempts to long-press drag a queue item
- **THEN** the system SHALL NOT allow the drag and SHALL display a brief message indicating reorder is not available during shuffle mode

### Requirement: Visual feedback during drag
The floating window queue SHALL provide visual feedback while a song item is being dragged.

#### Scenario: Item elevation during drag
- **WHEN** the user is actively dragging a queue item
- **THEN** the dragged item SHALL be visually elevated above other items to indicate it is being moved

#### Scenario: Item returns to normal on drop
- **WHEN** the user releases the dragged item
- **THEN** the item SHALL return to its normal visual state at the new position

### Requirement: PlaybackService queue reorder API
`PlaybackService` SHALL provide a `moveInQueue(from: Int, to: Int)` method for reordering the playback queue.

#### Scenario: Valid move within bounds
- **WHEN** `moveInQueue(from, to)` is called with valid indices within the queue
- **THEN** the song at position `from` SHALL be removed and inserted at position `to`, and all queue change listeners SHALL be notified

#### Scenario: Invalid indices
- **WHEN** `moveInQueue(from, to)` is called with out-of-bounds indices
- **THEN** the method SHALL do nothing (no crash, no state change)

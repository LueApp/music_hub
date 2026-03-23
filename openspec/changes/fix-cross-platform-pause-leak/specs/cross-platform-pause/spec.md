## ADDED Requirements

### Requirement: Reactive re-pause on old platform playback during cross-platform switch

During a cross-platform song transition, the MediaMonitorService SHALL immediately pause the old platform's media controller when it detects the old platform entering PLAYING or BUFFERING state, without waiting for the next scheduled re-pause timer tick.

#### Scenario: NetEase auto-advances to next song after being paused for cross-platform switch
- **WHEN** Music Hub is switching from NetEase to QQ Music, and NetEase auto-advances to its next song (state transitions to PLAYING at position=0)
- **THEN** MediaMonitorService SHALL immediately send a pause command to NetEase's media controller within the same callback invocation, before the user hears the auto-advanced song

#### Scenario: Old platform does not auto-advance
- **WHEN** Music Hub switches platforms and the old platform stays paused/stopped
- **THEN** No additional pause commands SHALL be sent to the old platform beyond the initial pause and scheduled re-pauses

### Requirement: Re-pause SHALL only use pause transport command

The `pauseSpecificPackage` re-pause mechanism SHALL only send `pause()` to the old platform's transport controls. It SHALL NOT send `stop()`, to avoid interfering with other active media sessions.

#### Scenario: Re-pause does not affect new platform's playback
- **WHEN** a re-pause fires for the old platform during a cross-platform switch
- **THEN** the new platform's media session SHALL NOT be paused or stopped as a side effect

### Requirement: Scheduled re-pauses retained as safety net

The scheduled timer-based re-pauses (at 500ms intervals) SHALL continue to run alongside the reactive re-pause, as a fallback in case the playback state callback is delayed or missed.

#### Scenario: Reactive re-pause fires before scheduled re-pause
- **WHEN** the old platform starts playing and the reactive callback fires before the next scheduled re-pause
- **THEN** the scheduled re-pause SHALL detect that the old platform is already paused and skip sending a redundant pause command

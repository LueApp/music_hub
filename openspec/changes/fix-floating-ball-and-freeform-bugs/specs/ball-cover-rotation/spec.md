## ADDED Requirements

### Requirement: Album cover rotates while bound song is playing

The floating-ball album-cover ImageView (`ivBallCover` in `floating_ball.xml`) SHALL rotate continuously around its centre at a fixed angular speed whenever the bound playback session (the song that `PlaybackService` most recently launched) is in `PlaybackState.STATE_PLAYING`. Rotation SHALL be sourced from a running `ObjectAnimator` (or equivalent) that owns the `View.ROTATION` property of the cover view, never from a canvas-redraw loop driven by the progress poller.

#### Scenario: Cover starts rotating on PLAYING transition
- **WHEN** the bound MediaController for the currently-launched platform reports a state transition from any non-PLAYING state to `STATE_PLAYING`
- **THEN** the cover ImageView's rotation animator is started (or resumed) within 500 ms and the cover visibly rotates

#### Scenario: Cover pauses rotation on PAUSED transition
- **WHEN** the bound MediaController reports a state transition from `STATE_PLAYING` to `STATE_PAUSED`, `STATE_STOPPED`, or `STATE_NONE`
- **THEN** the cover rotation freezes at its current angle within 500 ms and does not snap back to 0°

#### Scenario: Rotation survives pause/resume cycle
- **WHEN** the user pauses and then resumes playback of the same song
- **THEN** the cover continues rotating from the angle it held while paused (no visible angle jump on resume)

#### Scenario: Rotation continues across song change inside same platform
- **WHEN** the user advances to the next song on the same platform (e.g. NetEase → NetEase) and the new song reaches `STATE_PLAYING`
- **THEN** the cover ImageView keeps rotating; the new cover bitmap takes over the rotating view without resetting the animator

#### Scenario: Rotation does not run when no song is bound
- **WHEN** Tutti has no current song (queue empty, or floating window in idle state)
- **THEN** no rotation animator is running and the cover view is static at 0°

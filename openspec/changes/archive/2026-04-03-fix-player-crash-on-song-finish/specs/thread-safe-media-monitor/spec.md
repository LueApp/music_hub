## ADDED Requirements

### Requirement: Thread-safe access to active media controllers

`MediaMonitorService` SHALL synchronize all access to the `activeControllers` map using a dedicated lock object. Methods that read from `activeControllers` from any thread MUST take a snapshot (copy) of the map values under the lock and iterate the snapshot outside the lock.

#### Scenario: Concurrent read from broadcast thread during song transition

- **WHEN** `RemoteServer.buildCurrentState()` calls `MediaMonitorService.getPlaybackInfo()` from the IO/broadcast thread while the main thread is executing `onActiveSessionsChanged()` (adding/removing controllers during a song transition)
- **THEN** `getPlaybackInfo()` SHALL return a valid `PlaybackInfo` or `null` without throwing `ConcurrentModificationException`

#### Scenario: Concurrent read during pauseAllMedia

- **WHEN** `getPlaybackInfo()` is called from the broadcast thread while `pauseAllMedia()` is iterating and modifying controller state on the main thread
- **THEN** both operations SHALL complete without exception; `getPlaybackInfo()` SHALL return a consistent snapshot of controller state

#### Scenario: togglePlayPause from server thread dispatched to main

- **WHEN** `RemoteServer` handles a POST to `/api/play/pause` and dispatches `togglePlayPause()` to the main thread via `mainHandler.post{}`
- **THEN** `togglePlayPause()` SHALL acquire the controllers lock, take a snapshot of active controllers, and iterate the snapshot safely

### Requirement: Resilient WebSocket broadcast loop

The `RemoteServer` WebSocket broadcast loop SHALL NOT terminate due to transient exceptions from `buildCurrentState()` or `WebSocket.send()`. Individual broadcast failures MUST be caught and logged without stopping the loop.

#### Scenario: buildCurrentState throws during song transition

- **WHEN** `buildCurrentState()` throws any exception (e.g., the service instance becomes null mid-call, or an unexpected state occurs during a song transition)
- **THEN** the broadcast loop SHALL log the error and continue to the next 500ms broadcast cycle without crashing or stopping

#### Scenario: Continuous operation through multiple song transitions

- **WHEN** the player plays through a queue of songs in PLAYER mode with a connected controller
- **THEN** the WebSocket broadcast loop SHALL continue operating through all song transitions, providing state updates to the controller after each transition completes

### Requirement: Synchronized controller map mutations

All methods that add to, remove from, or iterate over `activeControllers` and `controllerCallbacks` maps MUST use the same lock object for synchronization. This includes `onActiveSessionsChanged()`, `stopMonitoring()`, `getPlaybackInfo()`, `togglePlayPause()`, `seekTo()`, `pauseAllMedia()`, `pauseSpecificPackage()`, and `pollCurrentPosition()`.

#### Scenario: Controller added while getPlaybackInfo iterates

- **WHEN** a new media session appears (triggering `onActiveSessionsChanged()` to add a controller to the map) while `getPlaybackInfo()` is iterating over a snapshot of the controllers
- **THEN** both operations SHALL complete without exception; `getPlaybackInfo()` SHALL use its pre-existing snapshot and the new controller SHALL be visible in subsequent calls

#### Scenario: Controller removed while seekTo iterates

- **WHEN** a media session is destroyed (triggering removal from `activeControllers`) while `seekTo()` is iterating a snapshot of controllers
- **THEN** `seekTo()` SHALL complete its iteration over the snapshot without exception; any `MediaController` method calls on the removed controller SHALL be caught by existing try-catch blocks

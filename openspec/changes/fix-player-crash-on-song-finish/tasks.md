## 1. Add thread-safe synchronization to MediaMonitorService

- [x] 1.1 Add `private val controllersLock = Any()` field to `MediaMonitorService` in `service/MediaMonitorService.kt`
- [x] 1.2 Wrap `activeControllers` and `controllerCallbacks` mutations in `onActiveSessionsChanged()` with `synchronized(controllersLock)` — both the removal loop and the add loop
- [x] 1.3 Wrap `activeControllers` and `controllerCallbacks` cleanup in `stopMonitoring()` with `synchronized(controllersLock)`
- [x] 1.4 Update `getPlaybackInfo()` to take a snapshot of `activeControllers.values.toList()` under `synchronized(controllersLock)`, then iterate the snapshot outside the lock
- [x] 1.5 Update `togglePlayPause()` to take a snapshot of `activeControllers.values.toList()` under `synchronized(controllersLock)`, then iterate the snapshot outside the lock
- [x] 1.6 Update `seekTo()` to take a snapshot of `activeControllers.values.toList()` under `synchronized(controllersLock)`, then iterate the snapshot outside the lock
- [x] 1.7 Update `pauseAllMedia()` to use `synchronized(controllersLock)` when iterating `activeControllers`
- [x] 1.8 Update `pauseSpecificPackage()` to use `synchronized(controllersLock)` when accessing `activeControllers[packageName]`
- [x] 1.9 Update `pollCurrentPosition()` to take a snapshot of `activeControllers` entries under `synchronized(controllersLock)`, then iterate outside the lock

## 2. Make RemoteServer broadcast loop resilient

- [x] 2.1 In `RemoteServer.startBroadcasting()` (`remote/RemoteServer.kt`), move the existing try-catch inside the while loop to wrap `buildCurrentState()` individually, so a failure in state building doesn't prevent the `delay(500)` or kill the loop
- [x] 2.2 Add a `continue` after logging the error in the inner catch block to skip the send step when state building fails

## 3. Build verification

- [x] 3.1 Run `pixi run build` to verify the project compiles without errors
- [x] 3.2 Run `pixi run test` to verify unit tests pass

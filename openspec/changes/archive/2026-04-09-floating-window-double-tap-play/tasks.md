## 1. Implement double-tap detection and playback in FloatingWindowService

- [x] 1.1 In `FloatingWindowService.setupQueueView()`, replace the no-op `onItemClick` callback with double-tap detection logic: track `lastTapIndex` and `lastTapTime`, and if the same index is tapped within 300ms, treat as double-tap
- [x] 1.2 On double-tap, branch on `RemoteMode.isController()`: call `PlaybackService.getInstance()?.playAtIndex(index)` for local/player mode, or `RemoteClient.playAtIndex(index)` for controller mode
- [x] 1.3 Add visual feedback on first tap: briefly highlight the tapped item's background (e.g., flash primary color at low alpha), clearing after 300ms if no second tap

## 2. Add queue reorder API to PlaybackService

- [x] 2.1 Add `moveInQueue(from: Int, to: Int)` method to `PlaybackService` that removes the song at `from`, inserts at `to`, adjusts `currentIndex` if needed, and notifies queue change listeners
- [x] 2.2 Handle shuffle mode: update `shuffleOrder` indices when queue is reordered, or skip reorder if shuffle is active

## 3. Add remote support for queue reorder

- [x] 3.1 Add `moveInQueue(from, to)` endpoint to `RemoteServer` (HTTP POST)
- [x] 3.2 Add `moveInQueue(from, to)` method to `RemoteClient`

## 4. Implement drag-to-reorder in FloatingWindowService

- [x] 4.1 Attach an `ItemTouchHelper` with `SimpleCallback` to the queue `RecyclerView` in `FloatingWindowService.setupQueueView()`, enabling UP/DOWN drag (no swipe)
- [x] 4.2 In the `onMove` callback, update the adapter data and call `PlaybackService.moveInQueue()` or `RemoteClient.moveInQueue()` depending on mode
- [x] 4.3 Add visual feedback during drag: elevate the dragged item, restore on drop
- [x] 4.4 Disable drag when shuffle is enabled: check shuffle state in `isLongPressDragEnabled()` and show a toast if user attempts to drag

## 5. Build and verify

- [x] 5.1 Build the project with `pixi run build` and fix any compilation errors
- [ ] 5.2 Deploy to device and test: double-tap to play, drag to reorder, verify both work in the floating window queue

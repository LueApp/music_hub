## Context

The floating window's queue view (`RecyclerView` in `FloatingWindowService`) already renders song items via `QueueAdapter` with an `onItemClick` callback. However, the callback is currently a no-op (logs only, with a TODO comment). `PlaybackService.playAtIndex(index)` and `RemoteClient.playAtIndex(index)` already exist and are used by the main app's playlist fragments.

There is currently no queue reordering capability anywhere in the app — `PlaybackService` has no `moveInQueue` method, and no `ItemTouchHelper` is attached to any queue `RecyclerView`.

## Goals / Non-Goals

**Goals:**
- Enable double-tap on a queue item to jump to and play that song
- Enable long-press drag to reorder songs in the floating window queue
- Work in all three app modes: standalone, player, and controller
- Provide visual feedback on first tap and during drag

**Non-Goals:**
- Adding single-tap actions (e.g., showing song details)
- Adding long-press context menus
- Persisting queue reorder to the database (queue is transient)

## Decisions

### 1. Double-tap detection: Time-based in QueueAdapter vs GestureDetector

**Decision**: Use simple time-based double-tap detection directly in the `onItemClick` callback in `FloatingWindowService.setupQueueView()`.

Track `lastTapIndex` and `lastTapTime`. If the same index is tapped within 300ms, treat as double-tap.

**Why not GestureDetector**: GestureDetector requires attaching to `onTouchEvent` at the ViewHolder level, which adds complexity for a simple interaction. The time-based approach is simpler, self-contained, and matches the existing code style.

### 2. Visual feedback on first tap

**Decision**: Briefly highlight the tapped item's background on first tap (e.g., flash the primary color at low alpha for ~150ms) to indicate the item is "armed" for a second tap.

This is lightweight and doesn't require layout changes.

### 3. Playback invocation: Local vs Remote

**Decision**: Follow the existing pattern used by all other playback controls in `FloatingWindowService` — branch on `RemoteMode.isController()`:
- Local/Player mode: `PlaybackService.getInstance()?.playAtIndex(index)`
- Controller mode: `RemoteClient.playAtIndex(index)`

Both APIs already exist and are tested in the main app's playlist detail fragments.

### 4. Drag-to-reorder: ItemTouchHelper on the queue RecyclerView

**Decision**: Use Android's `ItemTouchHelper` with `ItemTouchHelper.SimpleCallback` for long-press-initiated drag. This is the standard Android approach for RecyclerView reordering.

- Enable `UP | DOWN` drag directions, no swipe
- Long-press triggers drag (default `ItemTouchHelper` behavior — no custom gesture needed)
- During drag, elevate the dragged item for visual feedback
- On drop, call `PlaybackService.moveInQueue(fromIndex, toIndex)` or `RemoteClient.moveInQueue(fromIndex, toIndex)`

**Why ItemTouchHelper over custom touch handling**: `ItemTouchHelper` handles all the edge cases (scroll-while-dragging, animation, proper ViewHolder recycling) that would be error-prone to implement manually.

### 5. PlaybackService.moveInQueue() implementation

**Decision**: Add a `moveInQueue(from: Int, to: Int)` method that:
1. Removes the song at `from` and inserts it at `to` (under `queueLock`)
2. Updates `currentIndex` if the currently playing song was affected by the move
3. Updates `shuffleOrder` if shuffle is enabled (remap indices)
4. Notifies queue change listeners

### 6. Interaction between drag and double-tap

**Decision**: Long-press initiates drag; short taps feed into double-tap detection. These don't conflict because `ItemTouchHelper` consumes the long-press, preventing it from reaching the click listener. A quick double-tap (two taps within 300ms) is too fast to trigger a long-press (~400ms threshold).

## Risks / Trade-offs

- **[Risk] Accidental double-tap**: Users scrolling the queue might accidentally trigger playback → **Mitigation**: 300ms window is tight enough to avoid accidental triggers during scrolling; the drag listener already handles scroll vs tap distinction.
- **[Risk] Index mismatch in shuffle mode**: The adapter remaps display position to actual queue index → **Mitigation**: `QueueAdapter.onItemClick` already returns the actual queue index (not display position), so no additional mapping needed. For drag-reorder, the `moveInQueue` method operates on actual queue indices.
- **[Risk] Drag in shuffle mode confusion**: Display order differs from queue order when shuffled → **Mitigation**: Disable drag-to-reorder when shuffle is enabled. Reordering a shuffled queue is confusing UX. Show a brief toast if user attempts to drag while shuffled.
- **[Trade-off] Double-tap vs single-tap**: Double-tap adds friction but reserves single-tap for future use. This matches the user's explicit request.
- **[Risk] Floating window touch conflicts**: The floating window has its own drag-to-move listener → **Mitigation**: The drag-to-reorder only applies to the `RecyclerView` items inside the queue container, which is a separate view from the window drag handle area.

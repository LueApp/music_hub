## Context

The remote control feature allows one phone (controller) to control music playback on another phone (player) over LAN. The player runs a NanoHTTPD server with REST API + WebSocket on port 8765. The controller connects via OkHttp HTTP client + WebSocket.

Currently, when the controller navigates to the home screen, playlists screen, or tries to view playback progress, it often shows empty/zero state. The root cause is twofold:
1. `RemoteClient.fetchList()` silently returns `emptyList()` on any HTTP failure — no error propagation, no retry, no user feedback.
2. `RemoteClient.connect()` makes a single WebSocket connection attempt with no auto-reconnect — a transient failure leaves the controller permanently disconnected.

The fragments (HomeFragment, PlaylistsFragment, PlaylistDetailFragment) in controller mode call `RemoteClient.fetchAllSongs()`, `fetchPlaylists()`, etc. inside a coroutine. On failure, the catch block logs to logcat but shows no user-visible feedback. The user sees an empty screen and has no idea why.

Only controller mode is affected. Standalone and player modes use local Room database queries via Flow and are unaffected.

## Goals / Non-Goals

**Goals:**
- Make remote data loading failures visible to the user with actionable feedback (Toast + retry)
- Auto-reconnect the WebSocket when it drops, so playback state continues flowing
- Show loading indicators during remote fetches so the user knows data is being loaded
- Add a connection status indicator so the user can tell if the controller is connected
- Align PlaylistDetailFragment with the same defensive guards as HomeFragment/PlaylistsFragment

**Non-Goals:**
- Changing the server-side (RemoteServer) REST API or WebSocket protocol
- Adding new remote control capabilities
- Persisting RemoteMode across app restarts
- Offline caching of remote data on the controller side
- Changing behavior in standalone or player modes

## Decisions

### Decision 1: Error propagation via exceptions (not Result type)

The `RemoteClient.fetchList()` currently catches all exceptions and returns `emptyList()`. Two options:

- **Option A: Return `Result<List<T>>`** — Callers check `isSuccess`/`isFailure`.
- **Option B: Let exceptions propagate** — Callers already have try-catch blocks.

**Choice: Option B.** The fragment-level try-catch blocks already exist in HomeFragment and PlaylistsFragment. We just need to: (1) remove the catch in `fetchList()` so exceptions propagate, and (2) show a Toast in the fragment-level catch blocks. This minimizes changes. `fetchList()` will still return `emptyList()` for empty successful responses but will throw on network/parse failures.

### Decision 2: WebSocket auto-reconnect with capped exponential backoff

Add reconnect logic directly in `RemoteClient`. On `onFailure` or `onClosed` (if not user-initiated via `disconnect()`), schedule a reconnect attempt after a delay.

- Initial delay: 1 second
- Max delay: 30 seconds
- Multiply by 2 on each failure
- Reset delay on successful connection
- Stop reconnecting when `disconnect()` is called explicitly or mode changes away from CONTROLLER

Implementation: Use `mainHandler.postDelayed()` for scheduling since `RemoteClient` already has a `Handler(Looper.getMainLooper())`.

**Alternatives considered:**
- OkHttp retry interceptor — doesn't apply to WebSocket connections
- External library (e.g., Scarlet) — adds a dependency for a simple problem

### Decision 3: Loading indicator via ProgressBar in existing layouts

Add a `ProgressBar` (indeterminate spinner) to `fragment_home.xml`, `fragment_playlists.xml`, and `fragment_playlist_detail.xml`. In controller mode, show the spinner before the remote fetch, hide it after success or failure.

No new layout files needed — just add a `<ProgressBar>` to each existing layout XML and toggle visibility.

### Decision 4: Connection status via a small banner in MainActivity

Add a `TextView` banner below the toolbar (or above the now-playing bar) that shows "连接中..." / "已断开 - 重试中..." when the WebSocket is not connected. Hide when connected. This is simpler than a status icon and more visible.

The banner uses `RemoteClient.addConnectionListener()` which already exists.

### Decision 5: PlaylistDetailFragment defensive alignment

Add the same try-catch + `_binding` null check pattern already used in HomeFragment and PlaylistsFragment. Straightforward copy of the same pattern.

## Risks / Trade-offs

**[Risk] Auto-reconnect could spam the player if it's offline** → Mitigated by exponential backoff capped at 30 seconds. Max reconnect rate is once per 30s after a few failures.

**[Risk] Removing catch in `fetchList()` could crash if a caller forgets try-catch** → Mitigated by auditing all call sites. There are exactly 4 callers (HomeFragment, PlaylistsFragment, PlaylistDetailFragment, and fetchQueue used only in FloatingWindowService sync). All will be wrapped.

**[Risk] Adding ProgressBar to layouts could break existing layout on small screens** → Mitigated by using `wrap_content` with `GONE` default visibility. The spinner only appears briefly during loading.

**[Trade-off] Toast vs Snackbar for errors**: Toast is simpler and works from any context (including services). Snackbar requires a view reference. Using Toast for consistency since FloatingWindowService already uses Toast.

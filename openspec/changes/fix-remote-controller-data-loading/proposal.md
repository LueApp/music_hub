## Why

The remote controller mode has three critical usability issues: the controller phone shows empty song lists, empty playlist screens, and no playback progress — making the feature effectively non-functional. The root causes are: (1) silent HTTP failures with no user feedback or retry logic, (2) WebSocket auto-reconnection is missing so a single connection failure leaves the controller permanently disconnected, and (3) no loading/error states in the UI for remote data fetching.

## What Changes

- **Add error feedback and retry for remote HTTP fetches**: Show Toast or Snackbar when remote data loading fails, with a retry option. Currently `RemoteClient.fetchList()` silently returns `emptyList()` on any failure.
- **Add WebSocket auto-reconnect**: When the WebSocket connection drops or fails, automatically retry with exponential backoff. Currently a single failure leaves `isConnected = false` permanently.
- **Add loading states to controller-mode fragments**: Show a loading indicator while fetching remote data (songs, playlists) instead of immediately showing the empty state.
- **Add connection status indicator**: Show a visible connection status (connected/disconnected/reconnecting) so the user knows whether the controller is actually talking to the player.
- **Fix PlaylistDetailFragment missing defensive guards**: The controller path in `PlaylistDetailFragment.observeData()` lacks the try-catch and binding null-check that were added to `HomeFragment` and `PlaylistsFragment`.

## Non-goals

- Not changing the REST API or WebSocket protocol on the server (player) side — these already work correctly.
- Not adding new remote control capabilities (e.g., search, add songs remotely).
- Not persisting `RemoteMode` across app restarts — that's a separate concern.
- No changes to standalone or player modes — only controller mode is affected.
- Not affecting any specific platform (NetEase/QQ Music/Bilibili) behavior — this is platform-agnostic remote control infrastructure.

## Capabilities

### New Capabilities
- `remote-error-handling`: Error feedback, retry logic, and loading states for all remote data fetching on the controller side.
- `remote-auto-reconnect`: Automatic WebSocket reconnection with backoff when the connection drops.

### Modified Capabilities
<!-- No existing specs to modify -->

## Impact

- **RemoteClient.kt**: Add auto-reconnect logic to WebSocket, add error propagation (Result type or exceptions) for HTTP fetches.
- **HomeFragment.kt, PlaylistsFragment.kt, PlaylistDetailFragment.kt**: Add loading indicators, error Toasts, retry buttons for remote data loading.
- **FloatingWindowService.kt**: Show connection status, handle disconnected state gracefully.
- **MainActivity.kt**: Show connection status indicator in the now-playing bar area.
- **No new dependencies** — uses existing OkHttp, coroutines, Material components.
- **No new permissions** required.

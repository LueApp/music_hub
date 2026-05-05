## ADDED Requirements

### Requirement: WebSocket SHALL auto-reconnect on connection failure
When the WebSocket connection fails or is closed unexpectedly (not by user-initiated `disconnect()`), `RemoteClient` SHALL automatically attempt to reconnect.

#### Scenario: WebSocket connection drops
- **WHEN** the WebSocket `onFailure` callback fires (network error, server restart, etc.)
- **THEN** `RemoteClient` SHALL schedule a reconnection attempt after a delay
- **THEN** `isConnected` SHALL be set to `false` and connection listeners SHALL be notified

#### Scenario: WebSocket closed by server
- **WHEN** the WebSocket `onClosed` callback fires with a non-user-initiated close
- **THEN** `RemoteClient` SHALL schedule a reconnection attempt after a delay

#### Scenario: User calls disconnect()
- **WHEN** `RemoteClient.disconnect()` is called explicitly (e.g., mode change to standalone)
- **THEN** no reconnection SHALL be attempted
- **THEN** any pending reconnection timers SHALL be cancelled

### Requirement: Reconnection SHALL use capped exponential backoff
The delay between reconnection attempts SHALL increase exponentially, starting from an initial delay and capping at a maximum delay.

#### Scenario: First reconnection attempt
- **WHEN** the WebSocket connection fails for the first time
- **THEN** the reconnection attempt SHALL be scheduled after 1 second

#### Scenario: Subsequent failures
- **WHEN** reconnection attempt N fails
- **THEN** the next attempt SHALL be scheduled after `min(initialDelay * 2^N, maxDelay)` where maxDelay is 30 seconds

#### Scenario: Successful reconnection
- **WHEN** a reconnection attempt succeeds (WebSocket `onOpen` fires)
- **THEN** the backoff delay SHALL be reset to the initial value (1 second)
- **THEN** `isConnected` SHALL be set to `true` and connection listeners SHALL be notified

### Requirement: Reconnection SHALL stop when mode changes
If the app mode changes away from CONTROLLER while auto-reconnect is active, all pending reconnection timers SHALL be cancelled.

#### Scenario: Switch from controller to standalone during reconnect
- **WHEN** auto-reconnect is active (scheduling attempts) and the user switches to standalone mode
- **THEN** all pending reconnection callbacks SHALL be cancelled
- **THEN** no further reconnection attempts SHALL be made

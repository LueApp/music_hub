## ADDED Requirements

### Requirement: Remote fetch errors SHALL be shown to the user
When a remote HTTP fetch (songs, playlists, playlist songs) fails on the controller side, the system SHALL display a Toast message indicating the failure. The message SHALL be in Chinese (e.g., "加载失败，请检查网络连接").

#### Scenario: Network timeout fetching songs
- **WHEN** the controller is in controller mode and HomeFragment calls `RemoteClient.fetchAllSongs()` and the HTTP request times out
- **THEN** a Toast SHALL be displayed with error message "加载歌曲失败" and the song list SHALL show the empty state

#### Scenario: Network timeout fetching playlists
- **WHEN** the controller is in controller mode and PlaylistsFragment calls `RemoteClient.fetchPlaylists()` and the HTTP request fails
- **THEN** a Toast SHALL be displayed with error message "加载歌单失败" and the playlist view SHALL show the empty state

#### Scenario: Network timeout fetching playlist songs
- **WHEN** the controller is in controller mode and PlaylistDetailFragment calls `RemoteClient.fetchPlaylistSongs()` and the HTTP request fails
- **THEN** a Toast SHALL be displayed with error message "加载歌曲失败" and the song list SHALL show the empty state

### Requirement: Remote fetch errors SHALL propagate from RemoteClient
`RemoteClient.fetchList()` SHALL throw exceptions on network or parse failures instead of silently returning `emptyList()`. It SHALL still return `emptyList()` for successful responses with empty JSON arrays.

#### Scenario: HTTP connection refused
- **WHEN** `RemoteClient.fetchList()` is called and the server is unreachable
- **THEN** the method SHALL throw an `IOException` (or subclass) to the caller

#### Scenario: Successful empty response
- **WHEN** `RemoteClient.fetchList()` is called and the server returns an empty JSON array `[]`
- **THEN** the method SHALL return an empty list without throwing

### Requirement: Loading indicator SHALL be shown during remote fetches
When the controller fetches data from the player, a loading spinner (indeterminate ProgressBar) SHALL be visible while the HTTP request is in flight. The spinner SHALL be hidden when the request completes (success or failure).

#### Scenario: Loading songs on HomeFragment
- **WHEN** HomeFragment starts fetching songs in controller mode
- **THEN** a ProgressBar SHALL be visible, and the empty state and RecyclerView SHALL be hidden
- **WHEN** the fetch completes (success or failure)
- **THEN** the ProgressBar SHALL be hidden and the appropriate content (song list or empty state) SHALL be shown

#### Scenario: Loading playlists on PlaylistsFragment
- **WHEN** PlaylistsFragment starts fetching playlists in controller mode
- **THEN** a ProgressBar SHALL be visible
- **WHEN** the fetch completes
- **THEN** the ProgressBar SHALL be hidden

#### Scenario: Loading playlist songs on PlaylistDetailFragment
- **WHEN** PlaylistDetailFragment starts fetching songs in controller mode
- **THEN** a ProgressBar SHALL be visible
- **WHEN** the fetch completes
- **THEN** the ProgressBar SHALL be hidden

### Requirement: PlaylistDetailFragment SHALL have defensive guards in controller mode
The controller-mode code path in `PlaylistDetailFragment.observeData()` SHALL include try-catch around the remote fetch and SHALL check `_binding == null` after returning from `withContext(Dispatchers.IO)`.

#### Scenario: Fragment destroyed during fetch
- **WHEN** PlaylistDetailFragment is in controller mode and the user navigates away while `fetchPlaylistSongs()` is still in flight
- **THEN** the coroutine SHALL check `_binding == null` and return without accessing the binding, preventing a crash

#### Scenario: Fetch throws exception
- **WHEN** PlaylistDetailFragment is in controller mode and `fetchPlaylistSongs()` throws an exception
- **THEN** the exception SHALL be caught, logged, and a Toast SHALL be shown instead of crashing

### Requirement: Connection status SHALL be visible to the controller user
When the app is in controller mode, a connection status indicator SHALL be shown in MainActivity. It SHALL display "已断开" when the WebSocket is not connected and SHALL be hidden when connected.

#### Scenario: WebSocket connected
- **WHEN** the controller establishes a WebSocket connection to the player
- **THEN** the connection status banner SHALL be hidden (or show connected state briefly then hide)

#### Scenario: WebSocket disconnected
- **WHEN** the WebSocket connection drops
- **THEN** the connection status banner SHALL show "已断开 - 重连中..." and remain visible until reconnection succeeds

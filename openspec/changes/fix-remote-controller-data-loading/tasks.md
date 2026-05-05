## 1. RemoteClient Error Propagation & Auto-Reconnect

- [x] 1.1 Update `RemoteClient.fetchList()` to throw on network/parse failures instead of returning `emptyList()`. Keep returning `emptyList()` for successful empty responses. File: `android-app/app/src/main/java/com/musichub/remote/RemoteClient.kt`
- [x] 1.2 Add WebSocket auto-reconnect with exponential backoff to `RemoteClient`. On `onFailure`/`onClosed` (non-user-initiated), schedule reconnect via `mainHandler.postDelayed()`. Initial delay 1s, max 30s, reset on success. Add `cancelReconnect()` called from `disconnect()`. File: `android-app/app/src/main/java/com/musichub/remote/RemoteClient.kt`
- [x] 1.3 Cancel auto-reconnect when mode changes away from CONTROLLER. Update `RemoteMode.setStandalone()` and `RemoteMode.setPlayer()` to call `RemoteClient.disconnect()`. File: `android-app/app/src/main/java/com/musichub/remote/RemoteMode.kt`

## 2. Layout Changes — Add Loading Indicators

- [x] 2.1 Add `<ProgressBar>` (indeterminate, centered, `android:visibility="gone"`) with id `progressLoading` to `fragment_home.xml`. File: `android-app/app/src/main/res/layout/fragment_home.xml`
- [x] 2.2 Add `<ProgressBar>` (indeterminate, centered, `android:visibility="gone"`) with id `progressLoading` to `fragment_playlists.xml`. File: `android-app/app/src/main/res/layout/fragment_playlists.xml`
- [x] 2.3 Add `<ProgressBar>` (indeterminate, centered, `android:visibility="gone"`) with id `progressLoading` to `fragment_playlist_detail.xml`. File: `android-app/app/src/main/res/layout/fragment_playlist_detail.xml`
- [x] 2.4 Add connection status `<TextView>` (id `tvConnectionStatus`, `android:visibility="gone"`, red/warning background) to `activity_main.xml` below the toolbar area. File: `android-app/app/src/main/res/layout/activity_main.xml`

## 3. Fragment Updates — Error Handling & Loading States

- [x] 3.1 Update `HomeFragment.observeData()` controller path: show `progressLoading` before fetch, hide after; show Toast on catch with "加载歌曲失败". File: `android-app/app/src/main/java/com/musichub/ui/fragment/HomeFragment.kt`
- [x] 3.2 Update `PlaylistsFragment.observeData()` controller path: show `progressLoading` before fetch, hide after; show Toast on catch with "加载歌单失败". File: `android-app/app/src/main/java/com/musichub/ui/fragment/PlaylistsFragment.kt`
- [x] 3.3 Update `PlaylistDetailFragment.observeData()` controller path: add try-catch + `_binding` null check (matching HomeFragment/PlaylistsFragment pattern), show `progressLoading` before fetch, hide after; show Toast on catch with "加载歌曲失败". File: `android-app/app/src/main/java/com/musichub/ui/fragment/PlaylistDetailFragment.kt`

## 4. MainActivity — Connection Status Banner

- [x] 4.1 Add connection status banner logic in `MainActivity`. In controller mode, register a `RemoteClient` connection listener that shows/hides `tvConnectionStatus` with text "已断开 - 重连中..." when disconnected and hides when connected. File: `android-app/app/src/main/java/com/musichub/ui/MainActivity.kt`

## 5. String Resources

- [x] 5.1 Add Chinese string resources for error messages and connection status: `remote_load_songs_failed` ("加载歌曲失败"), `remote_load_playlists_failed` ("加载歌单失败"), `remote_disconnected_reconnecting` ("已断开 - 重连中..."). File: `android-app/app/src/main/res/values/strings.xml`

## 6. Build & Verification

- [x] 6.1 Build with `pixi run build` and verify no compilation errors
- [ ] 6.2 Deploy to both phones. On player: set mode to Player. On controller: set mode to Controller with player IP. Verify: song list loads, playlists load, playback progress shows in floating window
- [ ] 6.3 Test error scenarios: stop the player server, verify controller shows error Toast and reconnects when player comes back online
- [ ] 6.4 Commit all changes

## Why

Bilibili is the only supported platform that lacks playlist import and sync functionality. NetEase and QQ Music both support importing playlists from URLs and periodic background syncing via the existing `SyncSource` infrastructure. Users who curate Bilibili favorites folders (medialists) cannot import them into Music Hub, and the app explicitly rejects Bilibili URLs in the manage-sources flow with "B站暂不支持歌单同步". Adding this brings Bilibili to feature parity with the other two platforms.

## What Changes

- Add URL pattern recognition for Bilibili medialist/favorites URLs (`bilibili.com/medialist/detail/ml{id}`, `space.bilibili.com/{uid}/favlist?fid={id}`)
- Implement `parsePlaylistUrl()` in `BilibiliPlatform` to extract the `media_id` from medialist URLs
- Implement `fetchPlaylistSongs()` in `BilibiliPlatform` using the Bilibili favorites API (`/x/v3/fav/resource/list`) with pagination support
- Remove the explicit Bilibili rejection in `ManageSourcesViewModel` so Bilibili medialists can be added as sync sources
- Enable periodic playlist sync for Bilibili sources through the existing `PlaylistSyncEngine` infrastructure (no changes needed to the sync engine itself)

## Non-goals

- Authentication/login for private Bilibili favorites (only public medialists are supported)
- Bilibili "channel" or "series" playlist types (only favorites/medialists with `ml{id}` format)
- Bilibili audio playlists (`bilibili.com/audio/am{id}`) — only video favorites folders
- Changes to the sync engine, scheduler, or WorkManager infrastructure (these are platform-agnostic and already work)
- No new Android permissions required

## Capabilities

### New Capabilities
- `bilibili-medialist-sync`: Parsing, importing, and syncing Bilibili favorites/medialist playlists via the public Bilibili API

### Modified Capabilities

## Impact

- **BilibiliPlatform.kt**: Add `parsePlaylistUrl()` and `fetchPlaylistSongs()` implementations with new URL regex patterns and API calls
- **ManageSourcesViewModel.kt**: Remove Bilibili-specific rejection block
- **No database changes**: Existing `SyncSource` entity already supports `platform="bilibili"` — no schema migration needed
- **No new dependencies**: Uses existing OkHttp client and Gson for API calls
- **Affected platform**: Bilibili only — no changes to NetEase or QQ Music behavior

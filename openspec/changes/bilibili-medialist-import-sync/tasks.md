## 1. Bilibili URL Parsing

- [x] 1.1 Add medialist/favorites URL regex patterns to `BilibiliPlatform.kt` (`android-app/app/src/main/java/com/musichub/platform/BilibiliPlatform.kt`): `bilibili\.com/medialist/detail/ml(\d+)` and `space\.bilibili\.com/\d+/favlist.*[?&]fid=(\d+)`
- [x] 1.2 Implement `parsePlaylistUrl()` override in `BilibiliPlatform` that extracts the media_id and returns `ParsedPlaylist(platform="bilibili", playlistId=mediaId)`

## 2. Bilibili Favorites API Integration

- [x] 2.1 Implement `fetchPlaylistSongs()` override in `BilibiliPlatform.kt` that calls `https://api.bilibili.com/x/v3/fav/resource/list?media_id={id}&pn={page}&ps=20` with pagination
- [x] 2.2 Parse each media item from the API response: extract `bv_id` (or fall back to `id` as av number), `title`, `upper.name`, `cover`. Filter to `type == 2` only and skip `attr == 9` items
- [x] 2.3 Populate `ParsedPlaylist` metadata (name, coverUrl, songCount) from `data.info` in the first page response
- [x] 2.4 Handle error responses: return `null` and log for `code != 0` (including -403 for private favorites, -404 for not found)

## 3. ManageSourcesViewModel Integration

- [x] 3.1 Update `ManageSourcesViewModel.addSourceFromUrl()` (`android-app/app/src/main/java/com/musichub/ui/viewmodel/ManageSourcesViewModel.kt`): replace the Bilibili rejection block (lines 79-85) with a call to `biliHandler.parsePlaylistUrl()` that creates a `SyncSource` if a valid medialist is found

## 4. Build Verification and Testing

- [x] 4.1 Run `pixi run build` to verify the project compiles without errors
- [ ] 4.2 Deploy to device (`pixi run deploy`) and manually test: import `https://www.bilibili.com/medialist/detail/ml3957996127` via the AddSong flow — verify songs are imported with correct titles and BV IDs
- [ ] 4.3 Manually test: add the same medialist URL as a sync source via Manage Sources — verify `SyncSource` is created and periodic sync picks up changes

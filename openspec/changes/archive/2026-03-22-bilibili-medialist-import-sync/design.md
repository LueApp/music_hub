## Context

Bilibili is currently the only supported platform that cannot import or sync playlists. NetEase and QQ Music both implement `parsePlaylistUrl()` and `fetchPlaylistSongs()` in their platform handlers, enabling the existing `PlaylistSyncEngine` to periodically sync remote playlists. Bilibili's `BilibiliPlatform` class handles single video/audio URL parsing and metadata fetching, but has no playlist support. The `ManageSourcesViewModel` explicitly rejects Bilibili URLs with "B站暂不支持歌单同步".

Bilibili organizes user-curated collections as "favorites folders" (收藏夹), accessible at `bilibili.com/medialist/detail/ml{media_id}`. These are fetchable via the public API endpoint `/x/v3/fav/resource/list` with pagination. Each item in a favorites folder has a `bv_id` field that maps directly to the existing `video:BV{id}` platformSongId format already used by `BilibiliPlatform`.

The sync infrastructure (`SyncSource` entity, `PlaylistSyncEngine`, `SyncScheduler`, `PlaylistSyncWorker`) is fully platform-agnostic and already supports `platform="bilibili"` in the schema without migration.

## Goals / Non-Goals

**Goals:**
- Enable `BilibiliPlatform` to recognize and parse Bilibili medialist/favorites URLs
- Fetch all videos from a Bilibili favorites folder via the public API with pagination
- Integrate into the existing sync pipeline so Bilibili medialists are treated identically to NetEase/QQ Music playlists for import and periodic sync
- Remove the explicit Bilibili rejection in `ManageSourcesViewModel`

**Non-Goals:**
- Authentication for private favorites folders (only public ones)
- Support for Bilibili channels, series, or audio playlists (only favorites/medialists)
- Changes to the sync engine, scheduler, or worker infrastructure
- New UI components (the existing import and manage-sources UIs already handle any platform generically)
- Support for non-video content types within a favorites folder (e.g., articles, columns)

## Decisions

### 1. URL patterns to recognize

**Decision:** Support two URL formats for Bilibili favorites:
- `bilibili.com/medialist/detail/ml(\d+)` — the primary share URL format
- `space.bilibili.com/\d+/favlist.*fid=(\d+)` — the user space URL format

**Rationale:** These are the two standard ways Bilibili favorites are shared. The `ml{id}` URL is what users get when they click "share" on a favorites folder. The `space.bilibili.com` URL is what appears when browsing a user's favorites list. Both contain the `media_id` needed for the API call.

**Alternative considered:** Supporting `bilibili.com/list/ml{id}` (a newer format seen in some contexts). This can be added later if needed — the regex approach makes it trivial to extend.

### 2. API endpoint and pagination strategy

**Decision:** Use `https://api.bilibili.com/x/v3/fav/resource/list` with `ps=20` page size, paginating until all items are fetched using `data.info.media_count` as the total.

**Rationale:** This is the standard public API for fetching favorites folder contents. The page size of 20 matches Bilibili's default and keeps individual responses small. The `media_count` field in the response tells us the total count, so we know when to stop paginating.

**Alternative considered:** Using `/x/v3/fav/resource/ids` to get all IDs in one call, then batch-fetching metadata. This would be more efficient for very large folders but adds complexity. The paginated approach is simpler and the metadata (title, cover, uploader) comes included in each page response, avoiding extra API calls.

### 3. Content type filtering

**Decision:** Only import items with `type == 2` (video). Skip other resource types (articles, audio collections, etc.) with a log warning. Also skip items with `attr == 9` (invalidated/deleted items that Bilibili marks but retains in the list).

**Rationale:** The app's Bilibili support is built around video BV IDs with deep links like `bilibili://video/{bvid}`. Non-video items would not have valid deep links. Deleted/invalidated items would fail on playback.

### 4. Metadata extraction from favorites API response

**Decision:** Extract `title`, `upper.name` (as artist), `cover`, and `bv_id` directly from each media item in the favorites list response. Use the existing `video:BV{id}` platformSongId format.

**Rationale:** The favorites API response includes sufficient metadata per item, so we don't need additional per-video API calls. This reuses the exact platformSongId format that `BilibiliPlatform` already uses for single video imports, ensuring deduplication works correctly via the `(platform, platform_song_id)` unique constraint.

### 5. ManageSourcesViewModel integration

**Decision:** Replace the Bilibili rejection block with a call to `biliHandler.parsePlaylistUrl()`. If the handler returns a parsed playlist, proceed with creating a `SyncSource` just like NetEase and QQ Music.

**Rationale:** The entire point of the `PlatformHandler` interface pattern is to make platform support uniform. With `parsePlaylistUrl()` implemented, the ViewModel should treat Bilibili identically to other platforms.

### 6. Playlist metadata

**Decision:** Extract the favorites folder name and media count from `data.info` in the first page response, and populate `ParsedPlaylist.name`, `ParsedPlaylist.coverUrl`, and `ParsedPlaylist.songCount`.

**Rationale:** Consistent with how NetEase and QQ Music populate playlist metadata for the import UI.

## Risks / Trade-offs

**[Private favorites]** → The API returns `code: -403` for private favorites. Mitigation: Return a clear error message "该收藏夹为私密状态，无法导入" when encountering -403.

**[Rate limiting]** → Bilibili may rate-limit API requests, especially during pagination of large favorites. Mitigation: Use a conservative page size (20) and the existing OkHttp client timeout handling. The sync engine already handles transient errors gracefully (marks source as error, doesn't remove items).

**[Invalidated items]** → Deleted videos remain in favorites lists with `attr == 9`. Mitigation: Filter these out during import to avoid adding unplayable songs.

**[Items without bv_id]** → Some very old videos may not have a BV ID in the response (only `id` as av number). Mitigation: Fall back to `video:av{id}` format when `bv_id` is empty, which `BilibiliPlatform` already supports.

**[No impact on floating window or media monitoring]** → This change only affects the platform handler and import/sync flow. No changes to `FloatingWindowService`, `MediaMonitorService`, or `PlaybackService`. Behavior is identical in standalone, player, and controller modes since the sync engine runs independently.

## Open Questions

- Should there be a maximum number of items to import from a single favorites folder? NetEase caps at 500, QQ Music at 5000. Bilibili favorites can contain up to 1000 items per folder. For now, import all items without a cap since 1000 is within a reasonable range.

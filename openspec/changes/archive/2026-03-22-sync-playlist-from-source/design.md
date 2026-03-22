## Context

Music Hub currently imports playlists from NetEase Cloud Music and QQ Music as a one-time snapshot. The imported songs are copied into a local Room database and have no connection back to the source. Users who want to keep their local playlists up-to-date with changes on the source platform must manually re-import, which is tedious and creates duplicates.

The app already has the infrastructure to fetch playlist songs from both platforms via `PlatformHandler.fetchPlaylistSongs()`, and the Room database supports `(platform, platform_song_id)` deduplication on the `songs` table.

## Goals / Non-Goals

**Goals:**
- Allow a local playlist to be linked to one or more remote source playlist URLs
- Provide a sync mechanism that detects added/removed songs in the source and applies changes locally
- Support periodic automatic sync via WorkManager
- Support manual sync triggered from the playlist detail screen
- Show sync status (last sync time, in-progress indicator) in the UI
- Work in standalone and player modes (sync runs on the device with the database)

**Non-Goals:**
- Write-back sync (pushing local changes to the remote platform)
- Bilibili playlist sync (Bilibili doesn't support playlist fetching)
- Syncing playlist metadata (name, cover, description)
- Real-time push notifications from platforms
- Sync in controller mode (controller has no local database; sync only runs on standalone/player devices)

## Decisions

### 1. New `SyncSource` Room entity (separate table, not inline on Playlist)

**Decision:** Create a new `sync_sources` table rather than adding columns to the `playlists` table.

**Rationale:** A sync playlist can have multiple sources (e.g., one NetEase + two QQ Music playlists). A separate entity with a foreign-key-like `playlistId` naturally models this one-to-many relationship. It also keeps the `Playlist` entity clean for non-synced playlists.

**Alternatives considered:**
- JSON blob column on `Playlist` — simpler but loses queryability, breaks Room conventions
- Inline columns for a single source — doesn't support multiple sources per playlist

**Schema:**
```kotlin
@Entity(
    tableName = "sync_sources",
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "platform", "remote_playlist_id"], unique = true)
    ]
)
data class SyncSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val platform: String,                           // "netease" or "qqmusic"
    @ColumnInfo(name = "remote_playlist_id") val remotePlaylistId: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long = 0,
    @ColumnInfo(name = "last_sync_status") val lastSyncStatus: String = "never", // "never", "success", "error"
    @ColumnInfo(name = "last_sync_error") val lastSyncError: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
```

### 2. Sync algorithm: additive by default, with removal of songs no longer in any source

**Decision:** During sync, for each source: fetch the remote song list, add any new songs not already in the local playlist. After processing all sources, remove songs that were originally added by sync but are no longer present in any attached source.

**Tracking sync-origin:** Add an optional `sync_source_id` column to `PlaylistItem` (nullable, null for manually-added songs). This lets us distinguish manually-added songs from sync-added ones, so manual additions are never removed by sync.

**Rationale:** Users may manually add songs to a sync playlist. Those songs should persist across syncs. Only songs that came from a sync source and have been removed from that source should be cleaned up.

**Alternatives considered:**
- Full replace (delete all + re-add) — destructive, loses manual additions and ordering
- Never remove, only add — playlist grows forever, stale songs accumulate

### 3. WorkManager for periodic sync

**Decision:** Use AndroidX WorkManager with `PeriodicWorkRequest` for background sync scheduling.

**Rationale:** WorkManager is the standard Android solution for deferrable periodic background work. It handles doze mode, battery optimization, and survives app restarts. No new permissions are needed.

**Configuration:**
- Default sync interval: 6 hours (minimum WorkManager period is 15 minutes)
- Configurable per-playlist or globally via settings
- Constraints: requires network connectivity (`NetworkType.CONNECTED`)
- Existing work policy: `KEEP` (don't re-enqueue if already scheduled)

**Alternatives considered:**
- AlarmManager — lower-level, doesn't respect battery optimization, more boilerplate
- Foreground service — overkill for periodic fetches, wastes battery
- Simple coroutine timer — doesn't survive app kill or device restart

### 4. Sync interval stored on Playlist entity

**Decision:** Add a `sync_interval_minutes` column (Long, default 360 = 6 hours) to the `Playlist` entity. Value of 0 means sync is disabled (no sources or manual-only).

**Rationale:** Different playlists may need different sync frequencies. Storing it on the playlist allows per-playlist configuration. The WorkManager worker reads this value when scheduling.

### 5. Database migration strategy

**Decision:** Use `fallbackToDestructiveMigration()` (already configured) and bump the database version. Since this is a personal-use app without a large user base, destructive migration is acceptable.

**Rationale:** The app already uses `fallbackToDestructiveMigration()`. Adding a proper migration is possible but unnecessary at this stage.

### 6. UI integration in PlaylistDetailFragment

**Decision:** Add sync controls to the existing playlist detail screen via a toolbar menu:
- A sync status indicator showing last sync time
- A "Sync Now" menu item to trigger manual sync
- A "Manage Sources" option to view/add/remove attached source URLs
- Sync status text below the playlist description

**Rationale:** Keeps the sync functionality close to where users interact with the playlist. No need for a separate screen.

### 7. Import flow integration

**Decision:** When a user imports a playlist via share intent, offer a checkbox "Keep synced with source" (默认勾选). If checked, a `SyncSource` record is created linking the playlist to the remote source. The initial import still happens immediately (same as today), and subsequent syncs happen via WorkManager.

**Rationale:** This is the natural point where the user has a remote playlist URL. Making sync opt-in (but defaulted on) avoids surprising users while making the feature discoverable.

## Risks / Trade-offs

- **[API rate limits]** → Platforms may rate-limit playlist fetches. Mitigation: minimum sync interval of 15 minutes, exponential backoff on errors, and the default 6-hour interval is conservative.
- **[Network failures during sync]** → Partial sync could leave inconsistent state. Mitigation: wrap each source fetch + apply in a Room transaction; if one source fails, other sources still sync, and the failed source logs an error without removing its songs.
- **[Large playlists]** → Fetching a 1000+ song playlist may be slow. Mitigation: existing platform handlers already batch API calls (NetEase batches by 50, QQ Music paginates by 300). The sync worker runs on a background thread.
- **[Song ordering]** → After sync, the order of newly-added songs may not match the remote order. Mitigation: append new songs at the end (after existing songs). Preserving remote order perfectly would require rewriting all positions, which conflicts with manual reordering.
- **[Destructive migration]** → Users lose existing data on schema change. Mitigation: acceptable for this personal-use app; document in release notes.

## Open Questions

- Should there be a global sync toggle in settings (enable/disable all sync), or is per-playlist control sufficient? Starting with per-playlist only.

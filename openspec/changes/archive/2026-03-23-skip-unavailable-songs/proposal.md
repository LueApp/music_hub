## Why

When the playback queue hits an unavailable song (deleted, region-locked, taken down), the deep link opens the music app which shows an error page. The queue gets stuck because MediaMonitorService never detects a proper "song finished" signal from the error state, blocking the entire playback flow.

## What Changes

- Add `checkSongAvailability()` method to the `PlatformHandler` interface with platform-specific implementations for NetEase, QQ Music, and Bilibili
- Modify `PlaybackService.launchCurrentSong()` to check availability before launching a deep link
- Auto-skip unavailable songs with a toast notification showing the reason (e.g., "跳过: 歌曲名 (歌曲已下架)")
- Add a safety limit (10 consecutive skips) to prevent infinite loops when all songs in the queue are unavailable
- On network errors during the check, assume available to avoid blocking playback

## Non-goals

- No persistent storage of unavailability status (skip info is logged but not saved to the database)
- No cross-platform song replacement (finding the same song on another platform)
- No UI for viewing/managing skipped song history
- No pre-scanning of the entire queue before playback starts

## Capabilities

### New Capabilities
- `song-availability-check`: Pre-playback availability verification via platform APIs with auto-skip for unavailable songs

### Modified Capabilities
<!-- No existing specs to modify -->

## Impact

- **Platform handlers**: NetEasePlatform, QQMusicPlatform, BilibiliPlatform each gain a `checkSongAvailability()` override
- **PlatformHandler interface**: New `checkSongAvailability()` default method and `SongAvailability` data class
- **PlaybackService**: `launchCurrentSong()` refactored to async check → skip or launch pattern; new `doLaunchSong()` and `getHandlerForPlatform()` helpers; coroutine scope added to service lifecycle
- **No new permissions required**
- **No database schema changes**
- **Affected platforms**: All three — NetEase Cloud Music, QQ Music, Bilibili

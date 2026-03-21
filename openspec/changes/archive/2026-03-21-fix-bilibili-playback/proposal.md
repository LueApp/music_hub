## Why

Bilibili content has two playback issues that hurt the user experience:

1. **Videos resume from last position**: When Music Hub launches a Bilibili video via `bilibili://video/{id}`, the Bilibili app resumes from wherever the user previously stopped rather than playing from the beginning. This is wrong for a playlist-based music launcher — the user expects each song/video to start fresh.

2. **No playback status detection**: MediaMonitorService explicitly excludes Bilibili (`tv.danmaku.bili`) from media session monitoring, so Music Hub cannot detect when a Bilibili video finishes. This means auto-advance to the next song never triggers for Bilibili content — the user must manually tap "Next" every time.

## What Changes

- Append `?start_progress=0` to Bilibili video deep links (`bilibili://video/{id}?start_progress=0`) to force playback from the beginning
- Add `tv.danmaku.bili` to MediaMonitorService's `targetPackages` so Bilibili media sessions are monitored
- Remove the Bilibili exclusion guards in PlaybackService (`songFinishedReceiver` and `schedulePlaybackTimeout`) so auto-advance and timeout detection work for Bilibili content

## Non-goals

- No changes to NetEase or QQ Music handling
- No changes to Bilibili metadata fetching or song storage
- No changes to the floating window UI

## Capabilities

### New Capabilities
- `bilibili-playback-control`: Force Bilibili videos to start from the beginning via deep link parameters and enable media session monitoring for Bilibili content so auto-advance works.

### Modified Capabilities
- `bilibili-native-deep-link`: Deep link format changes from `bilibili://video/{id}` to `bilibili://video/{id}?start_progress=0` for video content
- `playback-timeout-detection`: Remove the Bilibili exclusion so timeout detection applies to Bilibili songs

## Impact

- **BilibiliPlatform.kt**: `generateDeepLink()` updated to append `?start_progress=0` to video deep links
- **DeepLinkLauncher.kt**: `convertLegacyBilibiliDeepLink()` updated to also append `?start_progress=0` in the conversion
- **MediaMonitorService.kt**: Add `tv.danmaku.bili` to `targetPackages` set
- **PlaybackService.kt**: Remove Bilibili exclusion in `songFinishedReceiver` and `schedulePlaybackTimeout()`
- No new dependencies or permissions required

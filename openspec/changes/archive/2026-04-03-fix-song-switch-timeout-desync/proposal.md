## Why

Song switching has three interrelated bugs causing songs to be incorrectly skipped and playback state to desync from actual audio. The root cause is that the playback timeout system uses a binary warm/cold heuristic that doesn't account for landscape workaround delays or rapid cross-platform switching, and the timeout verification only checks "is something playing" without verifying it's the correct song.

## What Changes

- Fix timeout duration to account for NetEase landscape workaround (CLEAR_TASK makes warm controllers irrelevant — treat as cold start)
- Add song identity verification to the timeout check: compare MediaSession metadata title against the expected song before declaring "all good"
- When a timeout-skipped song starts playing late (wrong song detected), pause it and re-launch the correct current song
- Increase timeout tolerance during rapid cross-platform switches where apps need extra time to process pause/stop + new deep link

## Capabilities

### New Capabilities
- `song-identity-verification`: Verify that the song actually playing matches what PlaybackService expects, detect and recover from desync

### Modified Capabilities
- `playback-timeout-detection`: Timeout duration must account for landscape workaround (CLEAR_TASK = cold start), and timeout check must verify song identity not just playback state

## Impact

- `PlaybackService.kt`: Timeout scheduling logic, timeout check runnable, desync recovery
- `MediaMonitorService.kt`: Expose current metadata title for identity verification
- `DeepLinkLauncher.kt`: Signal when landscape workaround is active (affects timeout calculation)

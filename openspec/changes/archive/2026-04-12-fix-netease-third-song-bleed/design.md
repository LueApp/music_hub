## Context

When Music Hub advances from one NetEase song to another (same-platform), the following sequence occurs:

1. Early song-end detection fires ~2s before song ends (99% played or <2s remaining)
2. `doLaunchSong()` sends the deep link for the next song immediately
3. NetEase auto-advances to its own internal queue's next song ~600ms-1.5s after the current song ends
4. The auto-advanced "third song" plays audibly for ~0.5-1.5s
5. At t+2s, the re-sent deep link overrides NetEase, loading the correct song

The problem: between step 2 (initial deep link) and step 5 (re-send), NetEase's auto-advance plays a third song audibly. The current portrait-mode approach relies solely on the 2s re-send to fix this, but provides no silencing during the gap.

The landscape-mode code already handles this correctly — it sends pause commands at 500ms and 1200ms to silence the auto-advanced song. Portrait mode lacks this intermediate pause.

## Goals / Non-Goals

**Goals:**
- Silence the auto-advanced third song during NetEase-to-NetEase portrait transitions
- Keep the double-send approach (it reliably overrides NetEase's queue)
- Add intermediate pause commands to mute the third song during the gap window

**Non-Goals:**
- Changing landscape mode behavior (already working)
- Cross-platform switch behavior
- Eliminating the 2s re-send window entirely
- Modifying early song-end detection timing

## Decisions

### Decision 1: Add intermediate pauses in portrait mode (same approach as landscape)

**Choice**: Insert `pausePackage("com.netease.cloudmusic")` calls at ~500ms and ~1200ms after the initial deep link send, mirroring what landscape mode already does successfully.

**Rationale**: The landscape code already proves this pattern works. When the initial deep link is sent, NetEase starts loading it. The auto-advanced third song that appears ~600ms later can be paused without affecting the deep-link-loaded target song (which takes longer to initialize). The re-send at 2s then ensures the correct song plays.

**Alternative considered**: Shortening the re-send delay from 2s to 1s — rejected because the deep link needs time to fully load; sending too early could be swallowed by a still-initializing player.

### Decision 2: Unify portrait/landscape pause logic

**Choice**: Merge the portrait and landscape branches so both use intermediate pauses. The landscape branch additionally skips the re-send (as it already does). The portrait branch keeps the re-send at 2s.

**Rationale**: Reduces code duplication between the two branches. The only difference is whether the re-send happens — both modes benefit from intermediate pauses.

### Decision 3: Keep re-send at 2s for portrait mode

**Choice**: Don't change the 2s re-send timing.

**Rationale**: The re-send is a safety net that ensures the correct song is loaded even if intermediate pauses fail. The pauses only silence the third song; the re-send actually replaces it. 2s gives enough time for the initial deep link to load.

## Risks / Trade-offs

- **[Risk] Pausing the target song instead of the third song**: If the initial deep link loads faster than expected, a pause at 500ms could pause the target song. → **Mitigation**: NetEase deep links typically take 1-3s to start playback; at 500ms the player is still loading, not playing. Log the pause actions for debugging.

- **[Risk] Race condition with early detection timing**: If early detection fires later than expected, the timeline shifts. → **Mitigation**: The pause commands are fire-and-forget; if there's nothing to pause, they're no-ops. The re-send at 2s remains as the reliable fallback.

- **[Trade-off] Brief silence gap**: The user may hear ~0.5s of silence between songs instead of the third song audio. This is the desired behavior — silence is preferable to wrong music.

- **[Mode behavior]**: This fix applies to standalone and player modes (local playback). Controller mode delegates to the player via remote API, so the fix applies transparently on the player side.

## Why

Three floating-window / freeform behaviours have regressed and are now visibly broken to the user:

1. **The mini floating ball's album-cover artwork no longer rotates while a song is playing**, so the ball gives no visual feedback that playback is live.
2. **The floating ball's circular progress ring can latch onto a different platform's session** (e.g. a Bilibili video) once the user pauses the song that Tutti launched — the ring drifts away from the song we are actually controlling.
3. **In background (freeform) launch mode, swiping HOME does not reliably hide all music-app freeform windows.** After a platform switch, the prior platform's freeform task can re-appear as a visible square window on the home screen because the off-screen-resize bookkeeping only tracks one platform at a time.

All three together break the "discreet floating ball" UX promise that background mode is supposed to deliver, so they need to be fixed as one cohesive change rather than three trickling patches.

## What Changes

- **Restart album-cover rotation correctly on state transitions.** The animator on `ivBallCover` in `FloatingWindowService` must be (re)started whenever the bound playback state becomes `PLAYING`, and must remain a *running* (not paused-after-cancel) animator across pause→play cycles. The rotation must keep ticking even if the underlying ImageView is recycled for a new song's cover bitmap.
- **Restrict the floating ball's progress/state source to the currently launched platform.** `MediaMonitorService.getPlaybackInfo()` must filter (not just prefer-sort) by `currentPlatformPackage`, and `FloatingWindowService.updateProgress()` must ignore controllers from any other music-app package — including Bilibili in video mode — even when those sessions are `PLAYING`. When no controller for the current platform exists, the ring shows the last known position of the launched song instead of snapping to a foreign session's position.
- **Hide every music-app freeform task on HOME / launcher transitions, not just the active one.** `ShizukuLauncher` must keep an off-screen-bounds memory per package (not only `currentTargetPkg`), and `PlayerAccessibilityService` must trigger an off-screen resize for *any* music-app package whose freeform window resurfaces — not only the one currently registered as the active target.
- **Add an adb-driven verification recipe** to the change docs (no new test-infrastructure dependency) so the developer can re-confirm fixes on device after each release.

### Non-goals

- Not changing the foreground launch mode (rotation/landscape hack stays as-is).
- Not introducing new permissions; all fixes use existing SYSTEM_ALERT_WINDOW, NotificationListener, accessibility, and Shizuku grants.
- Not adding background song-switching capability — the known foreground-bring-up limitation remains.
- Not adding a new unit-test or instrumentation-test framework; verification is via adb logcat + dumpsys.

## Capabilities

### New Capabilities

- `ball-cover-rotation`: The mini floating-ball album cover rotates whenever the bound song is in a PLAYING state, and pauses (without losing wall-clock continuity across short pause/play cycles) otherwise.
- `current-platform-playback-isolation`: The floating window's playback state (progress, position, paused/playing icon) is sourced *only* from the MediaController belonging to the platform package that Tutti most recently launched, never from a stray PLAYING session of a different music-app or video-app package.
- `freeform-multi-task-hide`: In background launch mode, every music-app freeform task that Tutti has ever launched is kept off-screen (or re-hidden within ≤500 ms) across HOME-gesture, Recents-dismiss, and platform-switch transitions — not only the currently-active platform's task.

### Modified Capabilities

None. The three new capabilities are additive and do not change the public requirements of existing specs (`floating-ball-double-click`, `playback-timeout-detection`, `thread-safe-media-monitor`, etc.).

## Impact

- **Code:**
  - `android-app/app/src/main/java/com/musichub/service/FloatingWindowService.kt` — `startCoverRotation` / `stopCoverRotation` / `updateMiniBall` / `updateProgress` / `updateProgressFromRemote`.
  - `android-app/app/src/main/java/com/musichub/service/MediaMonitorService.kt` — `getPlaybackInfo` and any helper that the floating window calls into for current-position queries.
  - `android-app/app/src/main/java/com/musichub/service/ShizukuLauncher.kt` — per-package off-screen-bounds memory, `triggerResize` / `triggerResizeForCurrentTarget`, watchdog state.
  - `android-app/app/src/main/java/com/musichub/service/PlayerAccessibilityService.kt` — `onAccessibilityEvent` `TYPE_WINDOWS_CHANGED` branch: dispatch `triggerResize(pkg)` for every music-app package that resurfaces, not gated on `currentTargetPkg`.
- **Layouts / strings:** none.
- **Permissions:** none added; relies on existing Shizuku + accessibility grants for fix 3.
- **Platforms affected:** all four (NetEase Cloud Music, QQ Music, Kugou, Bilibili). Bilibili is the primary trigger for fix 2 because its video sessions are the most common source of cross-platform contamination.
- **Risk:** the per-package freeform memory increases shell-IPC traffic on platform switch; the watchdog cap and 400 ms tick are unchanged so steady-state CPU/IPC cost stays within the existing budget.
- **Verification:** documented adb recipe in `design.md` — logcat-tagged checks plus `dumpsys activity activities | grep freeform` and `dumpsys media_session`.

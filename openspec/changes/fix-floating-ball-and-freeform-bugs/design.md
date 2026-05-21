## Context

This change addresses three orthogonal regressions, all surfacing in background launch mode:

1. **Album-cover rotation regression.** `FloatingWindowService.startCoverRotation`/`stopCoverRotation` (~lines 1365–1407) maintain `coverRotationAnimator` plus `currentRotatingView`. The current pause/resume branch (~lines 1372–1376) calls `coverRotationAnimator?.resume()`, but the animator was cancelled (not paused) on the previous tick at line 1385. A cancelled `ObjectAnimator` cannot be resumed — `resume()` is a silent no-op — so once cancellation happens, all subsequent ticks try (and fail) to resume the same dead animator instead of creating a new one. The cover then appears static.

2. **Progress source contamination.** `MediaMonitorService.getPlaybackInfo()` (~lines 1020–1092) sorts active controllers with `sortedByDescending { it.packageName == currentPlatformPackage }` — that *prefers* the current platform but does not *filter*. The first-pass loop (~lines 1038–1065) returns the first controller it finds in `STATE_PLAYING`. When the current platform is paused (NetEase user-paused via Tutti) and any other music-app or video-app session is playing (Bilibili video, idle QQ Music with autoplay), that foreign session wins and the floating ball's ring jumps to its position.

3. **Freeform single-target lock-in.** `ShizukuLauncher` tracks exactly one target (`currentTargetPkg`, `currentBoundsProvider`, `currentInitialBounds` at ~lines 82–91). `triggerResize(pkg)` at ~line 619 short-circuits with `if (target != pkg) return`. `PlayerAccessibilityService.onAccessibilityEvent` calls `triggerResize(pkg)` on `TYPE_WINDOWS_CHANGED`. Net effect: after a NetEase→QQ Music switch, the old NetEase freeform task has no live watchdog (its generation was bumped) and any accessibility event for it is silently dropped. HyperOS's HOME gesture then re-surfaces NetEase as a visible square window.

## Goals / Non-goals

**Goals**
- Album cover spins when the current song plays, freezes when it pauses, survives same-song pause/resume and cross-song transitions within the same platform.
- Floating ball's progress ring reflects *only* the current platform's playback, never a stray PLAYING session from another music or video app.
- Every freeform task Tutti has launched in the session stays off-screen across HOME/Recents/platform-switch transitions.
- Hand the user an adb recipe to verify all three live on device.

**Non-goals**
- No new permissions, no foreground-mode changes, no background-song-switching attempt, no new test framework.

## Decisions

### Decision 1 — Always rebuild the rotation animator on PLAYING; never resume a cancelled animator

We replace the resume-or-cancel-or-create branch with a single rule: on every PLAYING-state observation, **if no animator exists or the animator is not running, build a fresh `ObjectAnimator.ofFloat(view, View.ROTATION, fromAngle, fromAngle + 360f)` with `RepeatCount.INFINITE`, `RepeatMode.RESTART`, `INTERPOLATOR=Linear`, duration ~10 000 ms.** The `fromAngle` carry-over comes from reading `view.rotation` before cancelling the old one, so a pause→play cycle has zero visible angle jump.

`stopCoverRotation` keeps the animator object's *last-set rotation* on the View (via `view.rotation = currentAngle`) but tears the animator down. The next PLAYING tick will then create a new animator from `currentAngle`.

**Why not just call `pause()` instead of `cancel()`?** `ObjectAnimator.pause()` is reliable on API 19+ and would let `resume()` work, but it has known edge cases on HyperOS (animator survives across configuration changes in a stuck state). Always rebuilding from the live rotation is simpler and has equivalent visual continuity.

**Alternatives considered**
- *Canvas redraw loop driven by progress poller*: rejected — couples the rotation rate to MediaController poll frequency and creates a visible stutter at 500 ms ticks.
- *XML `<rotate>` `Animation`*: rejected — `Animation` requires `View.startAnimation`/`clearAnimation`, which sets a `Transformation` rather than the `rotation` property; harder to read the current angle for carry-over.

### Decision 2 — Filter (not sort) controllers by `currentPlatformPackage` inside `getPlaybackInfo()`

Refactor `getPlaybackInfo()` to:
1. Read `currentPlatformPackage` (volatile snapshot).
2. If null/empty, return null (no current platform → no progress to report).
3. Look up the controller for *that exact package* via the existing `activeControllers` map. If absent, return a synthetic "paused at last-known position" info (using `lastKnownPositionByPackage[currentPlatformPackage]`) so the floating ball does not snap to 0.
4. If present, return that controller's state directly — never iterate other controllers.

A new instance field `lastKnownPositionByPackage: MutableMap<String, Long>` is updated on every successful read; it lets the ring keep showing a reasonable position even when the music app is killed.

**FloatingWindowService side**: keep `updateProgress()` / `updateProgressFromRemote()` as the only callers of `getPlaybackInfo()`. Audit and remove any direct `MediaSessionManager` or `MediaController` lookups inside the floating window — the investigation found none today, but the spec records this so a future refactor cannot reintroduce the bug.

**Alternatives considered**
- *Keep the prefer-sort and add a secondary "playing only counts if it matches current platform" check*: rejected — easy to forget on a future edit; the filter approach is structurally safer.
- *Push `currentPlatformPackage` into a `StateFlow<String?>` and have the ball collect it*: rejected as overscope — single-field filter is sufficient for this bug.

### Decision 3 — Per-package off-screen bounds memory + accessibility re-hides any music-app package

Add `pkgOffscreenBounds: ConcurrentHashMap<String, Rect>` to `ShizukuLauncher`. Populate it from `scheduleResize(pkg, boundsProvider)` on every launch; never evict on platform switch.

Change `triggerResize(pkg)` so that:
- If `pkg` is in `pkgOffscreenBounds`, run the `am task resize` for the freeform task matching `pkg` to those stored bounds — regardless of `currentTargetPkg`.
- The existing single-target watchdog stays for the active target (it polls dumpsys for the active package's task ID more aggressively), but the *secondary* re-hide path for prior packages is event-driven via accessibility.

`PlayerAccessibilityService.onAccessibilityEvent` already calls `triggerResize(event.packageName as String)` for music-app packages on `TYPE_WINDOWS_CHANGED`. After this change that call will no longer be no-op'd by the `target != pkg` guard.

**Why not iterate every entry of `pkgOffscreenBounds` from the watchdog instead?** That works too but raises steady-state shell-IPC cost (4 packages × 400 ms tick = 10 IPC/s in idle). Event-driven via accessibility costs only when HyperOS actually surfaces a task. We keep the watchdog single-target for the active package because that one resizes most frequently (Bilibili player rebound at +3 s, NetEase landscape transitions, etc.).

**Alternatives considered**
- *Force-stop the prior platform's app on switch*: rejected — destroys the in-app queue state and forces a fresh cold-launch next time the user returns to that platform.
- *Move the freeform tasks to a hidden virtual display*: rejected — requires `CAPTURE_VIDEO_OUTPUT` (signature-protected), not grantable via Shizuku.

## Risks / Trade-offs

- **Rotation animator rebuild on every PLAYING tick is wasteful if state oscillates.** Mitigated by an "already running" early-return — only rebuild when `coverRotationAnimator == null || !coverRotationAnimator.isRunning`.
- **`pkgOffscreenBounds` grows unboundedly across long sessions.** In practice it caps at 4 entries (the four music-app packages). No eviction needed.
- **Accessibility event for foreign package can fire in a tight loop** (HyperOS sometimes spams TYPE_WINDOWS_CHANGED). Add a 200 ms throttle per package inside `triggerResize` to avoid IPC spam.
- **Bilibili "audio" content has a music-style MediaSession; filtering it out is fine when current platform is not Bilibili.** When the current platform *is* Bilibili the filter happens to be the same as today — no regression.

## Migration

None. No persisted state changes, no SharedPreferences schema bump, no Room migration.

## adb Verification Recipe

After `pixi run deploy-release` on a Shizuku-enabled HyperOS device:

```bash
PIXI=/home/lue/.pixi/bin/pixi
ADB=$HOME/Android/Sdk/platform-tools/adb

# Clear log buffer + tail relevant tags
$ADB logcat -c
$ADB logcat -s "FloatingWindowService:*" "MediaMonitorService:*" \
              "ShizukuLauncher:*" "PlayerAccessibilityService:*" "PlaybackService:*" &

# --- Fix 1: rotation ---
# 1. Play a NetEase song from the floating ball.
# 2. Visually confirm cover rotates.
# 3. Pause via the ball; confirm cover freezes (does not snap to 0°).
# 4. Resume; confirm rotation continues from the same angle.
# Expected log: "startCoverRotation: built new animator from angle=NN.N°"

# --- Fix 2: progress source ---
# 1. Play a NetEase song; let it run 30s.
# 2. In Bilibili app, open a video; let it autoplay.
# 3. Return to home; observe the floating-ball ring.
# 4. Pause NetEase via the ball.
# Expected: ring stays at NetEase's paused position, does NOT jump to Bilibili's video position.
# Verify: $ADB shell dumpsys media_session | grep -E "package|state"
# Expected log: "getPlaybackInfo: pkg=com.netease.cloudmusic filtered; ignored com.tencent.qqmusic, tv.danmaku.bili"

# --- Fix 3: HOME hides all freeform ---
# 1. Background-mode play: NetEase → confirm freeform task off-screen.
# 2. Switch to QQ Music → confirm QQ Music freeform task off-screen.
# 3. Trigger HOME gesture from QQ Music freeform area.
# Expected: home screen shows no music-app windows.
# Verify:  $ADB shell dumpsys activity activities | grep -E "freeform|Bounds" | head -40
# Expected log: "triggerResize(com.netease.cloudmusic): pkg in pkgOffscreenBounds (size=2), resizing to Rect(...)"
# Expected: every freeform task for a music-app package has Bounds[left >= screenWidth].
```

A successful run satisfies all three new specs.

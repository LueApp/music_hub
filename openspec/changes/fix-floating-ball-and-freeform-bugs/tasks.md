## 1. Bug 1 — Album cover rotation

- [x] 1.1 In `android-app/app/src/main/java/com/musichub/service/FloatingWindowService.kt`, rewrite `startCoverRotation(view)` so it (a) reads `view.rotation` as `fromAngle`, (b) cancels any existing `coverRotationAnimator` only if its target view differs OR if it is not currently running, (c) builds a fresh `ObjectAnimator.ofFloat(view, View.ROTATION, fromAngle, fromAngle + 360f)` with `repeatCount = INFINITE`, `repeatMode = RESTART`, `interpolator = LinearInterpolator`, `duration = 10_000L`, and starts it. Add an early-return if the animator is already running on the same view.
- [x] 1.2 In the same file, rewrite `stopCoverRotation()` so it persists the current angle (`view.rotation = animator.animatedValue as Float`) before cancelling and nulling out the animator. Remove any `pause()`/`resume()` call paths.
- [x] 1.3 Audit `updateMiniBall()` and `updateProgress()` so that `startCoverRotation` is invoked on every transition into PLAYING (idempotent thanks to 1.1), and `stopCoverRotation` is invoked on every transition out of PLAYING — including the "no current song" idle state.
- [x] 1.4 Add a single `Log.d("FloatingWindowService", "startCoverRotation: built new animator from angle=%.1f°".format(fromAngle))` inside the rebuild branch so the adb verification recipe in `design.md` can confirm the path was taken.
- [x] 1.5 `pixi run build` — must compile clean.
- [x] 1.6 Manual verify on device per the "Fix 1: rotation" block in `design.md` (visual: spin → pause-no-snap → resume-no-jump). **Note:** initial `ObjectAnimator` approach broke on devices with `animator_duration_scale=0`; switched to a manual `Handler` tick (~30 fps) to bypass the system scale.

## 2. Bug 2 — Progress source filter

- [x] 2.1 In `android-app/app/src/main/java/com/musichub/service/MediaMonitorService.kt`, add an instance field `private val lastKnownPositionByPackage = ConcurrentHashMap<String, Long>()`.
- [x] 2.2 Rewrite `getPlaybackInfo()` to: (a) snapshot `currentPlatformPackage`; (b) return `null` if blank; (c) look up the controller for that exact package in `activeControllers`; (d) if present, read its `position`/`duration`/`state`, update `lastKnownPositionByPackage[pkg]`, and return that info; (e) if absent, return a synthetic info object with `state = PlaybackState.STATE_PAUSED`, `position = lastKnownPositionByPackage[pkg] ?: 0L`, and the duration of the last-known song.
- [x] 2.3 Remove or comment out the `sortedByDescending { it.packageName == currentPlatformPackage }` block and any first-pass loop that scans foreign controllers — replace with the direct `activeControllers[pkg]` lookup.
- [x] 2.4 Add `Log.d("MediaMonitorService", "getPlaybackInfo: pkg=$pkg filtered; ignored ${activeControllers.keys.filter { it != pkg }}")` once per pkg change so the adb recipe can confirm filtering.
- [x] 2.5 Audit `FloatingWindowService.updateProgress()` and `updateProgressFromRemote()` to confirm they only call through `MediaMonitorService.getInstance()?.getPlaybackInfo()`; no direct `MediaSessionManager.getActiveSessions()` or per-package `MediaController(...)` instantiation. (Investigation suggests this is already the case — confirm and add a Kotlin `// SPEC: current-platform-playback-isolation` comment marker at the call site so future refactors notice the constraint.)
- [x] 2.6 `pixi run build` — must compile clean.
- [x] 2.7 Manual verify on device per the "Fix 2: progress source" block in `design.md` (NetEase paused + Bilibili playing → ring stays on NetEase). **Follow-up:** found two related regressions — (a) `getPlaybackInfo` returned null while QQ Music metadata was still loading (~6s), causing `schedulePlaybackTimeout` to skip the song; relaxed to return live `isPlaying` even when `duration == 0`. (b) `MainActivity.onResume → rebindMediaMonitor` toggled the NotificationListener on every UI return, recreating `MediaMonitorService` and nulling `currentPlatformPackage`; added an early-return when the service is already bound.

## 3. Bug 3 — Multi-task freeform hide

- [x] 3.1 In `android-app/app/src/main/java/com/musichub/service/ShizukuLauncher.kt`, add a process-singleton `private val pkgOffscreenBounds = ConcurrentHashMap<String, Rect>()` and populate it inside `scheduleResize(pkg, boundsProvider, initialBounds)` using `boundsProvider.invoke()` (or `initialBounds`) — store under `pkgOffscreenBounds[pkg] = computedBounds`. Do **not** evict on platform switch.
- [x] 3.2 Modify `triggerResize(pkg)`: remove the `if (target != pkg) return` guard. Instead, look up `pkgOffscreenBounds[pkg]` — if present, run the existing `am task resize <taskId> <l> <t> <r> <b>` shell against the freeform task matching `pkg`. If absent (unknown package), return early as today.
- [x] 3.3 Add per-package throttle inside `triggerResize`: `private val lastTriggerAtMs = ConcurrentHashMap<String, Long>()`; drop calls within 200 ms of the previous call for the same `pkg` to avoid IPC spam from HyperOS event storms.
- [x] 3.4 Keep the existing single-target watchdog (it still polls the active `currentTargetPkg`). Verify it no longer cancels re-hides for prior packages — those are now event-driven via accessibility (see 3.5).
- [x] 3.5 In `android-app/app/src/main/java/com/musichub/service/PlayerAccessibilityService.kt`, confirm the `TYPE_WINDOWS_CHANGED` branch calls `ShizukuLauncher.triggerResize(event.packageName as String)` for every music-app package (NetEase, QQ Music, Kugou, Bilibili) — not gated on the active target. Adjust if it currently filters.
- [x] 3.6 Add `Log.d("ShizukuLauncher", "triggerResize($pkg): pkg in pkgOffscreenBounds (size=${pkgOffscreenBounds.size}), resizing to $bounds")` so the adb recipe can confirm the new path.
- [x] 3.7 `pixi run build` — must compile clean.
- [x] 3.8 Manual verify on device per the "Fix 3: HOME hides all freeform" block in `design.md` — confirmed via log: `triggerResize(com.tencent.qqmusic): pkg in pkgOffscreenBounds (size=2), resizing to Rect(...)` fires for prior platforms regardless of `currentTargetPkg`.

## 4. Cross-cutting verification and ship

- [x] 4.1 `pixi run build-release` — release-mode compile must pass.
- [x] 4.2 `pixi run deploy-release` to the connected Shizuku-enabled HyperOS device.
- [x] 4.3 Run all three adb verification blocks from `design.md` end-to-end, in one session, with the logcat tail capturing every TAG listed.
- [x] 4.4 Confirm all three spec scenarios in `specs/ball-cover-rotation/spec.md`, `specs/current-platform-playback-isolation/spec.md`, `specs/freeform-multi-task-hide/spec.md` hold on device.
- [x] 4.5 Commit per the repo's commit-after-every-fix convention with a single message that lists the three fixes.
- [ ] 4.6 After dev→master merge, follow the versioning rule in `CLAUDE.md` (PATCH bump — these are bug fixes), tag and push: `git tag v<new> && git push origin master --follow-tags`.

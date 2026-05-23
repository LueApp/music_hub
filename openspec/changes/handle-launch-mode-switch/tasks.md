## 1. ShizukuLauncher purge primitives

- [x] 1.1 Add `ShizukuLauncher.purgeMusicAppTasks(context, excludePackages: Set<String> = emptySet()): PurgeResult` returning per-package counts, a `total`, fallback-used set, and a `skipped` set. Internally runs ONE shell script (see design Decision 2) that dumps `dumpsys activity activities` once, extracts every task id whose `Hist` line matches any non-excluded music-app package, runs `am stack remove-task <tid>` for each, and prints `PKG=<pkg> COUNT=<n> FALLBACK=<0|1>` per package plus a final `TOTAL=<n>`. Excluded packages are not included in the script at all and appear in `PurgeResult.skipped`. Parse stdout into `PurgeResult(perPackage: Map<String,Int>, total: Int, fallbackUsed: Set<String>, skipped: Set<String>)`.
- [x] 1.2 Inside the script, if any `am stack remove-task` returns non-zero for a given package, run `am force-stop <pkg>` once for that package and mark the package as fallback-used in the `PurgeResult`. Force-stop counts as one purge for the per-package count.
- [x] 1.3 Guard `purgeMusicAppTasks` on `status(context) == READY`; return `PurgeResult(emptyMap(), 0, emptySet(), emptySet())` when Shizuku is not ready (caller distinguishes via `status` check).
- [x] 1.4 Log via `Log.i(TAG, ...)` the full result line for diagnostics: `purgeMusicAppTasks: total=N perPackage={netease=2, qqmusic=5, bilibili=1, kugou=0} fallback={qqmusic} skipped={netease}`.

## 2. LaunchModeSwitcher entry point

- [x] 2.1 Create `android-app/app/src/main/java/com/musichub/service/LaunchModeSwitcher.kt` as a Kotlin object with `fun onModeChanged(context: Context, fromMode: String, toMode: String)`.
- [x] 2.2 Implement same-mode no-op guard: early-return when `fromMode == toMode`.
- [x] 2.3 Implement Shizuku-not-ready branch: post `R.string.launch_mode_switch_no_shizuku_cn` Toast formatted with the Chinese mode label (`后台` / `前台`), call `ShizukuLauncher.clearTargetState()`, return.
- [x] 2.4 Implement happy path: dispatch a single background `Thread { ... }.start()` named `LaunchModeSwitcher`. Inside the thread call `ShizukuLauncher.purgeMusicAppTasks(appCtx, excludePackages)`, then `ShizukuLauncher.clearTargetState()`, then post the success Toast on `Handler(Looper.getMainLooper())` using `R.string.launch_mode_switch_done_cn` with `total` and Chinese mode label.
- [x] 2.5 Wrap the thread body in try/catch and post `R.string.launch_mode_switch_failed_cn` Toast on exception. Still call `clearTargetState()` in the catch block so internal state stays consistent.
- [x] 2.6 Compute `excludePackages` via `currentlyPlayingPackages()` helper: union of `MediaMonitorService.getCurrentPlatformPackage()` and every `ShizukuLauncher.musicAppPackages()` entry where `MediaMonitorService.hasActiveController(pkg)` is true. Returns empty set when `MediaMonitorService.getInstance()` is null.
- [x] 2.7 Add `ShizukuLauncher.promoteTaskToFullscreen(context, pkg, deepLink)` that re-delivers the deep link via Shizuku with `am start --windowingMode 1 -a android.intent.action.VIEW -d <deeplink> -p <pkg> -f 0x10000000`. The `--windowingMode 1` hint flips the existing task's windowing mode to fullscreen without destroying the Activity stack. Audio Service unaffected. Returns true on `am start` exit 0.
- [x] 2.8 In `LaunchModeSwitcher.onModeChanged`, after the purge, when `toMode == LAUNCH_MODE_FOREGROUND`, get `PlaybackService.getInstance()?.getCurrentSong()` and call `promoteTaskToFullscreen(ctx, Platforms.PACKAGE_NAMES[song.platform], song.deepLink)` iff that package is in `excludePackages`.

## 7a. On-device verification (Shizuku ready)

- [x] 7a.1 With launch_mode=background, play Bilibili song; confirm task `mode=freeform`. Toggle to foreground via Settings. Confirm task flips to `mode=fullscreen` (same task id), audio position polling shows zero discontinuity, Bilibili player visible fullscreen with Tutti overlay on top.

## 3. PlaybackService wiring

- [x] 3.1 In `PlaybackService.kt:217`, replace the body of `launchModePrefListener` so that when `key == "launch_mode"`, it reads the previous value from a stored field (`@Volatile private var lastLaunchMode: String`), the new value from `prefs.getString(key, DeepLinkLauncher.LAUNCH_MODE_BACKGROUND)`, and dispatches both to `LaunchModeSwitcher.onModeChanged(applicationContext, lastLaunchMode, newMode)`. Update `lastLaunchMode` after dispatch.
- [x] 3.2 In `PlaybackService.onCreate` / `onStartCommand`, initialize `lastLaunchMode` from the current pref value at service start so the first toggle has the correct `fromMode`.
- [x] 3.3 Delete the now-redundant inline `ShizukuLauncher.clearTargetState()` call inside `launchModePrefListener` — `LaunchModeSwitcher` owns that call.

## 4. DeepLinkLauncher cleanup

- [x] 4.1 In `DeepLinkLauncher.launchForeground` (around line 117), keep the `ShizukuLauncher.clearTargetState()` call — it remains the per-launch reset for foreground mode (covers the "user toggled mode then immediately launched a song" race). Verify the comment block still accurately describes the call's purpose; tighten it if the new `LaunchModeSwitcher` makes part of the comment redundant.

## 5. String resources

- [x] 5.1 Add three strings to `android-app/app/src/main/res/values/strings.xml`:
  - `launch_mode_switch_done_cn`: `已清理 %1$d 个旧任务，切换到%2$s模式`
  - `launch_mode_switch_no_shizuku_cn`: `已切换到%1$s模式，但需要 Shizuku 才能清理旧任务`
  - `launch_mode_switch_failed_cn`: `切换模式时清理任务失败：%1$s`
- [x] 5.2 Add helper at `LaunchModeSwitcher` companion or top-level: `private fun modeLabelCn(mode: String): String = if (mode == DeepLinkLauncher.LAUNCH_MODE_FOREGROUND) "前台" else "后台"`.

## 6. Build verification

- [x] 6.1 `pixi run build` — confirm `app-debug.apk` builds without warnings related to the new files.
- [ ] 6.2 `pixi run deploy` to a HyperOS device with all four music apps installed and Shizuku granted.

## 7. Manual device verification — Shizuku READY

- [ ] 7.1 With `launch_mode=background`, at least one song played per platform, and **playback paused** (or no MediaController active), toggle to `foreground` in Settings. Confirm Toast `已清理 N 个旧任务，切换到前台模式` with N ≥ 4. Confirm `dumpsys activity activities | grep -E "(cloudmusic|qqmusic|danmaku.bili|kugou.android)"` shows no remaining music-app tasks.
- [ ] 7.1b With NetEase playing in `background` mode (off-screen freeform), toggle to `foreground`. Confirm (a) audio continues uninterrupted, (b) NetEase window expands to fullscreen and appears on top, (c) Toast count reflects only non-NetEase task removals, (d) `dumpsys activity activities` shows NetEase task in fullscreen / non-freeform windowing.
- [ ] 7.1c With NetEase playing in `foreground` mode (fullscreen), toggle to `background`. Confirm (a) audio continues uninterrupted, (b) NetEase stays fullscreen (no auto-shrink), (c) Toast count reflects only non-NetEase task removals.
- [ ] 7.2 Launch a song under `foreground` mode for each platform. Confirm normal foreground launch behavior (full app switch, NetEase landscape workaround if applicable, QQ Music a11y tap if a11y granted).
- [ ] 7.3 Toggle back to `background`. Confirm Toast `已清理 N 个旧任务，切换到后台模式` with N ≥ 1 per platform that was touched in step 7.2.
- [ ] 7.4 Launch a song under `background` mode and confirm freeform off-screen launch behavior (music plays, floating ball visible, no music-app UI on screen).
- [ ] 7.5 Rapid-toggle stress: tap the ListPreference 4 times within 2 s (bg→fg→bg→fg→bg). Confirm at least 4 Toasts appear in order, no crashes, final pref state matches the last selection.

## 8. Manual device verification — Shizuku NOT ready

- [ ] 8.1 Revoke Shizuku permission for Music Hub (`pm revoke` via adb or in the Shizuku app). Toggle `launch_mode`. Confirm Toast `已切换到<mode>模式，但需要 Shizuku 才能清理旧任务`.
- [ ] 8.2 Confirm the next song launch uses the new mode (background mode falls back to fullscreen launch with the existing `Background mode fell back to fullscreen: shizukuStatus=...` log line — pre-existing behavior, not regressed).
- [ ] 8.3 Re-grant Shizuku, toggle once more, confirm purge path resumes.

## 9. Documentation

- [x] 9.1 Add a one-line entry to `.wolf/memory.md` summarizing the change.
- [ ] 9.2 If a new project-level constraint emerged during testing (e.g., a specific HyperOS quirk in `am stack remove-task`), append a `## Do-Not-Repeat` entry to `.wolf/cerebrum.md` per OpenWolf protocol.
- [ ] 9.3 If a fix log is warranted, append to `.wolf/buglog.json` per OpenWolf protocol.

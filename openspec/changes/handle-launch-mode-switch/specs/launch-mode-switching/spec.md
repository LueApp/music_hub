## ADDED Requirements

### Requirement: Mode-switch handler MUST run on every `launch_mode` toggle

When the `launch_mode` SharedPreferences value changes between `background` and `foreground`, the system SHALL run a centralized mode-switch handler before any subsequent song-launch invocation can read the new value.

#### Scenario: User toggles from background to foreground in Settings

- **WHEN** the user changes the `launch_mode` ListPreference from `background` to `foreground`
- **THEN** `PlaybackService.launchModePrefListener` SHALL dispatch the event to `LaunchModeSwitcher.onModeChanged(context, fromMode="background", toMode="foreground")` on a background thread
- **AND** the dispatch SHALL complete before the next user-initiated playback action processes through `DeepLinkLauncher.launch`

#### Scenario: User toggles from foreground to background in Settings

- **WHEN** the user changes the `launch_mode` ListPreference from `foreground` to `background`
- **THEN** the same handler SHALL run with `fromMode="foreground"` and `toMode="background"`

#### Scenario: Pref re-saved with the same value

- **WHEN** the `launch_mode` SharedPreferences value is rewritten without an actual change (e.g., system re-emits the listener on process resume)
- **THEN** `LaunchModeSwitcher.onModeChanged` SHALL early-return without running the purge
- **AND** SHALL NOT post a Toast

### Requirement: Mode-switch handler MUST purge stale music-app tasks across non-playing platforms

On a real mode change (`fromMode != toMode`), the handler SHALL purge every task belonging to `com.netease.cloudmusic`, `com.tencent.qqmusic`, `tv.danmaku.bili`, and `com.kugou.android` via Shizuku, regardless of the task's windowing mode or visibility — EXCEPT for any package that is currently playing audio (i.e., reported as the active platform by `MediaMonitorService.getCurrentPlatformPackage()` or owning a live MediaController per `MediaMonitorService.hasActiveController`). Excluded packages SHALL be untouched (no `am stack remove-task`, no `am force-stop`) and SHALL appear in `PurgeResult.skipped`. This preserves the currently-playing song across the mode toggle; the new mode takes full effect on the next song launch.

#### Scenario: Background-to-foreground purges off-screen freeform tasks for every non-playing music app

- **WHEN** the user switches from `background` to `foreground` while NetEase, QQ Music, Bilibili, and Kugou each have one or more off-screen freeform tasks created by prior `launchBackground` invocations AND no song is currently playing
- **THEN** the handler SHALL identify every task whose `Hist` line in `dumpsys activity activities` contains `<package>/` for any of the four packages
- **AND** SHALL invoke `am stack remove-task <tid>` for each identified task
- **AND** SHALL count the number of successfully removed tasks for inclusion in the user-facing Toast

#### Scenario: Currently-playing package is excluded from the purge

- **WHEN** the user switches mode while one music app (e.g. NetEase) is actively playing audio
- **THEN** `LaunchModeSwitcher.onModeChanged` SHALL query `MediaMonitorService.getCurrentPlatformPackage()` and `MediaMonitorService.hasActiveController(pkg)` to determine the currently-playing package set
- **AND** SHALL pass that set as the `excludePackages` argument to `ShizukuLauncher.purgeMusicAppTasks`
- **AND** the playing app's task(s) SHALL NOT receive `am stack remove-task` or `am force-stop`
- **AND** audio playback SHALL continue uninterrupted across the mode toggle
- **AND** the playing package SHALL appear in `PurgeResult.skipped`

#### Scenario: MediaMonitorService is unavailable

- **WHEN** the handler runs but `MediaMonitorService.getInstance()` returns null (service not bound)
- **THEN** `excludePackages` SHALL be empty
- **AND** the purge SHALL proceed across all four packages
- **AND** any audio that was playing without an active MediaController in the service SHALL be terminated as a consequence — accepted as a degraded path because without MediaMonitor we cannot know what is playing

#### Scenario: Foreground-to-background purges leftover fullscreen tasks for non-playing packages

- **WHEN** the user switches from `foreground` to `background` while music-app tasks created by prior `launchForeground` invocations are still in the fullscreen stack AND no song is currently playing
- **THEN** the handler SHALL purge those tasks identically to the freeform case
- **AND** the next `launchBackground` invocation for a purged platform SHALL find no candidate task in the reuse-path lookup and create a fresh freeform task via the cold path

#### Scenario: QQ Music has multiple lingering tasks and is currently playing

- **WHEN** QQ Music has accumulated 3 or more invisible freeform tasks AND QQ Music is the currently-playing platform
- **THEN** all QQ Music tasks SHALL be left untouched (entire package excluded)
- **AND** the per-package count for QQ Music SHALL be 0 with the package recorded in `PurgeResult.skipped`

#### Scenario: QQ Music has multiple lingering tasks and is NOT currently playing

- **WHEN** QQ Music has 3+ ghost tasks but a different platform (e.g. Bilibili) is currently playing
- **THEN** every QQ Music task SHALL be discovered and purged in a single dumpsys+loop pass

#### Scenario: Bilibili video-chain tasks (not currently playing)

- **WHEN** Bilibili has both an `IntentHandlerActivity` task and a chained `UnitedBizDetailsActivity` task from a prior video deep-link launch AND Bilibili is not currently playing
- **THEN** both tasks SHALL be discovered and purged

### Requirement: Currently-playing exclude set MUST be the single package matching `PlaybackService.getCurrentSong().platform`

The exclude set passed to `ShizukuLauncher.purgeMusicAppTasks` SHALL contain at most one package: the one returned by `Platforms.PACKAGE_NAMES[PlaybackService.getInstance()?.getCurrentSong()?.platform]`. The exclude set SHALL NOT be widened by also including every package returning true from `MediaMonitorService.hasActiveController(pkg)`.

#### Scenario: Both NetEase and QQ Music have live MediaControllers but only QQ Music is current

- **WHEN** `LaunchModeSwitcher.onModeChanged` runs while Tutti's queue's current song is on QQ Music and NetEase's MediaController is still alive from prior playback
- **THEN** `currentlyPlayingPackages()` SHALL return only `setOf("com.tencent.qqmusic")` — NetEase's live controller SHALL NOT add it to the exclude set
- **AND** the purge SHALL target NetEase's task(s), eliminating the stale freeform window the user was seeing

#### Scenario: Queue empty

- **WHEN** `PlaybackService.getInstance()?.getCurrentSong()` returns null (queue empty, or service not bound)
- **THEN** `currentlyPlayingPackages()` SHALL return `emptySet()`
- **AND** the purge SHALL target every supported music-app package

### Requirement: Background → foreground MUST promote the currently-playing task to fullscreen

When the user toggles `launch_mode` from `background` to `foreground` and the currently-playing song's platform package is in the exclude set, the handler SHALL re-deliver the current song's deep link via Shizuku with `am start --windowingMode 1 -a android.intent.action.VIEW -d <deeplink> -p <pkg> -f 0x10000000`. The `--windowingMode 1` (WINDOWING_MODE_FULLSCREEN) hint flips the existing task's windowing mode from freeform to fullscreen; the Activity and audio Service are unaffected so playback continues without interruption.

#### Scenario: Bilibili is playing off-screen in freeform; user switches to foreground

- **WHEN** `LaunchModeSwitcher.onModeChanged` runs with `fromMode="background"`, `toMode="foreground"` and `PlaybackService.getInstance()?.getCurrentSong()` returns a song with `platform="bilibili"`
- **THEN** after the purge of non-playing packages, the handler SHALL call `ShizukuLauncher.promoteTaskToFullscreen(context, "tv.danmaku.bili", currentSong.deepLink)`
- **AND** the Shizuku invocation SHALL be `am start --windowingMode 1 -a android.intent.action.VIEW -d <deeplink> -p tv.danmaku.bili -f 0x10000000`
- **AND** audio playback SHALL continue uninterrupted (position polling shows no reset)
- **AND** the Bilibili task SHALL flip from `mode=freeform` to `mode=fullscreen` (same task id preserved)
- **AND** the user SHALL see the Bilibili fullscreen player on-screen

#### Scenario: Current song's package is not the one playing audio

- **WHEN** the current song's platform package differs from the actually-playing package (e.g., user manually started a song in another app outside Tutti)
- **THEN** the handler SHALL only attempt promotion if `Platforms.PACKAGE_NAMES[currentSong.platform]` is in `excludePackages`
- **AND** SHALL skip promotion otherwise (no fullscreen re-delivery)

#### Scenario: No current song (queue empty)

- **WHEN** `PlaybackService.getInstance()?.getCurrentSong()` returns null
- **THEN** the handler SHALL skip promotion entirely
- **AND** the mode switch SHALL still complete (Toast and `clearTargetState` proceed)

#### Scenario: Foreground → background does NOT auto-shrink the playing task

- **WHEN** `LaunchModeSwitcher.onModeChanged` runs with `fromMode="foreground"`, `toMode="background"`, and a music-app package is currently playing in fullscreen
- **THEN** the handler SHALL NOT call `promoteTaskToFullscreen` (only applies to bg→fg)
- **AND** SHALL NOT call any inverse "demote to freeform off-screen" routine
- **AND** the playing app SHALL keep its fullscreen state until the user triggers the next song-switch, which routes through the new mode's launch path

### Requirement: Purge MUST fall back to `am force-stop` per-package when `am stack remove-task` fails

For each music-app package, if `am stack remove-task` returns a non-zero exit code for any of that package's tasks, the handler SHALL invoke `am force-stop <package>` as a fallback for that single package.

#### Scenario: HyperOS rejects remove-task for the current foreground task

- **WHEN** one of QQ Music's tasks is the current top-of-stack activity and `am stack remove-task` exits non-zero for it
- **THEN** the handler SHALL run `am force-stop com.tencent.qqmusic` once
- **AND** the per-package fallback SHALL count as one successful purge for the totalled count, since the entire process is terminated

#### Scenario: All packages succeed without fallback

- **WHEN** every `am stack remove-task` exits zero
- **THEN** the handler SHALL NOT invoke any `am force-stop`

### Requirement: Internal Shizuku tracking state MUST be cleared on every transition

After (and only after) the on-device task purge has been attempted, the handler SHALL call `ShizukuLauncher.clearTargetState()` to drop `currentTargetPkg`, `currentBoundsProvider`, `currentInitialBounds`, the `pkgManaged` set, and to increment `resizeGeneration`.

#### Scenario: In-flight watchdog thread exits cleanly after transition

- **WHEN** the user toggles mode while a `ShizukuLauncher.scheduleResize` watchdog thread is mid-iteration
- **THEN** the handler's `clearTargetState` call SHALL increment `resizeGeneration`
- **AND** the watchdog thread SHALL exit on its next iteration without further `am task resize` calls

#### Scenario: No-Shizuku transition still clears in-process state

- **WHEN** the handler runs the transition but Shizuku is not `READY` (purge is skipped)
- **THEN** the handler SHALL still call `ShizukuLauncher.clearTargetState()` so that any inherited tracking from a prior session does not affect subsequent launches

### Requirement: Mode-switch handler MUST surface result via a single Chinese Toast

The handler SHALL post exactly one Toast to the main thread per transition, summarizing the outcome.

#### Scenario: Successful purge

- **WHEN** Shizuku is `READY` and the purge completes with a non-negative total count `N`
- **THEN** the handler SHALL post `已清理 N 个旧任务，切换到<mode>模式` where `<mode>` is `后台` for `toMode="background"` or `前台` for `toMode="foreground"`

#### Scenario: Shizuku not available

- **WHEN** `ShizukuLauncher.status(context)` is anything other than `READY`
- **THEN** the handler SHALL post `已切换到<mode>模式，但需要 Shizuku 才能清理旧任务`
- **AND** SHALL NOT attempt the purge

#### Scenario: Purge script throws an exception

- **WHEN** the dumpsys / am-stack script throws or returns a malformed `TOTAL=` line
- **THEN** the handler SHALL post `切换模式时清理任务失败：<short error message>`
- **AND** SHALL still call `ShizukuLauncher.clearTargetState()` so internal state is consistent

### Requirement: Locked-screen launch path MUST be unaffected by mode switching

The locked-screen launch path (`DeepLinkLauncher.launchForLockedScreen`) does not branch on `launch_mode` and SHALL NOT be invoked by the mode-switch handler.

#### Scenario: Mode toggle while phone is locked

- **WHEN** the user toggles `launch_mode` (e.g., via the remote-controller flow with a paired phone screen-off)
- **THEN** the handler SHALL run the purge as in any other transition
- **AND** subsequent locked-screen song launches SHALL continue to route through `launchForLockedScreen` without any new mode-specific handling

### Requirement: Concurrent mode toggles MUST be safe

The handler SHALL be safe to invoke concurrently from rapid pref toggles (e.g., bg → fg → bg within 1 s).

#### Scenario: Rapid toggle sequence

- **WHEN** the user toggles `launch_mode` three times in quick succession (bg → fg → bg → fg)
- **THEN** each invocation SHALL run on its own background thread and complete its purge + `clearTargetState`
- **AND** the final pref value SHALL be the user's most recent selection
- **AND** the Toast for each invocation SHALL appear (Toasts queue naturally via the main looper)

### Requirement: Mode-switch handler MUST run on a background thread

The handler SHALL NOT block the main thread.

#### Scenario: Pref-change listener fires on main thread

- **WHEN** `launchModePrefListener.onSharedPreferenceChanged` fires on the main thread
- **THEN** `LaunchModeSwitcher.onModeChanged` SHALL dispatch the dumpsys/purge work to a `Thread { ... }.start()` named `LaunchModeSwitcher`
- **AND** the main-thread listener SHALL return within 5 ms of receiving the event

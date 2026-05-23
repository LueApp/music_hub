## Context

Tutti's launch architecture exposes two `launch_mode` values, swapped via a `ListPreference` in `SettingsFragment.kt:224`:

- **`background`** (default) — `DeepLinkLauncher.launchBackground` asks Shizuku to run `am start --windowingMode 5` with bounds pushed past the right screen edge. Music app plays audio in a freeform task whose chrome surface lies off-screen; user controls everything via Tutti's floating ball. `ShizukuLauncher.pkgManaged` tracks every package launched this way for `triggerResize` re-hides.
- **`foreground`** — `DeepLinkLauncher.launchForeground` does a plain `Intent` with `FLAG_ACTIVITY_NEW_TASK`. NetEase landscape gets a CLEAR_TASK rotation workaround; QQ Music gets a 2.5 s `PlayerAccessibilityService.requestClickMiniPlayer()` after launch.

`PlaybackService.launchModePrefListener` (line 217) listens to the pref. It currently only calls `ShizukuLauncher.clearTargetState()` when leaving background, which:
- Clears in-process tracking (`currentTargetPkg`, `currentBoundsProvider`, `pkgManaged`, increments `resizeGeneration`).
- Does nothing to the **on-device tasks themselves** — the off-screen freeform tasks persist in `ActivityManager` until the user kills them or the OS reclaims them under memory pressure.

There is no symmetric handling for `foreground → background`: leftover fullscreen music-app tasks linger and confuse the reuse-path lookup in `ShizukuLauncher.buildLaunchScript` (which only matches `mode=freeform AND visible=true`).

Per-platform pathology, from `.wolf/cerebrum.md`:

- **NetEase**: single PlayerActivity, reused across launches. CLEAR_TASK is the foreground-mode reset path. Off-screen freeform residue at mode-switch is one task per app instance.
- **QQ Music**: creates a *new* task per deep-link launch (do-not-repeat 2026-05-09); old tasks linger as invisible freeform tasks. After heavy use of background mode, can have 5–10 ghost tasks.
- **Bilibili**: video chain `IntentHandlerActivity → UnitedBizDetailsActivity` resets task bounds 2–4 s after launch (do-not-repeat 2026-05-08). Multiple chained tasks.
- **Kugou**: MediaActivity with `launchMode=singleTop`. Single reused task. HTTPS deep link requires `-p com.kugou.android` (do-not-repeat 2026-05-08 / cerebrum learning on `-p` flag).

## Goals / Non-Goals

**Goals:**

- Symmetric handling of both `background → foreground` and `foreground → background` transitions.
- All four supported platforms (NetEase / QQ Music / Bilibili / Kugou) handled uniformly through one purge routine; no per-platform branching at the call site.
- Single user-visible Toast per transition, in Chinese, summarizing what changed and how many tasks were purged.
- Zero behavioral change inside `launchBackground` / `launchForeground` once the purge has run — keep the workaround paths exactly as they are today.
- Graceful degradation when Shizuku is unavailable: the mode preference still flips, but a clarifying Toast directs the user to Shizuku before the new mode will be fully clean.

**Non-Goals:**

- Not preserving music-app internal state (current track position inside NetEase, etc.). Tutti drives the queue via deep links; per-launch state is disposable.
- Not changing the `launch_mode` ListPreference UI, summary copy, or default value.
- Not adding new permissions. Relies on the already-documented `可选`-tier Shizuku grant.
- Not running purges on app launch / process-creation. Limit purge to the explicit user-initiated mode toggle — running on every cold start would surprise users who left a music app open under the *old* mode intentionally.
- Not touching the locked-screen launch path (`launchForLockedScreen`); that path is mode-agnostic.
- Not adding automated tests — this is system-state behavior verified on-device via HyperOS, consistent with existing do-not-repeat entries.

## Decisions

### Decision 1: Purge stale tasks rather than convert windowing mode — except the currently-playing package

**Decision:** On mode switch, kill every task across the four music-app packages via Shizuku `am stack remove-task <tid>`, EXCEPT for any package currently producing audio. Fallback to `am force-stop <pkg>` per non-excluded package if `remove-task` fails or is rate-limited. The currently-playing package is identified via `MediaMonitorService.getCurrentPlatformPackage()` (the package Tutti last directed playback to) plus any package returning true from `MediaMonitorService.hasActiveController(pkg)` (defensive — covers the edge case where the current platform pointer is stale but the controller is alive). The excluded package's task(s) survive the mode toggle untouched, so audio plays through; the new mode takes full effect on the next song-switch.

**Alternatives considered:**

- *(A) Convert freeform → fullscreen via `am task move-stack <tid> 1` (and vice versa).* Rejected — `move-stack` is unreliable across HyperOS versions, sometimes silently no-ops, and even when it succeeds the music app's internal Activity stack may not re-render correctly (e.g., NetEase's `PlayerLandscapeActivity` was launched by an `OrientationEventListener` that fired only on a fresh portrait→landscape transition — moving the stack doesn't re-trigger it). The CLEAR_TASK rotation workaround already relies on starting from a clean slate; trying to "preserve" tasks across modes would re-introduce the very stale-state bugs the rotation hack was written to avoid.
- *(B) Do nothing and trust the OS to clean up.* Rejected — the OS keeps freeform tasks alive for tens of minutes under typical memory pressure on a Xiaomi 24117RK2CC. The user's "next song-switch is broken" experience reliably reproduces within 30 s.
- *(C) Selectively kill only off-screen freeform tasks (left edge > screenWidth) on `bg → fg`.* Rejected — too clever. The simpler "kill non-playing tasks on every transition" rule is correct, easy to reason about, and matches what users intuitively expect.
- *(D) Kill ALL tasks including the currently-playing one.* Rejected after user feedback — killing the playing task kills audio mid-stream, which is worse than the "next song-switch is broken" UX we're trying to fix. Users explicitly want audio continuity across mode toggles. Exclusion of the playing package is the accepted balance.

**Why exclude only the playing package:** Tutti is the source of truth for the playback queue (see `MusicRepository`, `PlaybackService.queue`). Whatever the *non-playing* music apps had loaded internally is downstream of Tutti's deep-link and disposable; whatever the *playing* app has loaded is currently producing audio the user is listening to. The new mode taking effect on the next song-switch is an acceptable delay because the next song is the next time the user cares about mode-specific visual behavior anyway.

### Decision 1b: Background → foreground promotes the playing task via `am start --windowingMode 1` (audio continuity preserved with zero hiccup)

**Decision:** On a `bg → fg` toggle, after the purge of non-playing packages, get the current song from `PlaybackService.getInstance()?.getCurrentSong()` and, iff its platform package is in the exclude set, re-deliver its deep link via Shizuku with an explicit fullscreen windowing-mode hint:

```sh
am start --windowingMode 1 -a android.intent.action.VIEW -d <deeplink> -p <pkg> -f 0x10000000
#         ^^^^^^^^^^^^^^^^^^                                                       ^^^^^^^^^^
#         WINDOWING_MODE_FULLSCREEN (=1)                              FLAG_ACTIVITY_NEW_TASK
```

No `CLEAR_TASK`, no task removal. The existing freeform task's id and Activity stack are preserved; only the windowing mode is flipped. **Audio continues without interruption** — verified on-device by position-poll continuity across the toggle (e.g., 38539 ms → 39539 ms → 40541 ms with no reset and no MediaSession destroy/recreate).

**Why this works on HyperOS where prior approaches did not:** the `--windowingMode 1` hint is honored by `ActivityManager.startActivity` for shell-UID callers (Shizuku) even when the Intent resolves to an existing singleTask activity via `onNewIntent` — the task's windowing mode is reassigned to the hinted value. The hint is only stripped when the caller lacks `MANAGE_ACTIVITY_TASKS`, which shell UID holds.

**Why audio survives:** the Activity is reused (not destroyed). The Service-bound MediaSession is unaffected. Position polling shows zero discontinuity.

**Why only bg→fg, not the inverse:** the user explicitly asked for bg→fg promotion. The symmetric "fg→bg auto-shrink-to-off-screen-freeform" would need `--windowingMode 5` + bounds-resize re-delivery; same mechanism would work but adds surprise behavior the user has not requested.

**Alternatives considered and rejected after on-device testing:**

- *(A) `am stack move-task <tid> 1 true`.* Rejected — on HyperOS Android 14 the call exited 0 but task stayed in freeform; the windowing mode change was silently dropped.
- *(B) `am start ... -f 0x10008000` (NEW_TASK | CLEAR_TASK), no `--windowingMode`.* Rejected — on-device the task id and windowing mode were preserved (still freeform) even after the Activity stack was destroyed and re-created. Audio was briefly interrupted (position reset to ~695 ms) because the MediaSession was recreated. The OS defaults a fresh Activity to the *task's current* windowing mode when no hint is given.
- *(C) `am stack remove-task <tid>` + fresh `am start --windowingMode 1`.* Rejected — `am stack remove-task` does not exist on Android 14 (`Error: unknown command 'remove-task'`). The `am stack` subcommands on this release are `move-task`, `list`, `info`, and `remove` (operates on `STACK_ID`, not `TASK_ID`).
- *(D) Have PlayerAccessibilityService tap HyperOS's freeform-header "maximize" button.* Rejected — UI-fragile, HyperOS-version-dependent, adds responsibility to an a11y service that already has two jobs.

**Note on the purge script (`purgeMusicAppTasks`):** the same `am stack remove-task` non-existence affects the purge — every `remove-task` call exits non-zero and the script falls back to `am force-stop <pkg>`. For non-playing apps this is the intended behavior (we want them gone). The fallback path is what actually does the work on Android 14; the `remove-task` attempt is harmless dead code left for older Android versions where it might exist.

### Decision 2: Single shell script for task discovery + purge

**Decision:** Add a new `ShizukuLauncher.purgeMusicAppTasks()` that runs ONE shell script under Shizuku. The script:

1. Runs `dumpsys activity activities` once.
2. For each of the four music-app packages, extracts every task id (regardless of windowing mode or visibility).
3. Loops `am stack remove-task <tid>` over the collected ids.
4. Prints a per-package count line so Kotlin can format the Toast.

```sh
PKGS="com.netease.cloudmusic com.tencent.qqmusic tv.danmaku.bili com.kugou.android"
DUMP=$(dumpsys activity activities 2>/dev/null)
TOTAL=0
for PKG in $PKGS; do
  TIDS=$(echo "$DUMP" | awk -v pkg="$PKG" '
    /^[[:space:]]*\* Task\{/ { match($0, /#[0-9]+/); cur=substr($0,RSTART+1,RLENGTH-1); has=0 }
    /Hist/ && index($0, pkg"/") > 0 { has=1 }
    /^$/ && has { print cur; has=0 }
  ')
  COUNT=0
  for T in $TIDS; do
    am stack remove-task "$T" >/dev/null 2>&1 && COUNT=$((COUNT+1))
  done
  echo "PKG=$PKG COUNT=$COUNT"
  TOTAL=$((TOTAL+COUNT))
done
echo "TOTAL=$TOTAL"
```

**Alternatives considered:**

- *(A) Per-package `am force-stop`.* Considered as primary. Rejected — `force-stop` revokes any accessibility services bound to the music app (not our problem; the music apps don't ship a11y services) AND it terminates currently-playing audio mid-stream rather than just nuking the Activity stack. `remove-task` is the cleaner equivalent of "close from Recents" — it kills the task but lets the app process die naturally if nothing else holds it. Keep `force-stop` as the fallback when `remove-task` returns non-zero (e.g., HyperOS sometimes rejects `remove-task` for foreground tasks).
- *(B) Iterating `am stack remove-task` per-task from Kotlin, one Shizuku IPC each.* Rejected — each IPC is ~30–50 ms; with 4 packages × up to 10 ghost tasks each that's 1–2 s of latency for the Toast to surface. One shell script keeps it under 200 ms even in pathological cases.

### Decision 3: Mode-switch handler lives in a new `LaunchModeSwitcher` object, not extended onto `DeepLinkLauncher`

**Decision:** Create `service/LaunchModeSwitcher.kt` exposing `fun onModeChanged(context: Context, fromMode: String, toMode: String)`. `PlaybackService.launchModePrefListener` calls this. `DeepLinkLauncher` is untouched except for deleting the now-redundant `ShizukuLauncher.clearTargetState()` call at `launchForeground` entry.

**Exclude-set derivation — narrowed after on-device testing:** the helper `currentlyPlayingPackages()` returns ONLY the package matching `PlaybackService.getInstance()?.getCurrentSong()?.platform`. An earlier version also unioned in every package returning true from `MediaMonitorService.hasActiveController(pkg)` — that was wrong. Music apps frequently keep their MediaController alive after playback stops (NetEase keeps its controller for many seconds after a switch). Including those packages in the exclude set caused the purge to skip stale freeform tasks the user wanted gone. The current song's platform is the single source of truth for "the one app whose audio we deliberately want to keep alive across the toggle."

**Why separate object:** `DeepLinkLauncher` is already a >700-line object accreted with rotation hacks, locked-screen branches, and per-platform package-name special cases. Adding mode-transition logic there mixes "how to launch one song" with "how to migrate global state between modes" — two distinct responsibilities. Keeping `LaunchModeSwitcher` separate lets it be the single audit point for "what happens on a mode toggle" without expanding the launcher further.

**Alternatives considered:**

- *(A) Extend `ShizukuLauncher.clearTargetState()` to also do the purge.* Rejected — `clearTargetState` is also called from `launchForeground` *entry* (every song launch in foreground mode), where purging is not what we want. Mixing the two roles would require a parameter or a new method anyway. Better to keep `clearTargetState` purely internal-state and let the new `LaunchModeSwitcher` orchestrate.

### Decision 4: Toast in Chinese, single line, surfaces both success and Shizuku-unavailable cases

**Decision:** New strings in `res/values/strings.xml`:

```xml
<string name="launch_mode_switch_done_cn">已清理 %1$d 个旧任务，切换到%2$s模式</string>
<string name="launch_mode_switch_no_shizuku_cn">已切换到%1$s模式，但需要 Shizuku 才能清理旧任务</string>
<string name="launch_mode_switch_failed_cn">切换模式时清理任务失败：%1$s</string>
```

`%2$s` (or `%1$s`) is the Chinese mode name — "后台" / "前台" — to match the existing `updateLaunchModeSummary` copy ("当前模式: 前台模式" / "当前模式: 后台模式").

**Alternatives considered:**

- *(A) Snackbar over Toast.* Rejected — the user triggers the mode change from `SettingsFragment`, but the purge runs in `PlaybackService` (a Service has no view tree for a Snackbar host). Toast is the right level for "system-action confirmation" anyway.
- *(B) No Toast (silent transition).* Rejected — the whole point of this change is to make a previously silent state transition visible. A Toast is the minimum-viable surface.

### Decision 5: Same-mode no-op guard

**Decision:** `LaunchModeSwitcher.onModeChanged` early-returns if `fromMode == toMode`. `SharedPreferences.OnSharedPreferenceChangeListener` can fire with no actual value change (e.g., when the underlying file is re-read on process resume); guarding against this prevents spurious purges.

### Decision 6: Threading

**Decision:** Run `onModeChanged` on a background thread (`Thread { ... }.start()` — consistent with how `ShizukuLauncher.triggerResize` already dispatches). Toast is posted to the main looper from the background thread. The pref-change listener fires on the main thread; the actual purge cannot block it because the dumpsys + 4× am-stack sequence is 100–200 ms.

**Why not coroutines:** `ShizukuLauncher` already uses raw `Thread` for its background work, and `LaunchModeSwitcher` only does one thing on one thread. Adding a `CoroutineScope` here would be inconsistent with the rest of `service/`.

## Risks / Trade-offs

- **[Risk] HyperOS may reject `am stack remove-task` on the currently-foreground music app.** → Mitigation: fall back to `am force-stop <pkg>` for any task where `remove-task` exits non-zero. `force-stop` is unconditional. (Side-effect: kills audio mid-stream. Acceptable because the user is intentionally switching modes — playback discontinuity is the expected outcome.)
- **[Risk] User has Shizuku revoked (post-APK-reinstall, per do-not-repeat 2026-05-08).** → Mitigation: `LaunchModeSwitcher` checks `ShizukuLauncher.status(context) == READY` first; if not, surfaces the "需要 Shizuku" Toast. Mode pref still flips so the next launch routes through the new mode, but stale tasks remain on device. User can manually clear them via Recents in this case.
- **[Risk] User toggles the mode rapidly (e.g., bg → fg → bg in <1 s).** → Mitigation: each invocation runs to completion on its own thread; no shared mutable state across invocations beyond the pref itself. Worst case is two purges back-to-back, which is idempotent (second purge finds nothing to do, prints `TOTAL=0`).
- **[Trade-off] Killing tasks loses Bilibili's per-video resume position.** → Acceptable. Tutti's `MediaMonitorService` already tracks Bilibili's near-end / loop-reset signals (do-not-repeat 2026-05-16) and treats end-of-content as song-end; resume position inside the Bilibili app is not currently surfaced to Tutti's queue logic.
- **[Trade-off] Mode toggle now has a visible Toast where it was previously silent.** → Intentional. Confirming the action is the whole point.
- **[Risk] Stale `pkgManaged` if purge succeeds but `ShizukuLauncher.clearTargetState()` is not also called.** → Mitigation: `LaunchModeSwitcher.onModeChanged` ALWAYS calls `ShizukuLauncher.clearTargetState()` after the purge regardless of the from/to direction. The combined "clear in-process tracking + purge on-device tasks" is the transition invariant.
- **[Trade-off] New mode does not fully take effect for the currently-playing app until the next song-switch.** → Accepted to preserve audio. For `bg → fg`, the playing app's off-screen freeform task stays off-screen until the user picks the next song; for `fg → bg`, the playing app's fullscreen task remains fullscreen until then. Users who want immediate visual effect can swipe-close the playing app from Recents (kills audio, same as the rejected kill-all design).
- **[Risk] `currentPlatformPackage` is stale (e.g. Tutti directed playback at X but the user manually opened Y in foreground and Y is now playing).** → Mitigation: `LaunchModeSwitcher.currentlyPlayingPackages()` unions the platform pointer with every package returning true from `MediaMonitorService.hasActiveController(pkg)`. So any of the four music apps with a live MediaController is excluded, even if Tutti's bookkeeping is out of sync.

## Migration Plan

No data migration. Behavioral change only.

- **Deploy**: bundle with normal `dev → master` merge. Triggers PATCH bump per `.wolf/cerebrum.md` versioning rule (bug-fix tier — this is fixing a broken state transition, not adding a new user-visible feature).
- **Rollback**: revert the commit. The pre-change behavior (silent state transition, stale tasks) is restored. No persisted state changes between versions.
- **No-Shizuku fallback validation**: deploy verified by manually testing the four scenarios on a HyperOS device: bg→fg with Shizuku, bg→fg without Shizuku, fg→bg with Shizuku, fg→bg without Shizuku.

## Open Questions

- Should the purge also run when the user re-grants Shizuku permission while the pref is already at `background` (i.e., the "first time Shizuku becomes available" path)? Current proposal says no — only the explicit `launch_mode` toggle triggers a purge. Catching the Shizuku-grant event would require an additional listener and isn't motivated by a concrete user-reported pain point.
- Should `LaunchModeSwitcher.onModeChanged` also dismiss any in-app QQ Music dialogs that may be lingering (the `qqmusic-dialog-dismissal` spec touches this area)? Current proposal says no — `remove-task` nukes the task root, including any modal dialogs the task hosted. If a dialog floats free of a removed task on some HyperOS build, follow-up work.

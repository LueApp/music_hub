## Why

Tutti has two `launch_mode` settings — `background` (freeform via Shizuku, music app pushed off-screen behind the floating ball) and `foreground` (full app switch with NetEase rotation hack, QQ Music a11y mini-player tap, CLEAR_TASK). When the user toggles this preference mid-session, only the internal Shizuku tracking is cleared; **no action is taken on the music-app tasks already running on the device under the old mode**. The result is broken UX on the very next song-switch:

- **`background` → `foreground`**: leftover music-app tasks are still freeform and positioned *fully off-screen* (left > screenWidth). The next foreground launch routes a plain `Intent` + `FLAG_ACTIVITY_NEW_TASK` into the existing off-screen freeform task — user sees no app appear, hears the new song play "from nowhere," and the foreground-mode workarounds (CLEAR_TASK, QQ Music a11y tap, NetEase landscape rotation) silently misfire because the task they expected is not in the windowing mode they assumed.
- **`foreground` → `background`**: leftover fullscreen music-app tasks linger. The shell-script reuse path in `ShizukuLauncher.buildLaunchScript` only matches `mode=freeform AND visible=true`, so it falls through to the cold path (`am start --windowingMode 5`). HyperOS happily creates a *new* freeform task while the old fullscreen task remains in Recents — a ghost task per platform per mode-switch.

The most disruptive case is per-platform freeform handling. QQ Music creates a new task per launch (do-not-repeat 2026-05-09) and Bilibili's video-player chain resets bounds 2–5 s after launch (do-not-repeat 2026-05-08) — both leave residue that compounds across mode switches.

## What Changes

- Add a centralized **mode-switch handler** triggered by the `launch_mode` SharedPreferences change. Replaces the current bare `ShizukuLauncher.clearTargetState()` call in `PlaybackService.launchModePrefListener` with a full transition routine.
- On every transition, **purge stale music-app tasks** for all four supported platforms (NetEase, QQ Music, Bilibili, Kugou) via Shizuku `am stack remove-task <tid>` (or `am force-stop <pkg>` as fallback). Purge runs for whichever mode is being *left*, since that mode is the one whose task shapes are now wrong for the next launch.
- Surface mode-switch outcomes via a single Toast (Chinese): "已清理 N 个旧任务，切换到 <mode> 模式" — replaces silent state inconsistency with a visible confirmation.
- **Per-platform freeform task discovery**: extend `ShizukuLauncher` with a `listTasksForPackage(pkg)` helper that returns every task id for a package regardless of windowing mode and visibility — current dumpsys lookup is gated on `mode=freeform AND visible=true` and misses both fullscreen tasks (`fg → bg` case) and invisible/freeform ghosts (QQ Music accumulation, Bilibili IntentHandler chain).
- **Locked-screen path is left unchanged**: locked-screen launches share a single fallback regardless of mode, so they don't accumulate per-mode residue. No purge needed.
- **Gate the purge on Shizuku `READY` status**: without Shizuku the user can't toggle the system-level windowing mode anyway, so a Toast directs them to grant Shizuku before the new mode is fully effective.

### Non-goals

- Not changing the launch path inside either mode — `launchBackground` / `launchForeground` continue to behave exactly as today *after* the purge runs.
- Not preserving in-music-app state (current playback position, queue) across the purge. Tutti is the source of truth for the playback queue; the music app's internal state is disposable.
- Not surfacing a new permission tier — Shizuku permission is already documented as `可选` for background mode in `fragment_setup.xml`. The purge gracefully degrades when Shizuku is absent.
- Not redesigning the `launch_mode` ListPreference UI itself — the existing two-option ListPreference stays.

## Capabilities

### New Capabilities

- `launch-mode-switching`: How Tutti transitions between `background` and `foreground` launch modes — pref-change listener wiring, per-platform stale-task purge (NetEase / QQ Music / Bilibili / Kugou), Shizuku availability gating, user-facing confirmation Toast.

### Modified Capabilities

_(none — `launch_mode` is not currently the subject of any existing spec in `openspec/specs/`. The existing `freeform-multi-task-hide` spec belongs to a different change family — `fix-floating-ball-and-freeform-bugs` — and is concerned with managing the windowing of a SINGLE active mode, not the transition between modes.)_

## Impact

- **Code**:
  - `service/PlaybackService.kt` — `launchModePrefListener` becomes a thin dispatcher to a new `LaunchModeSwitcher` helper.
  - `service/ShizukuLauncher.kt` — add `listTasksForPackage(pkg)` (or extend the existing dumpsys script) and `removeTask(tid)`. Reuse `pkgManaged` set as the primary source for "tasks Tutti owns" but supplement with a dumpsys sweep for full coverage (covers Bilibili child tasks the manager set may not contain).
  - `service/DeepLinkLauncher.kt` — no behavioral change inside `launchForeground` / `launchBackground`; only delete the now-redundant `ShizukuLauncher.clearTargetState()` call at `launchForeground` entry (the new switcher handles it).
  - `service/LaunchModeSwitcher.kt` — new file. Single `onModeChanged(context, from, to)` entry point.
- **Affected platform apps**: NetEase (`com.netease.cloudmusic`), QQ Music (`com.tencent.qqmusic`), Bilibili (`tv.danmaku.bili`), Kugou (`com.kugou.android`). Each has different task-creation patterns (see `.wolf/cerebrum.md` Key Learnings) — purge handles all four through one Shizuku script.
- **Permissions**: none added. Relies on existing Shizuku grant; gracefully degrades without it.
- **User-facing**: one new Toast string (Chinese) on every mode toggle. No new Settings UI.
- **Tests**: no automated tests — this is system-level Shizuku behavior verified manually on HyperOS as per existing do-not-repeat entries.

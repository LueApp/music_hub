## Context

### How we got here

`6c66a9d` ("Auto-restore accessibility permission via Shizuku after force-stop") added a single chokepoint, `MusicHubApplication.restoreAccessibilityIfRevoked()`, called from `Application.onCreate()`. It maintains a `musichub_a11y/accessibility_granted` `SharedPreferences` boolean that is set to `true` whenever the call observes our `PlayerAccessibilityService` listed in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. On subsequent process starts, if the pref is `true` but the service is missing from the setting, `ShizukuLauncher.restoreAccessibilityServices(...)` is invoked to re-write the setting via `settings put secure enabled_accessibility_services '<merged>'` (requires `WRITE_SECURE_SETTINGS`, hence Shizuku).

### Observed symptom

User reports that on a device with Shizuku running, the accessibility service silently drops out of the granted list after a force-stop and is not restored on the next launch. The logging is `Log.d`-only on the failure path, so there is no smoking gun in `logcat`.

### Three gaps in the existing implementation

1. **Grant-observation gap.** The pref is only written from `Application.onCreate`. The realistic user flow is:
   - Launch → `onCreate` runs → pref is `false` because the service is not yet enabled → method returns.
   - User opens Accessibility settings, enables `PlayerAccessibilityService`, returns.
   - `MainActivity.onResume` fires; `Application.onCreate` does **not** fire because the process is still alive.
   - Pref stays `false`.
   - Later: force-stop → process dies → relaunch → `onCreate` runs → service is missing AND pref is `false` → we don't try to restore.

2. **Shizuku timing race.** `Application.onCreate` runs synchronously. Shizuku's binder connects asynchronously through `ShizukuProvider`. On a cold start, `Shizuku.pingBinder()` may return `false` at `onCreate` time. `ShizukuLauncher.status` then returns `SERVICE_NOT_RUNNING`, and `restoreAccessibilityServices` exits early with `false`. There is no retry once the binder is actually available.

3. **Process-alive revocation gap.** `Application.onCreate` runs at most once per process. The system's `AccessibilityManagerService` sweep can fire while our process is alive (e.g., when `am force-stop` is run on a sibling component, or during a background-task killer sweep that drops other services without killing our process). Our `restoreAccessibilityIfRevoked` never re-fires.

### Constraints

- `WRITE_SECURE_SETTINGS` is signature-only — direct writes from a third-party app fail. Shizuku is the only realistic recovery path. Users without Shizuku see manual re-enable; that is a fixed user-facing limitation.
- `Shizuku.pingBinder` / `Shizuku.checkSelfPermission` may throw if called too early. The existing `ShizukuLauncher.status` already catches `Throwable` and downgrades to `SERVICE_NOT_RUNNING`; we keep that.
- `ContentObserver` callbacks fire on a `Handler`; the `Shizuku.newProcess` reflective call blocks on a binder round-trip. Restoration work must run off the main thread.
- The fix is on `Application.onCreate` and on the main `MainActivity` — both run very early in the app's lifecycle. Anything we add must not regress startup time.

## Goals / Non-Goals

**Goals:**
- A Shizuku-equipped user who previously granted `PlayerAccessibilityService` gets the service silently restored within seconds of any force-stop-induced revocation, both at process start *and* live during a session.
- Failures are visible: when restoration is attempted but cannot succeed (e.g., Shizuku not `READY`), the log explicitly identifies the blocker; the user has a Settings action they can use to trigger and observe a restore attempt.
- The grant-observation pref is recorded the moment the user actually grants the service, not delayed until the next process start.

**Non-Goals:**
- Restoring accessibility for users without Shizuku. They keep the existing fallback (manual re-enable).
- Reworking the single- vs. split-service decision — staying single per CLAUDE.md "Accessibility services revoked on force-stop".
- Caching `WRITE_SECURE_SETTINGS` via any non-Shizuku mechanism — none exists for third-party apps on stock Android or HyperOS.

## Decisions

### D1: Record the grant in `onServiceConnected`, not only in `Application.onCreate`

`PlayerAccessibilityService.onServiceConnected()` fires when the system binds to the service after the user enables it in Accessibility settings — exactly the moment we want to record the grant. Adding a SharedPreferences write there closes Gap 1.

```kotlin
override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    // Mark grant so MusicHubApplication.restoreAccessibilityIfRevoked
    // can recover this service across future force-stop cycles. This
    // also covers the case where the user grants accessibility while
    // the process is already alive: Application.onCreate won't re-run,
    // but onServiceConnected does.
    applicationContext
        .getSharedPreferences(A11Y_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(PREF_GRANTED, true).apply()
    // ...existing setup...
}
```

Pref keys (`A11Y_PREFS`, `PREF_GRANTED`) are moved into a shared `AccessibilityGrantStore` object so `MusicHubApplication` and `PlayerAccessibilityService` reference the same constants.

**Alternative considered:** Listen for `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` changes everywhere and record on any change. Rejected — `onServiceConnected` is the cleanest signal that our specific service is bound (not just listed) and is on the standard accessibility-service lifecycle.

### D2: Retry restore on Shizuku binder-received

Register `Shizuku.addBinderReceivedListenerSticky { ... }` in `MusicHubApplication.onCreate`. The sticky variant fires immediately if the binder is already received, otherwise fires on the next connect. Inside the listener, re-call `restoreAccessibilityIfRevoked()` on a background coroutine. This closes Gap 2 (timing race).

```kotlin
Shizuku.addBinderReceivedListenerSticky {
    CoroutineScope(Dispatchers.IO).launch {
        restoreAccessibilityIfRevoked()
    }
}
```

**Alternative considered:** Spin a Handler-based delay loop ("if Shizuku not ready, retry in 500 ms up to N times"). Rejected — the Shizuku API already exposes the event we need; polling would waste cycles and still race.

### D3: Re-check on `MainActivity.onResume`

Add a public `MusicHubApplication.restoreAccessibilityIfNeeded()` entry point and call it from `MainActivity.onResume` alongside the existing `rebindMediaMonitor` call. Cheap and idempotent: one SharedPreferences read, one `Settings.Secure.getString`, at most one Shizuku call. Closes Gap 3 for the common scenario (user returns to the app after a system event).

**Alternative considered:** Call from `MainActivity.onCreate` instead. Rejected — `onCreate` runs only once per Activity instance and `onResume` covers more lifecycle states (e.g., resume after Recents close).

### D4: Live revocation monitoring via `ContentObserver`

Register a `ContentObserver` on `Settings.Secure.getUriFor(ENABLED_ACCESSIBILITY_SERVICES)` from `PlaybackService.onCreate` (the long-lived foreground service) and unregister in `onDestroy`. On each callback, if the pref says granted but the setting no longer contains our component, re-fire the restore on a background dispatcher.

```kotlin
private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (!PlayerAccessibilityService.isEnabled(this@PlaybackService) &&
            wasPreviouslyGranted(this@PlaybackService)) {
            (application as? MusicHubApplication)?.restoreAccessibilityIfNeeded()
        }
    }
}
```

**Why `PlaybackService`, not `MediaMonitorService`?** `PlaybackService` is the foreground service that backs the floating window; it has the longest guaranteed lifetime in our process. `MediaMonitorService` is a `NotificationListenerService` whose binding state is itself fragile (we already work around this with `requestRebind`); piggy-backing on it would couple two unrelated reliability concerns.

**Alternative considered:** Listen for `PACKAGE_RESTARTED` broadcasts. Rejected — the broadcast is not delivered to the package being restarted (by design), and the `ContentObserver` covers the actual signal (setting changed) more directly.

### D5: Surface failures with `Log.w` + a one-shot Toast on the diagnostic action

Today's `Log.d` ("Accessibility service revoked but couldn't restore...") is too quiet. Promote it to `Log.w` so default logcat filtering shows it, and include the explicit `ShizukuLauncher.Status` value so the next person reading logs can tell whether the blocker was install / running / permission.

Add a diagnostic preference in `SettingsFragment` ("立即恢复无障碍权限") that:
- Triggers `restoreAccessibilityIfNeeded()` immediately.
- Toasts the outcome: "已恢复" / "Shizuku 未授权" / "Shizuku 未运行" / "未曾授予过" / "未发现缺失服务".

This is the user-visible safety net for when the silent path fails for any reason.

### D6: Verify the write by reading back

`ShizukuLauncher.restoreAccessibilityServices` currently logs `exit=$exit` only. Extend the log to:
1. Read current `enabled_accessibility_services` (the `current` variable already exists).
2. Run the put.
3. Read it back immediately via `Settings.Secure.getString` and log all three values.

If the readback shows our component absent again, log a `Log.w` line. This is a diagnostic affordance for the next regression — it does not change behavior.

```kotlin
val before = current
val proc = newShizukuProcess(arrayOf("sh", "-c", script)) ?: return false
val exit = proc.waitFor()
val after = Settings.Secure.getString(
    context.contentResolver,
    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
).orEmpty()
val landed = desired.all { after.contains(it) }
Log.i(TAG, "restoreA11y exit=$exit landed=$landed before=$before merged=$merged after=$after")
```

### D7: Component name normalization

`Settings.Secure.enabled_accessibility_services` can store either `pkg/.RelativeClass` or `pkg/fully.qualified.Class`. We compare with `String.contains` on the full canonical name, so a system rewrite to relative form would make us think the service is missing. Document the assumption in code (a `Comment` is fine — the system uses the canonical form on every Android version we target) and add a normalization step in `restoreAccessibilityServices`:

```kotlin
val canonical = "$pkg/$canonicalClassName"
val relative = "$pkg/.${shortClassName}"
val alreadyPresent = existing.any { it == canonical || it == relative }
```

This is defensive only — empirically the system uses the canonical form on API 26+ HyperOS — but the cost is negligible and it prevents a future Android rewrite from masking the restore.

## Risks / Trade-offs

- **[Risk]** `Shizuku.addBinderReceivedListenerSticky` fires on the main thread. Heavy work in the listener could ANR. → Wrap the body in `CoroutineScope(Dispatchers.IO).launch { ... }` and keep the listener body to just dispatch.

- **[Risk]** `ContentObserver` callbacks fire on every change to the setting, including the writes we ourselves trigger via Shizuku. Without a guard we could feed-back-loop. → The observer's gate is `isEnabled(...) == false`. Our restore writes the service *into* the setting, so the observer's gate is `false` after the write and no further restore fires. As a belt-and-suspenders, debounce the observer to one fire per 1 s.

- **[Risk]** Calling `restoreAccessibilityIfNeeded` from `MainActivity.onResume` could attempt restore while the user is actively in Accessibility settings deliberately disabling our service. → Add a "user explicitly disabled via our UI" preference and short-circuit when it's set. The proposed diagnostic action does NOT set this; only an explicit toggle in our Settings would. (Out of immediate scope, but worth a Comment for the next iteration.)

- **[Risk]** The diagnostic Settings action visibly re-enables a service the user just disabled, surprising them. → The action message reads "立即恢复无障碍权限"; it is invoked only on tap, and the action surfaces explicit outcome text. No silent behavior change.

- **[Trade-off]** Adding a `ContentObserver` in `PlaybackService` ties accessibility recovery to playback-service lifetime. If `PlaybackService` is destroyed (rare — it's a foreground service), live monitoring stops until the next process restart. Acceptable: revocations that happen *and* persist past the next `MainActivity.onResume` are the realistic case, and that path is also covered.

- **[Trade-off]** We are layering five trigger points (existing `Application.onCreate`, new Shizuku-binder-received, new `MainActivity.onResume`, new `PlaybackService` `ContentObserver`, new Settings diagnostic). The function must be idempotent and cheap. The decision is to centralize the entire check inside one function and have all five sites call it; duplicate work between them is by design and is cheap (SharedPreferences + Settings read).

## Migration Plan

- No data migration: SharedPreferences key (`musichub_a11y/accessibility_granted`) and the underlying Shizuku call are unchanged. New writes from `onServiceConnected` write to the same pref the existing code reads.
- No version-tag implications beyond the standard patch bump on next `dev`→`master` merge.
- Rollback: revert the change. The `6c66a9d` baseline is restored verbatim.

## Open Questions

- Does HyperOS 3.x sometimes rewrite `enabled_accessibility_services` after our write to remove our component (e.g., via its own background sweep)? D6's before/after logging is in part designed to confirm or refute this. If it turns out true, the follow-up is a re-write loop with backoff — but we should not preemptively add it. (Tracked in tasks as a deferred diagnostic check.)
- Should the `ContentObserver` also live in `FloatingWindowService` (which is always foreground when the user is interacting)? Probably not — `PlaybackService` is the canonical long-lived service. If empirically the observer misses events, expand later.

## Why

Commit `6c66a9d` added a Shizuku-mediated auto-restore for the accessibility permission that AOSP silently revokes whenever the package is force-stopped. The user reports that, several commits later, the permission is now being lost again — even though Shizuku is running on the device. The fix exists in code but is not firing (or is firing and silently failing). The current `restoreAccessibilityIfRevoked()` has three latent gaps that together explain the regression: (1) the grant-observation pref is only written from `Application.onCreate`, missing the common flow where the user grants accessibility *after* the first launch while the process is still alive; (2) the restore runs once per process at `Application.onCreate`, so if `Shizuku.pingBinder()` returns `false` because Shizuku's binder hasn't connected yet, we silently skip and never retry; (3) failures are logged at `Log.d` only — there is no user-visible signal when the restore is attempted-and-failed vs. never-attempted. We need to close these gaps so Shizuku users get the silent recovery the original commit promised.

A natural extension: once we have a robust Shizuku-mediated write path for one permission, the same daemon can grant *every* permission Tutti needs. Today the user grants accessibility, notification access, overlay, USAGE_STATS, WRITE_SETTINGS, and POST_NOTIFICATIONS one-by-one through six separate system Settings pages — a meaningful friction point especially after force-stop + Shizuku restart. With Shizuku running, the app can detect missing permissions and grant them in a single batch via shell, then surface the result as a one-shot dismissable notification so the user knows what happened.

## What Changes

**Accessibility restoration (Gap fixes for `6c66a9d`):**

- Record the "previously granted" pref in **two** places, not just one:
  - `PlayerAccessibilityService.onServiceConnected()` — fires the moment the user grants the service, regardless of whether `Application.onCreate` will ever run again before the next force-stop.
  - `MusicHubApplication.restoreAccessibilityIfRevoked()` — preserved as the existing observation point.
- Retry the restore when Shizuku's binder *eventually* connects, not just at app start. Register a `Shizuku.addBinderReceivedListenerSticky` callback in `MusicHubApplication.onCreate` that re-runs `restoreAccessibilityIfRevoked` after the binder is available. This closes the race where `onCreate` ran before Shizuku was bound.
- Re-check accessibility in `MainActivity.onResume` (the user is back in front of our UI — perfect moment to verify and recover). Cheap: a SharedPreferences read + a `Settings.Secure` read + at most one Shizuku call.
- Add a `ContentObserver` on `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for the lifetime of the `PlaybackService` foreground service. When the setting changes and our service drops out of the list while the pref still says granted, re-trigger the restore. This catches the case where the process is alive (so `Application.onCreate` won't re-fire) but the system has just revoked our service.
- Surface restore failures to the user when they should be actionable: if the restore attempt fails because Shizuku status is not `READY`, emit a `Toast` (or log at `Log.w`) explaining which sub-state is the blocker (`NOT_INSTALLED` / `SERVICE_NOT_RUNNING` / `PERMISSION_DENIED`). The current silent `Log.d` makes it impossible for the user to know whether the restore was attempted.
- Add an explicit "Restore accessibility now" diagnostic action in `SettingsFragment` so the user can force a restore attempt on demand and see the result, both for normal recovery and for filing future bug reports.
- Tighten the existing `ShizukuLauncher.restoreAccessibilityServices` log to always print: (a) the value of the setting before the write, (b) the merged value being written, (c) the value read back from the setting after the write. This is needed to diagnose any future regression where the write exits 0 but the system rejects or rewrites the value.

**Bulk permission auto-grant (extension):**

- Add `ShizukuLauncher.autoGrantAllPermissions(context)` that, when Shizuku status is `READY`, attempts to grant every permission Tutti needs that is not currently held:
  - **Accessibility** (`BIND_ACCESSIBILITY_SERVICE`) — via the existing `restoreAccessibilityServices` path.
  - **Notification listener** (`BIND_NOTIFICATION_LISTENER_SERVICE`) — via `cmd notification allow_listener <component>`.
  - **Overlay** (`SYSTEM_ALERT_WINDOW`) — via `appops set <pkg> SYSTEM_ALERT_WINDOW allow`.
  - **Usage stats** (`PACKAGE_USAGE_STATS`) — via `appops set <pkg> GET_USAGE_STATS allow`.
  - **Write settings** (`WRITE_SETTINGS`) — via `appops set <pkg> WRITE_SETTINGS allow`.
  - **Post notifications** (`POST_NOTIFICATIONS`) — via `pm grant <pkg> android.permission.POST_NOTIFICATIONS`.
- Default-ON opt-out toggle (`auto_grant_via_shizuku` SharedPreference, defaults to `true`). User-controllable in Settings: "通过 Shizuku 自动授予所需权限".
- Invoke the bulk grant from `MusicHubApplication`'s Shizuku-binder-received listener — same trigger that already drives the accessibility restore. Single coherent entry point.
- On any net-new grant, post a one-shot dismissable notification on a new "permissions" channel summarizing what was just granted ("已通过 Shizuku 自动授予 N 项权限：..."). Tapping the notification opens the Settings page so the user can verify or revoke.
- Document the auto-grant feature on the landing site (`site/index.html` + `site/src/main.js` `zh`/`en` translations) so users can discover it without launching the app.

## Capabilities

### New Capabilities
- `accessibility-permission-recovery`: Lifecycle and restoration of the user's grant for `PlayerAccessibilityService` across `am force-stop` cycles. Covers when to record the grant, when to attempt restore, how to use Shizuku to re-write `Settings.Secure.enabled_accessibility_services`, and how to surface failures.
- `shizuku-permission-auto-grant`: Bulk batch grant of every special permission Tutti needs via Shizuku-mediated shell. Defaults to ON; user-controllable via Settings toggle. Reports newly-granted permissions through a dismissable notification.

### Modified Capabilities
<!-- None. The existing `qqmusic-accessibility-resilience` spec is scoped to mini-player click strategies, not permission lifecycle, so it is left unchanged. -->

## Impact

- **Code**:
  - `android-app/app/src/main/java/com/musichub/MusicHubApplication.kt` — extract `restoreAccessibilityIfRevoked` so it can be called from `onCreate`, from `MainActivity.onResume`, and from a Shizuku binder-received listener. Add a public entry point. Wire the new bulk auto-grant into the same binder-received listener. Build and post the auto-grant notification.
  - `android-app/app/src/main/java/com/musichub/service/PlayerAccessibilityService.kt` — write the granted-pref from `onServiceConnected`. Expose a helper to record/read the pref so the writes stay consistent.
  - `android-app/app/src/main/java/com/musichub/service/ShizukuLauncher.kt` — log the before / merged / after values of the setting around the restore write. Add `autoGrantAllPermissions` with per-permission probe-grant-verify logic and a structured `AutoGrantResult` return type.
  - `android-app/app/src/main/java/com/musichub/service/PlaybackService.kt` — register and unregister a `ContentObserver` on the accessibility-services setting alongside the existing service lifecycle.
  - `android-app/app/src/main/java/com/musichub/ui/MainActivity.kt` — call the public `restoreAccessibilityIfNeeded` entry point from `onResume` in addition to the existing `rebindMediaMonitor` call.
  - `android-app/app/src/main/java/com/musichub/ui/fragment/SettingsFragment.kt` — add a diagnostic "Restore accessibility now" preference that calls the public entry point and Toasts the result. Add the new "通过 Shizuku 自动授予所需权限" switch preference.
  - `android-app/app/src/main/res/xml/preferences.xml` — add the two new preferences.
  - `android-app/app/src/main/res/values/strings.xml` — add strings for the diagnostic action, the auto-grant toggle, and the notification body.
- **Landing site**:
  - `site/index.html` — add a section under permissions describing the Shizuku auto-grant capability.
  - `site/src/main.js` — add the matching `zh`/`en` translations for the new strings.
- **Platforms affected**: All four music platforms (NetEase, QQ Music, Bilibili, Kugou) — accessibility is shared infrastructure for QQ Music mini-player tap and for the freeform-resize trigger in background launch mode. None of the platform handlers change.
- **Permissions**: No new manifest permissions. Continues to depend on Shizuku for `WRITE_SECURE_SETTINGS` (used to write `Settings.Secure`) and for shell-UID delivery of `pm grant` / `appops set` / `cmd notification` commands.
- **User-facing**: Settings page gains the diagnostic action, the auto-grant toggle. One new notification channel ("permissions") with one notification posted at most once per launch when net-new grants occurred. The landing page gains a documentation section. No UI flow changes outside Settings.
- **Compatibility**: No SDK / API-level concerns. `ContentObserver`, `Shizuku.addBinderReceivedListenerSticky`, `appops`, `pm grant`, and `cmd notification` are all available on API 26+ via shell UID through Shizuku. `POST_NOTIFICATIONS` runtime grant is API 33+ but harmless to attempt on older versions (`pm grant` no-ops if the runtime permission isn't declared as such for the SDK target).

## Non-goals

- Restoring accessibility for users who never granted it. The proposal only addresses **revocations of a previously-granted service**.
- Restoring accessibility for users **without Shizuku**. They continue to need manual re-enable in HyperOS Accessibility settings. Documented limitation.
- Auto-revoking permissions. The bulk grant only adds; it never takes away. Users who want to revoke must do so manually through the system Settings UI.
- Auto-granting permissions when Shizuku is not `READY`. The feature exists only for Shizuku users; non-Shizuku users see no change.
- Reworking the split-service vs. single-service decision. The single `PlayerAccessibilityService` design is intentional (see CLAUDE.md "Accessibility services revoked on force-stop") and stays as-is.
- Replacing the `enabled_accessibility_services` setting write with any framework API that does not require Shizuku — no such API exists for third-party callers.
- Detecting *why* the system revoked the service (force-stop vs. category audit vs. user revoke). The behavior is identical from the restore's perspective: if the service was granted, and is no longer in the setting, and the user hasn't explicitly disabled it via our own UI, we attempt to restore.

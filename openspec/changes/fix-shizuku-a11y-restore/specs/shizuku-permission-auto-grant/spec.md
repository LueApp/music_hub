## ADDED Requirements

### Requirement: Bulk auto-grant scope and trigger
When the user has Shizuku running with permission granted to Tutti, the app SHALL attempt to grant every special permission Tutti needs in a single batch at the appropriate trigger points, without requiring the user to navigate through multiple system Settings pages.

#### Scenario: Auto-grant runs at application startup
- **WHEN** `MusicHubApplication.onCreate` completes AND the `auto_grant_via_shizuku` pref is `true` AND `ShizukuLauncher.status` returns `READY`
- **THEN** the application SHALL invoke `ShizukuLauncher.autoGrantAllPermissions(context)` on a background dispatcher

#### Scenario: Auto-grant runs on Shizuku binder-received
- **WHEN** the `Shizuku.addBinderReceivedListenerSticky` listener fires (covering the timing race where the binder was not yet bound at `onCreate`)
- **THEN** the application SHALL invoke `autoGrantAllPermissions` on a background dispatcher, gated on the `auto_grant_via_shizuku` pref

#### Scenario: Auto-grant covers all required permissions
- **WHEN** `autoGrantAllPermissions` runs with Shizuku `READY`
- **THEN** it SHALL probe and attempt to grant each of: `BIND_ACCESSIBILITY_SERVICE` (via `restoreAccessibilityServices`), `BIND_NOTIFICATION_LISTENER_SERVICE` (via `cmd notification allow_listener`), `SYSTEM_ALERT_WINDOW` (via `appops set ... SYSTEM_ALERT_WINDOW allow`), `PACKAGE_USAGE_STATS` (via `appops set ... GET_USAGE_STATS allow`), `WRITE_SETTINGS` (via `appops set ... WRITE_SETTINGS allow`), `POST_NOTIFICATIONS` (via `pm grant ... android.permission.POST_NOTIFICATIONS`)

#### Scenario: Auto-grant skipped when Shizuku is not READY
- **WHEN** `autoGrantAllPermissions` is called AND `ShizukuLauncher.status` returns `NOT_INSTALLED` / `SERVICE_NOT_RUNNING` / `PERMISSION_DENIED`
- **THEN** the function SHALL return an empty `AutoGrantResult` with the observed status AND log `autoGrant skipped: status=<status>` at `Log.d`

### Requirement: Per-permission probe-grant-verify cycle
For each permission in the bulk grant, the implementation SHALL: (1) probe the current grant state, (2) only attempt the grant shell command if the permission is currently missing, (3) re-verify the state after the grant and report success/failure.

#### Scenario: Permission already granted is skipped
- **WHEN** the initial probe for a given permission returns "already granted"
- **THEN** no shell command SHALL be issued for that permission AND the result entry SHALL be marked `alreadyGranted=true grantAttempted=false`

#### Scenario: Permission grant attempted and verified
- **WHEN** the initial probe returns "not granted" AND the shell command runs
- **THEN** the implementation SHALL re-probe after the command exits and record `grantSucceeded` based on whether the post-probe shows the permission as granted

#### Scenario: Grant succeeded for net-new permission
- **WHEN** a permission was probed as "not granted" AND the shell command exited 0 AND the post-probe shows it as granted
- **THEN** the result entry SHALL be marked `alreadyGranted=false grantAttempted=true grantSucceeded=true` AND be included in the `newlyGranted` aggregate

#### Scenario: Grant attempted but failed
- **WHEN** the shell command exited non-zero OR the post-probe still shows the permission as missing
- **THEN** the result entry SHALL be marked `grantSucceeded=false` AND the implementation SHALL log a `Log.w` line including the permission key, exit code, and pre/post probe values

### Requirement: Opt-out preference
The bulk auto-grant SHALL be controllable by a user-facing SharedPreference defaulting to `true`. When disabled, no auto-grant attempts run regardless of Shizuku state.

#### Scenario: Default behavior when pref is unset
- **WHEN** the app reads `auto_grant_via_shizuku` AND it has never been written
- **THEN** the default value SHALL be `true` (opt-out semantics)

#### Scenario: User disables auto-grant
- **WHEN** the user toggles the "通过 Shizuku 自动授予所需权限" preference OFF in Settings
- **THEN** subsequent calls to the auto-grant entry point SHALL no-op without contacting Shizuku, log at `Log.d` "autoGrant skipped: pref disabled"

#### Scenario: User re-enables auto-grant
- **WHEN** the user toggles the preference back ON
- **THEN** the next trigger (`onCreate`, `MainActivity.onResume`, or `Shizuku.addBinderReceivedListenerSticky`) SHALL run the auto-grant

### Requirement: Notification surface for newly-granted permissions
After every auto-grant invocation that results in at least one net-new grant, the app SHALL post a one-shot, auto-dismissable notification listing what was just granted, so the user is aware of side effects.

#### Scenario: At least one new permission granted
- **WHEN** `autoGrantAllPermissions` returns with `newlyGranted.isNotEmpty()`
- **THEN** the app SHALL create a notification on a dedicated "permissions" channel with title "已通过 Shizuku 自动授予权限" and a body listing the user-facing display name of each newly-granted permission

#### Scenario: No new permissions granted
- **WHEN** `autoGrantAllPermissions` returns with `newlyGranted.isEmpty()` (either all were already granted, or none could be granted)
- **THEN** the app SHALL NOT post a notification

#### Scenario: Notification is dismissable
- **WHEN** the notification is posted
- **THEN** it SHALL be configured with `setAutoCancel(true)` (auto-dismiss on tap) AND NOT `setOngoing(true)` (user can swipe-dismiss)

#### Scenario: Notification tap opens app Settings
- **WHEN** the user taps the auto-grant notification
- **THEN** the app SHALL open `MainActivity` so the user can navigate to Settings and verify or adjust the new grants

#### Scenario: Notification channel created lazily
- **WHEN** the auto-grant notification is about to be posted AND the "permissions" notification channel does not yet exist
- **THEN** the channel SHALL be created with `IMPORTANCE_LOW` (no sound, no peek), `setShowBadge(false)`, and a Chinese description identifying its purpose

### Requirement: Auto-grant must precede the POST_NOTIFICATIONS-dependent notify call
Because the auto-grant flow itself grants `POST_NOTIFICATIONS`, the implementation SHALL perform the grants first and only afterwards build and post the auto-grant notification, so the notify call is guaranteed to fire even on a fresh-install device where `POST_NOTIFICATIONS` was not granted previously.

#### Scenario: First-run device with POST_NOTIFICATIONS missing
- **WHEN** the user has Shizuku ready AND POST_NOTIFICATIONS is not yet granted AND auto-grant runs for the first time
- **THEN** `pm grant android.permission.POST_NOTIFICATIONS` SHALL be issued AND verified BEFORE the notify call is attempted

#### Scenario: POST_NOTIFICATIONS grant failed
- **WHEN** the POST_NOTIFICATIONS grant attempt failed (e.g., not declared in manifest for SDK target, or pm grant exited non-zero)
- **THEN** the notify call SHALL still be attempted (best-effort), and any system-level suppression SHALL be silently absorbed

### Requirement: Public diagnostic entry point
The bulk auto-grant SHALL be invokable on demand by user action, not only by the implicit triggers, so the user can re-run the grant after re-starting Shizuku without restarting the app.

#### Scenario: Diagnostic action from Settings
- **WHEN** the user taps a "立即重新授予所需权限" action in Settings
- **THEN** the app SHALL invoke the same auto-grant entry point and surface the outcome (number of newly-granted, blocking-status if Shizuku not READY) via Toast

### Requirement: Landing site documentation
The Tutti landing site SHALL document the Shizuku auto-grant capability in Chinese and English, so users can discover the feature before installing or before launching the app.

#### Scenario: Site mentions Shizuku auto-grant
- **WHEN** the user visits `site/index.html`
- **THEN** the page SHALL include a section describing the auto-grant capability AND linking it to the permissions tier breakdown

#### Scenario: i18n parity with existing content
- **WHEN** the documentation section is added
- **THEN** both `translations.zh` and `translations.en` in `site/src/main.js` SHALL include the new i18n keys

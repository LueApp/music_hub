## ADDED Requirements

### Requirement: Grant recording at the moment of grant
The app SHALL record an "accessibility previously granted" flag in `SharedPreferences` whenever `PlayerAccessibilityService` is actively bound by the system, regardless of which lifecycle event observes the grant.

#### Scenario: User grants accessibility while app process is alive
- **WHEN** the user enables `PlayerAccessibilityService` in the system Accessibility settings AND the Music Hub process is already running
- **THEN** `PlayerAccessibilityService.onServiceConnected` SHALL write `accessibility_granted=true` to the `musichub_a11y` SharedPreferences before performing any other setup

#### Scenario: Application observes the service enabled at process start
- **WHEN** `MusicHubApplication.onCreate` runs AND `PlayerAccessibilityService.isEnabled` returns true
- **THEN** the application SHALL write `accessibility_granted=true` to the same SharedPreferences and return without invoking the restore path

#### Scenario: Pref is durable across force-stop
- **WHEN** the pref `accessibility_granted=true` has been written AND the package is subsequently force-stopped
- **THEN** the pref SHALL persist (SharedPreferences storage is not wiped by force-stop) so the next process start can read it

### Requirement: Restore attempt at every reasonable trigger point
The app SHALL attempt to restore the accessibility service via Shizuku at multiple lifecycle trigger points, not solely at `Application.onCreate`, so that timing races and live revocations are both covered.

#### Scenario: Application start with Shizuku already connected
- **WHEN** `MusicHubApplication.onCreate` runs AND `accessibility_granted=true` AND the service is missing from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` AND `ShizukuLauncher.status` returns `READY`
- **THEN** the application SHALL call `ShizukuLauncher.restoreAccessibilityServices` with our service component

#### Scenario: Application start before Shizuku binder connects
- **WHEN** `MusicHubApplication.onCreate` runs AND `ShizukuLauncher.status` is not `READY` because the Shizuku binder has not yet connected
- **THEN** the application SHALL register a `Shizuku.addBinderReceivedListenerSticky` listener that re-fires the restore attempt on a background dispatcher when the binder eventually connects

#### Scenario: User returns to the foreground
- **WHEN** `MainActivity.onResume` runs
- **THEN** the activity SHALL call a public `restoreAccessibilityIfNeeded` entry point on the application object that performs the same check-and-restore sequence

#### Scenario: Live revocation while process is alive
- **WHEN** `PlaybackService` is alive AND `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` changes AND after the change our service is missing from the setting AND the pref says granted
- **THEN** a `ContentObserver` registered by `PlaybackService` SHALL trigger the same restore path on a background dispatcher

#### Scenario: User invokes diagnostic action
- **WHEN** the user taps the "立即恢复无障碍权限" preference in `SettingsFragment`
- **THEN** the app SHALL run the same restore path immediately and show a Toast with the outcome

### Requirement: Restore writes a merged service list and verifies the result
`ShizukuLauncher.restoreAccessibilityServices` SHALL preserve any third-party accessibility services already present in the system setting, write the merged value via `settings put secure`, and log enough state to diagnose post-write rejections.

#### Scenario: Merge preserves third-party services
- **WHEN** the system setting already lists third-party services such as `com.mutangtech.qianji/...` AND our component is missing
- **THEN** the restore SHALL build the new value as the union of `(existing ∪ desired)` joined by colons, never replacing the existing list

#### Scenario: Restore writes both keys atomically
- **WHEN** the restore is invoked
- **THEN** it SHALL execute `settings put secure enabled_accessibility_services '<merged>' && settings put secure accessibility_enabled 1` in a single Shizuku-mediated shell call so the master accessibility flag is also set

#### Scenario: Restore logs before/merged/after values
- **WHEN** the restore runs
- **THEN** the implementation SHALL log at `Log.i` the prior setting value, the merged value being written, the exit code of the shell call, the value of the setting read back after the write, and a boolean indicating whether all desired components are present in the read-back value

#### Scenario: Restore detects component-name format mismatch
- **WHEN** the system setting stores our component in relative form (`pkg/.RelativeClass`) AND we are checking for the canonical form
- **THEN** the membership check SHALL treat the relative and canonical forms as equivalent so we do not append a duplicate

### Requirement: Restore failure surfaces actionable information
The app SHALL not fail silently when a restore is attempted but cannot complete; it SHALL identify the blocking sub-state in logs and in the user-visible diagnostic action.

#### Scenario: Shizuku not installed
- **WHEN** the restore is attempted AND `ShizukuLauncher.status` returns `NOT_INSTALLED`
- **THEN** the log SHALL include the explicit status name AND the diagnostic action SHALL Toast "Shizuku 未安装"

#### Scenario: Shizuku not running
- **WHEN** the restore is attempted AND status returns `SERVICE_NOT_RUNNING`
- **THEN** the log SHALL include the explicit status name AND the diagnostic action SHALL Toast "Shizuku 未运行"

#### Scenario: Shizuku permission denied
- **WHEN** the restore is attempted AND status returns `PERMISSION_DENIED`
- **THEN** the log SHALL include the explicit status name AND the diagnostic action SHALL Toast "Shizuku 未授权"

#### Scenario: No prior grant recorded
- **WHEN** the diagnostic action is invoked AND `accessibility_granted=false`
- **THEN** the action SHALL Toast "未曾授予过" and not attempt the Shizuku write

#### Scenario: Service is already present
- **WHEN** the diagnostic action is invoked AND `PlayerAccessibilityService.isEnabled` returns true
- **THEN** the action SHALL Toast "无障碍权限已正常" and not attempt the Shizuku write

#### Scenario: Successful restore on diagnostic action
- **WHEN** the diagnostic action is invoked AND the Shizuku write succeeds AND the read-back confirms our component is present
- **THEN** the action SHALL Toast "已恢复"

### Requirement: Restore is idempotent and cheap
The restore path SHALL be safe to invoke from multiple trigger points without performing redundant work or feeding back into itself.

#### Scenario: No work when service is already enabled
- **WHEN** the restore is invoked AND `PlayerAccessibilityService.isEnabled` returns true
- **THEN** the path SHALL return immediately without contacting Shizuku

#### Scenario: ContentObserver does not loop on our own write
- **WHEN** the `ContentObserver` fires because the restore just wrote the setting AND `PlayerAccessibilityService.isEnabled` now returns true
- **THEN** the observer's `onChange` SHALL not re-trigger the restore

#### Scenario: ContentObserver debounces bursts
- **WHEN** the `ContentObserver` receives multiple `onChange` callbacks within 1 second
- **THEN** at most one restore attempt SHALL be initiated per second

### Requirement: Live monitoring is tied to PlaybackService lifecycle
The `ContentObserver` for `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` SHALL be registered in `PlaybackService.onCreate` and unregistered in `PlaybackService.onDestroy`, leveraging the foreground-service guarantee for stable long-running observation.

#### Scenario: Observer registered when playback service starts
- **WHEN** `PlaybackService.onCreate` runs
- **THEN** the service SHALL register a `ContentObserver` on the URI `Settings.Secure.getUriFor(ENABLED_ACCESSIBILITY_SERVICES)` with `notifyForDescendants=false`

#### Scenario: Observer unregistered cleanly
- **WHEN** `PlaybackService.onDestroy` runs
- **THEN** the service SHALL unregister the observer via `contentResolver.unregisterContentObserver`

#### Scenario: Observer callback runs off the main thread
- **WHEN** the observer's `onChange` fires AND a restore is needed
- **THEN** the actual Shizuku call SHALL execute on a background dispatcher (e.g., `Dispatchers.IO`) to avoid blocking the main thread on a binder round-trip

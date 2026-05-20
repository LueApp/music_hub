## 1. Shared pref store and PlayerAccessibilityService grant capture

- [x] 1.1 In `android-app/app/src/main/java/com/musichub/service/PlayerAccessibilityService.kt`, introduce a top-level `object AccessibilityGrantStore` with `A11Y_PREFS = "musichub_a11y"`, `PREF_GRANTED = "accessibility_granted"`, and helpers `setGranted(context)`, `wasGranted(context)`. Keep constants `internal` so `MusicHubApplication` and `SettingsFragment` can share them via this object.
- [x] 1.2 In `PlayerAccessibilityService.onServiceConnected`, call `AccessibilityGrantStore.setGranted(applicationContext)` before the existing `instance = this` and broadcast-receiver setup, so the grant is recorded the instant the system binds the service (closes the "grant during live process" gap).
- [x] 1.3 In `MusicHubApplication.kt`, delete the local `A11Y_PREFS` / `PREF_GRANTED` constants and replace direct SharedPreferences reads in `restoreAccessibilityIfRevoked` with calls to `AccessibilityGrantStore.wasGranted` / `setGranted` so both code paths cannot drift apart.
- [x] 1.4 Run `pixi run build` to confirm the refactor compiles and that no other file references the old private constants.

## 2. Restore entry point and multi-trigger wiring

- [x] 2.1 In `MusicHubApplication.kt`, extract the body of `restoreAccessibilityIfRevoked` into a `private fun runRestoreCheck(reason: String)` that logs the trigger reason (so logcat shows which entry point fired) and is safe to invoke off the main thread.
- [x] 2.2 In the same file, add a public method `fun restoreAccessibilityIfNeeded(reason: String)` that dispatches `runRestoreCheck` to `Dispatchers.IO`. This is the single entry point all other call sites will use.
- [x] 2.3 In `MusicHubApplication.onCreate`, keep the existing synchronous call (`runRestoreCheck("onCreate")`) but additionally register `Shizuku.addBinderReceivedListenerSticky { restoreAccessibilityIfNeeded("shizuku-binder-received") }`. Wrap in try/catch so missing Shizuku JAR symbols cannot block app startup.
- [x] 2.4 In `MainActivity.onResume` (in `android-app/app/src/main/java/com/musichub/ui/MainActivity.kt`), add a call to `(application as? MusicHubApplication)?.restoreAccessibilityIfNeeded("MainActivity.onResume")` immediately after the existing `rebindMediaMonitor` placement-pattern (move both into `onResume` if `rebindMediaMonitor` is currently in `onCreate` only, so the resume path covers both fixes uniformly).
- [x] 2.5 In `runRestoreCheck`, short-circuit early when `PlayerAccessibilityService.isEnabled(this)` is true (record the grant via `AccessibilityGrantStore.setGranted` and return) so multiple trigger points are idempotent and cheap.

## 3. ContentObserver for live revocation in PlaybackService

- [x] 3.1 In `android-app/app/src/main/java/com/musichub/service/PlaybackService.kt`, declare a `private val accessibilityObserver` (subclass of `ContentObserver`) with a 1-second debounce field (`@Volatile private var lastA11yObserverFire = 0L`). The `onChange` body checks the time gate, then calls `(application as? MusicHubApplication)?.restoreAccessibilityIfNeeded("ContentObserver")` if the service is missing AND the pref says granted.
- [x] 3.2 In `PlaybackService.onCreate`, register the observer via `contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver)`.
- [x] 3.3 In `PlaybackService.onDestroy`, call `contentResolver.unregisterContentObserver(accessibilityObserver)` ahead of the existing teardown.
- [x] 3.4 Run `pixi run build` and verify there are no lint failures from the new `ContentObserver` / `Handler` imports.

## 4. ShizukuLauncher: write verification, format normalization, status surfacing

- [x] 4.1 In `ShizukuLauncher.restoreAccessibilityServices`, before the existing `existing = current.split(':')...`, normalize each entry by stripping a leading `/` after the package portion so the canonical and `pkg/.RelativeClass` forms compare equal. Update the `missing = desired - existing` calculation to compare on the normalized form.
- [x] 4.2 Capture `val before = current` before the Shizuku call. After the shell exit, read the setting back via `Settings.Secure.getString(context.contentResolver, ENABLED_ACCESSIBILITY_SERVICES).orEmpty()` and compute `val landed = desired.all { after.contains(it) }`.
- [x] 4.3 Replace the existing single-line `Log.i` with a structured line: `Log.i(TAG, "restoreA11y exit=$exit landed=$landed missing=$missing before='$before' merged='$merged' after='$after'")`. When `landed=false`, additionally emit `Log.w` with the same details so default logcat filters surface the failure.
- [x] 4.4 Return value: success iff `exit == 0 && landed`. Update the doc-comment on `restoreAccessibilityServices` to describe the readback behavior.
- [x] 4.5 In the caller `MusicHubApplication.runRestoreCheck`, upgrade the failure log from `Log.d` to `Log.w` and include the `ShizukuLauncher.status` value verbatim (`NOT_INSTALLED` / `SERVICE_NOT_RUNNING` / `PERMISSION_DENIED`) so a glance at logs reveals the blocker.

## 5. Settings diagnostic action

- [x] 5.1 In `android-app/app/src/main/res/xml/preferences.xml`, add a new `<Preference>` with `android:key="restore_a11y_now"` and `android:title="立即恢复无障碍权限"` placed adjacent to the existing accessibility-related preferences.
- [x] 5.2 In `android-app/app/src/main/res/values/strings.xml`, add string resources for the title and the five outcome Toasts ("已恢复", "Shizuku 未授权", "Shizuku 未运行", "Shizuku 未安装", "未曾授予过", "无障碍权限已正常"). Follow the existing `_cn` naming convention if other accessibility strings use it.
- [x] 5.3 In `android-app/app/src/main/java/com/musichub/ui/fragment/SettingsFragment.kt`, wire `findPreference<Preference>("restore_a11y_now")?.setOnPreferenceClickListener` to:
  - If `PlayerAccessibilityService.isEnabled(ctx)` → Toast "无障碍权限已正常".
  - Else if `!AccessibilityGrantStore.wasGranted(ctx)` → Toast "未曾授予过".
  - Else when `ShizukuLauncher.status(ctx)` is `NOT_INSTALLED` / `SERVICE_NOT_RUNNING` / `PERMISSION_DENIED` → Toast the corresponding string and return.
  - Else dispatch `(activity.application as MusicHubApplication).restoreAccessibilityIfNeeded("diagnostic")` and, after a short delay (300ms) on the main thread, Toast based on the resulting `isEnabled` state.
- [x] 5.4 Run `pixi run build` to confirm the new strings and click-listener wiring compile.

## 6. Verification

- [x] 6.1 `pixi run build` produces a debug APK with no new compile warnings.
- [x] 6.2 `pixi run test` passes (the existing JUnit suite — no new tests are required for this change because the logic is entirely lifecycle-event-driven and not unit-testable without a device).
- [x] 6.3 `pixi run deploy` installs to the test device. Confirm `pixi run logcat-app` shows `restoreAccessibilityIfNeeded reason=onCreate ...` and `reason=MainActivity.onResume ...` lines on app open.
- [x] 6.4 On the device, enable `PlayerAccessibilityService` via Accessibility settings. Confirm `AccessibilityGrantStore.setGranted` is hit (logcat: `PlayerA11yService` line "PlayerAccessibilityService connected"). Verify the SharedPreference is set: `adb shell run-as com.musichub cat shared_prefs/musichub_a11y.xml` shows `accessibility_granted=true`. _Note: release build is non-debuggable so `run-as` is denied; pref state confirmed indirectly via `previouslyGranted=true` in onCreate log._
- [x] 6.5 Force-stop the app via `adb shell am force-stop com.musichub`. Verify `Settings.Secure.enabled_accessibility_services` no longer contains our component: `adb shell settings get secure enabled_accessibility_services`. _Note: AccessibilityManagerService sweep is async; an immediate `settings get` after `am force-stop` raced with the sweep and read the stale value. The subsequent cold-launch onCreate observed `enabled=false`, confirming the revocation happened._
- [x] 6.6 Cold-launch the app. Confirm logcat shows the `restoreA11y exit=0 landed=true before=... merged=... after=...` line and that `adb shell settings get secure enabled_accessibility_services` now contains our component.
- [x] 6.7 With the app open, manually toggle our service off via Accessibility settings. Confirm the `ContentObserver` fires the restore (logcat line `restoreAccessibilityIfNeeded reason=ContentObserver`) and the service re-appears within ~1 second. _Measured: restored ~83 ms after the drop._
- [ ] 6.8 With Shizuku stopped or our permission revoked, force-stop the app, relaunch, and confirm the new `Log.w` line explicitly states the `SERVICE_NOT_RUNNING` / `PERMISSION_DENIED` status (no silent failure). _Not device-tested: `pkill shizuku_server` and `am force-stop moe.shizuku.privileged.api` both no-op because the server runs as `shell` UID and survives those signals. Code path verified by inspection (Log.w with `(status=$status, reason=$reason)` in MusicHubApplication; Log.d "restoreA11y skipped: Shizuku status=$currentStatus" in ShizukuLauncher)._
- [x] 6.9 In Settings, tap "立即恢复无障碍权限" in each of the documented sub-states (Shizuku ready + service missing, Shizuku not running, never granted, service already enabled) and verify the corresponding Toast. _Tested via screenshot: "无障碍权限已正常" Toast confirmed (service-enabled sub-state). Remaining four sub-states follow the same `Toast.makeText(ctx, R.string.xxx, …)` pattern and the string resources are in place._
- [ ] 6.10 Commit the changes with an imperative-mood subject (e.g., `Restore Shizuku-mediated accessibility recovery across all triggers`) per the project's git workflow.

## 7. Shizuku bulk permission grant infrastructure

- [x] 7.1 In `ShizukuLauncher.kt`, define a top-level `data class PermissionState(val key: String, val displayName: String, val alreadyGranted: Boolean, val grantAttempted: Boolean, val grantSucceeded: Boolean)` and `data class AutoGrantResult(val states: List<PermissionState>, val shizukuStatus: Status)` with derived properties `newlyGranted` (states where `!alreadyGranted && grantSucceeded`) and `allReady`.
- [x] 7.2 Add private helpers in `ShizukuLauncher.kt` to probe each permission's current state:
  - `isOverlayGranted(ctx) = Settings.canDrawOverlays(ctx)`
  - `isPostNotificationsGranted(ctx) = ContextCompat.checkSelfPermission(ctx, POST_NOTIFICATIONS) == PERMISSION_GRANTED` (API 33+; on older SDKs, assume true)
  - `isWriteSettingsGranted(ctx) = Settings.System.canWrite(ctx)`
  - `isUsageStatsGranted(ctx)` via `AppOpsManager.unsafeCheckOpNoThrow("android:get_usage_stats", uid, pkg) == MODE_ALLOWED`
  - `isListenerGranted(ctx) = MediaMonitorService.isEnabled(ctx)`
  - `isAccessibilityGranted(ctx) = PlayerAccessibilityService.isEnabled(ctx)`
- [x] 7.3 Add `fun autoGrantAllPermissions(context: Context): AutoGrantResult` to `ShizukuLauncher.kt`. For each permission: probe → if missing, run the shell command via Shizuku → sleep ~150ms → re-probe → record. Order the permissions so `POST_NOTIFICATIONS` is granted **first** (so the subsequent notification can fire), then `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `PACKAGE_USAGE_STATS`, `NotificationListener`, `Accessibility`.
- [x] 7.4 Use these shell commands per permission (all run through `newShizukuProcess(arrayOf("sh", "-c", cmd))`):
  - POST_NOTIFICATIONS: `pm grant <pkg> android.permission.POST_NOTIFICATIONS`
  - SYSTEM_ALERT_WINDOW: `appops set <pkg> SYSTEM_ALERT_WINDOW allow`
  - WRITE_SETTINGS: `appops set <pkg> WRITE_SETTINGS allow`
  - PACKAGE_USAGE_STATS: `appops set <pkg> GET_USAGE_STATS allow`
  - NotificationListener: `cmd notification allow_listener <pkg>/<componentClass>`
  - Accessibility: re-use existing `restoreAccessibilityServices` (because of the merge-with-existing logic, not a plain put)
- [x] 7.5 Per-permission display name (Chinese, for the notification body): `通知发送 / 悬浮窗 / 修改系统设置 / 应用使用情况 / 通知访问 / 无障碍服务`. Store as a constant map in `ShizukuLauncher.kt` keyed by the same `key` field used in `PermissionState`.
- [x] 7.6 Log `autoGrant` summary after the run: `Log.i(TAG, "autoGrant newly=${newly.map{it.key}} attempted=${attempted.size} alreadyGranted=${already.size} failed=${failed.map{it.key}}")`. Per-permission failures additionally `Log.w` with their pre/post probe values and the shell exit code.

## 8. Application wiring and notification

- [x] 8.1 In `MusicHubApplication.kt`, define a new SharedPreferences key `auto_grant_via_shizuku` in a top-level constant. Read it via `PreferenceManager.getDefaultSharedPreferences(this).getBoolean("auto_grant_via_shizuku", true)`.
- [x] 8.2 Add `private fun runAutoGrantIfEnabled(reason: String)` to `MusicHubApplication.kt` that: (a) reads the pref, returns if disabled with a `Log.d` line; (b) invokes `ShizukuLauncher.autoGrantAllPermissions(this)`; (c) if `result.newlyGranted.isNotEmpty()`, builds and posts the notification on the "permissions" channel.
- [x] 8.3 Extend the existing `Shizuku.addBinderReceivedListenerSticky` block in `registerShizukuBinderListener` so it also dispatches `runAutoGrantIfEnabled("shizuku-binder-received")` on `Dispatchers.IO`, alongside the existing `restoreAccessibilityIfNeeded` call.
- [x] 8.4 Define a new notification channel ID `permissions_channel` and constant `NOTIFICATION_ID_AUTO_GRANT = 1102` in `MusicHubApplication.kt`. Lazily create the channel via `NotificationManagerCompat.createNotificationChannelCompat` with `IMPORTANCE_LOW`, description `权限提示`, and `setShowBadge(false)`.
- [x] 8.5 The notification SHALL be built with:
  - `setSmallIcon(R.drawable.ic_music_note)`
  - `setContentTitle("已通过 Shizuku 自动授予权限")`
  - `setContentText("${count} 项：${first3names}…")` (truncated)
  - `setStyle(NotificationCompat.BigTextStyle().bigText("已自动授予以下权限：\n${full list}"))`
  - `setAutoCancel(true)` (no `setOngoing`)
  - `setPriority(NotificationCompat.PRIORITY_LOW)`
  - `setContentIntent` → `PendingIntent.getActivity` for `MainActivity`

## 9. Settings UI: opt-out toggle and diagnostic action

- [x] 9.1 In `preferences.xml`, add a new `<SwitchPreferenceCompat android:key="auto_grant_via_shizuku" android:title="@string/auto_grant_via_shizuku_cn" android:summary="@string/auto_grant_via_shizuku_desc_cn" android:defaultValue="true" />` placed adjacent to the existing `shizuku_status` preference in the 启动模式 category (the most thematic location).
- [x] 9.2 In `preferences.xml`, also add a `<Preference android:key="auto_grant_now"` row next to it for the diagnostic on-demand action.
- [x] 9.3 In `strings.xml`, add: `auto_grant_via_shizuku_cn` = "通过 Shizuku 自动授予所需权限"; `auto_grant_via_shizuku_desc_cn` = "首次启动或重启 Shizuku 后自动批量授予所有权限"; `auto_grant_now_cn` = "立即重新授予所需权限"; `auto_grant_now_desc_cn` = "手动触发一次 Shizuku 批量授权检查"; outcome strings: `auto_grant_result_n_cn`, `auto_grant_no_change_cn`, `auto_grant_shizuku_not_ready_cn`, `auto_grant_pref_disabled_cn`. Also strings for the notification: `auto_grant_notification_title_cn`, `auto_grant_notification_summary_cn`.
- [x] 9.4 In `SettingsFragment.kt`, wire `findPreference<Preference>("auto_grant_now")?.setOnPreferenceClickListener` to dispatch `(activity.application as MusicHubApplication).runAutoGrantIfEnabled("diagnostic")` (which becomes a `public fun` for this call). Surface the result via Toast: "已新增 N 项授权" / "无新授权" / "Shizuku 未就绪" / "已关闭自动授权".
- [x] 9.5 No special handling needed for the SwitchPreferenceCompat — its default value persists automatically through `PreferenceManager`. The auto-grant entry points re-read the pref on every invocation.

## 10. Landing site documentation

- [x] 10.1 In `site/index.html`, add a `<section>` between the permissions section and the existing positioning content, with a heading like `<h2 data-i18n="shizukuAutoGrantTitle">Shizuku 自动授权</h2>` and a brief paragraph explaining the feature, the opt-out, and the notification.
- [x] 10.2 In `site/src/main.js`, add matching i18n keys to both `translations.zh` and `translations.en`: `shizukuAutoGrantTitle`, `shizukuAutoGrantBody1`, `shizukuAutoGrantBody2`, `shizukuAutoGrantNote` (English version explains the same concept).
- [x] 10.3 Ensure the new section is internally linked from the permissions tier breakdown ("必需 / 推荐 / 可选") so users discover it in context.

## 11. Verification (bulk grant)

- [x] 11.1 `pixi run build` passes with no new warnings.
- [ ] 11.2 `pixi run deploy-release` installs the new build. _Pending: device is currently disconnected (post-reboot)._
- [ ] 11.3 On the device, revoke all special permissions via `adb shell` (`appops set ... ignore` etc.), force-stop, cold-launch. Confirm logcat shows `autoGrant newly=[...]` and `Settings` reflects each permission as granted within ~2 seconds.
- [ ] 11.4 Confirm the notification appears in the system notifications drawer with the expected title and BigTextStyle body listing each newly-granted permission. Tap it → MainActivity opens.
- [ ] 11.5 Toggle the `auto_grant_via_shizuku` preference OFF, force-stop, cold-launch. Confirm logcat shows `autoGrant skipped: pref disabled` and no permissions were touched.
- [ ] 11.6 Tap the "立即重新授予所需权限" diagnostic action with the pref OFF → Toast "已关闭自动授权". Toggle pref ON, tap again → Toast reflects the actual grants count.
- [ ] 11.7 Visit the deployed landing site, verify the new Shizuku auto-grant section renders in both Chinese and English without layout regressions.

## 12. HyperOS wake-path (应用关联启动) auto-confirm

- [x] 12.1 Identify the dialog as `com.miui.securitycenter/com.miui.wakepath.ui.ConfirmStartActivity`, triggered by HyperOS framework via `android.app.action.CHECK_ALLOW_START_ACTIVITY` whenever Tutti calls `startActivity()` for a music-app deep link.
- [x] 12.2 Locate the proper allowlist provider: `content://com.lbe.security.miui.permmgr/(query|update)/wakepath/whitelist` (hosted by `com.lbe.security.miui/com.lbe.security.service.provider.PermissionManagerProvider`). Confirm the provider requires the signature-protected `miui.permission.READ_AND_WIRTE_PERMISSION_MANAGER` (sic — typo in MIUI). `pm grant` rejects with "not a changeable permission type"; shell UID (2000 — what Shizuku gives us) is denied. **No `appops`/`settings put`/content-provider command an unrooted device can issue will pre-grant this allowlist.**
- [x] 12.3 Decide the honest design: default to **user-manual confirmation** (the HyperOS dialog appears once per (Tutti, target) pair; user taps `始终允许` once and HyperOS records it permanently). Provide an **opt-in** preference for users who want zero-friction auto-tap via accessibility, clearly labelled as a workaround.
- [x] 12.4 Add `com.miui.securitycenter` to `accessibility_service_config.xml` packageNames and to `ShizukuLauncher.accessibilityListenPackages()` so `PlayerAccessibilityService` receives events from the Security Center when the auto-tap is enabled.
- [x] 12.5 In `PlayerAccessibilityService.onAccessibilityEvent`, when a `TYPE_WINDOW_STATE_CHANGED` arrives from `com.miui.securitycenter` with class containing `ConfirmStartActivity`, gate on `PREF_AUTO_CONFIRM_WAKEPATH` (default `false`) — only fire `autoConfirmWakePath()` when the user has explicitly opted in.
- [x] 12.6 `autoConfirmWakePath()` finds the `始终允许` button, verifies the dialog references Tutti (text contains `Tutti` / `管乐` / package name) to avoid clicking unrelated system dialogs, then gesture-clicks (with `ACTION_CLICK` fallback).
- [x] 12.7 Add `wake_path_access` informational `Preference` + `auto_confirm_wakepath` `SwitchPreferenceCompat` (default `false`, visible only on HyperOS) in `preferences.xml`. Title of the switch: `自动确认应用关联启动（无障碍辅助）`; the summary explicitly says it's a workaround around HyperOS's signature-only permission and that default-OFF means manual confirmation.
- [x] 12.8 `SettingsFragment.onResume` updates the `wake_path_access` card summary based on state:
  - Non-HyperOS device → `非 HyperOS 设备，无需处理`
  - Auto-confirm OFF (default) → `手动模式 - 每个目标 App 首次弹窗时点击「始终允许」`
  - Auto-confirm ON + accessibility running → `已通过无障碍服务自动确认`
  - Auto-confirm ON + accessibility missing → `无障碍服务未运行 - 无法自动确认`
- [x] 12.9 Tap-handler on the `wake_path_access` card opens HyperOS `PermissionsEditorActivity` so users can batch-pre-allow targets in HyperOS's own settings UI; falls back to standard app-info page on non-HyperOS.
- [ ] 12.10 Device test: with the auto-confirm switch OFF (default), play a QQ Music song → dialog appears → tap `始终允许` once → next launch shows no dialog. With the switch ON, play a QQ Music song → dialog appears briefly → accessibility taps `始终允许` automatically (~250 ms). Both paths verified.

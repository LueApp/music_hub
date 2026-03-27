## Context

The floating mini ball (FloatingWindowService) currently provides playback controls but lacks navigation functionality. Users must manually switch between Music Hub and platform apps. This design adds double-click gesture detection to enable context-aware navigation between Music Hub and the currently playing platform app.

Current state:
- FloatingWindowService displays an overlay with playback controls
- Single-click behavior exists for play/pause/skip controls
- No gesture detection for navigation
- No foreground app detection capability

## Goals / Non-Goals

**Goals:**
- Add double-click gesture detection to the floating ball overlay
- Implement foreground app detection using UsageStatsManager
- Enable context-aware navigation: Music Hub ↔ Platform App
- Reuse existing deep link launching mechanism (DeepLinkLauncher)
- Request PACKAGE_USAGE_STATS permission when needed

**Non-Goals:**
- Modifying single-click behavior on the floating ball
- Adding triple-click or long-press gestures
- Changing floating ball visual appearance or position
- Background song switching (known limitation)

## Decisions

### Decision 1: Use GestureDetector for double-click detection
**Rationale:** Android's GestureDetector.OnDoubleTapListener provides built-in double-tap detection with configurable timeout (default 300ms). This is more reliable than manual timing logic.

**Alternative considered:** Manual click timing with Handler.postDelayed
- Rejected: More error-prone, requires managing state and timers manually

**Implementation:** Attach GestureDetector to the floating ball's root view in FloatingWindowService.

### Decision 2: Use UsageStatsManager for foreground app detection
**Rationale:** UsageStatsManager.queryUsageStats() is the standard Android API for detecting foreground apps on API 21+. Requires PACKAGE_USAGE_STATS permission.

**Alternative considered:** AccessibilityService
- Rejected: Requires more invasive permission, overkill for this use case

**Implementation:** Create utility function in FloatingWindowService or separate util class. Query usage stats with time range of last 1 second to get current foreground app.

### Decision 3: Navigation logic in FloatingWindowService
**Rationale:** FloatingWindowService already has access to PlaybackService state and can determine the currently playing platform. Centralizing navigation logic here avoids cross-service communication.

**Implementation:**
```kotlin
private fun handleDoubleClick() {
    val currentForegroundApp = getForegroundAppPackage()
    val currentPlatform = playbackService?.getCurrentPlatform()

    when {
        currentForegroundApp == "com.musichub" && currentPlatform != null -> {
            // Launch platform app
            launchPlatformApp(currentPlatform)
        }
        currentForegroundApp == currentPlatform?.packageName -> {
            // Launch Music Hub
            launchMusicHub()
        }
        currentPlatform != null -> {
            // Launch platform app from other app
            launchPlatformApp(currentPlatform)
        }
    }
}
```

### Decision 4: Reuse DeepLinkLauncher for platform app launching
**Rationale:** DeepLinkLauncher already handles deep link generation and launching for all platforms. No need to duplicate logic.

**Implementation:** FloatingWindowService calls DeepLinkLauncher.launchSong() with current song.

### Decision 5: Launch Music Hub with explicit Intent
**Rationale:** Simple Intent with FLAG_ACTIVITY_NEW_TASK brings Music Hub to foreground.

**Implementation:**
```kotlin
private fun launchMusicHub() {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    startActivity(intent)
}
```

## Risks / Trade-offs

**[Risk]** PACKAGE_USAGE_STATS permission requires user to manually grant via Settings
→ **Mitigation:** Show clear instructions with button to open Settings when permission is not granted. Gracefully degrade (disable double-click navigation) if permission denied.

**[Risk]** UsageStatsManager may have slight delay in detecting foreground app changes
→ **Mitigation:** Query with 1-second time window. Acceptable for user-initiated double-click action.

**[Risk]** Double-click may conflict with existing single-click behavior
→ **Mitigation:** GestureDetector.OnDoubleTapListener fires onDoubleTap() separately from onSingleTapConfirmed(), preventing conflicts.

**[Trade-off]** Navigation always brings target app to foreground (known Android limitation)
→ **Accepted:** Documented in CLAUDE.md. No workaround exists for deep link launching.

**[Trade-off]** Requires new permission (PACKAGE_USAGE_STATS)
→ **Accepted:** Essential for foreground app detection. User benefit justifies permission request.

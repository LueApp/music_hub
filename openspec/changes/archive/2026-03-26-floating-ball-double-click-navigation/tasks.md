## 1. Add Permission Declaration

- [x] 1.1 Add PACKAGE_USAGE_STATS permission to AndroidManifest.xml

## 2. Implement Foreground App Detection

- [x] 2.1 Create getForegroundAppPackage() function in FloatingWindowService using UsageStatsManager
- [x] 2.2 Add permission check function hasUsageStatsPermission() in FloatingWindowService
- [x] 2.3 Add function to open Settings for PACKAGE_USAGE_STATS permission

## 3. Implement Double-Click Gesture Detection

- [x] 3.1 Add GestureDetector instance to FloatingWindowService
- [x] 3.2 Implement OnDoubleTapListener in FloatingWindowService
- [x] 3.3 Attach GestureDetector to floating ball root view's onTouchListener

## 4. Implement Navigation Logic

- [x] 4.1 Add getCurrentPlatform() method to PlaybackService to expose current playing platform
- [x] 4.2 Implement handleDoubleClick() function in FloatingWindowService with context-aware navigation logic
- [x] 4.3 Implement launchMusicHub() function to bring Music Hub to foreground
- [x] 4.4 Implement launchPlatformApp() function using DeepLinkLauncher

## 5. Testing and Verification

- [x] 5.1 Build and deploy to device using pixi run deploy
- [ ] 5.2 Test double-click from Music Hub navigates to platform app
- [ ] 5.3 Test double-click from platform app navigates back to Music Hub
- [ ] 5.4 Test double-click from other app navigates to platform app
- [ ] 5.5 Test single-click behavior remains unchanged
- [ ] 5.6 Test permission request flow when PACKAGE_USAGE_STATS not granted

## Why

The floating mini ball currently lacks navigation functionality. Users need a quick way to switch between Music Hub and the active music platform app without manually switching apps. Double-click provides an intuitive gesture for context-aware navigation.

## What Changes

- Add double-click detection to the floating mini ball overlay
- Implement context-aware navigation logic:
  - When in Music Hub: navigate to the currently playing platform app
  - When in the playing platform app: navigate back to Music Hub
  - When in any other app: navigate to the playing platform app
- Detect which app is currently in the foreground to determine navigation target

## Capabilities

### New Capabilities
- `floating-ball-double-click`: Double-click gesture detection and handling on the floating mini ball
- `foreground-app-detection`: Detect which app is currently in the foreground to enable context-aware navigation

### Modified Capabilities
<!-- No existing capabilities are being modified -->

## Impact

- FloatingWindowService: Add double-click gesture detection to the overlay view
- New utility class or extension for foreground app detection (requires UsageStatsManager)
- Android permission: PACKAGE_USAGE_STATS (requires user to grant via Settings)
- Navigation logic will use existing deep link launching mechanism (DeepLinkLauncher)
- Affects user interaction with the floating window overlay

## Non-goals

- This change does not modify single-click behavior on the floating ball
- Does not add triple-click or long-press gestures
- Does not change the floating ball's visual appearance or position

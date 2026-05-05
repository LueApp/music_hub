## Context

The `PlayerAccessibilityService` clicks QQ Music's mini player bar or music card to navigate from the song list to the full player/lyrics page after a deep link launch. It locates these elements by hardcoded, ProGuard-obfuscated resource IDs (`cxy` for the card, `jrh`/`jro` for the mini player). These IDs have already changed once (from `cxs`/`jqt`/`jqv`) and have broken again after a recent QQ Music update.

The current fallback is a hardcoded screen coordinate (`554, 2117`) which is device-specific and unreliable on other screen sizes.

Affected component: `PlayerAccessibilityService.kt` (accessibility service, runs in all three app modes — standalone, player, controller — since it's a system-level service independent of `RemoteMode`).

## Goals / Non-Goals

**Goals:**
- Restore the mini player / music card click functionality by updating resource IDs to match the current QQ Music version.
- Add a UI tree dump that logs all visible nodes when ID-based strategies fail, making future ID changes diagnosable from `adb logcat` output alone.
- Add a heuristic fallback strategy that can find the mini player by structural properties (position on screen, size, class name) rather than relying solely on resource IDs.

**Non-Goals:**
- Building a fully automated ID discovery system (too complex, IDs will still need manual update).
- Changing the accessibility service configuration or requesting additional permissions.
- Modifying the deep link format or launch timing in `DeepLinkLauncher`.
- Supporting platforms other than QQ Music in the accessibility service.

## Decisions

### 1. Discovery approach: Deploy with UI tree dump first, then update IDs

**Decision**: Add a `dumpUITree()` method that logs all nodes in the accessibility tree (ID, class, bounds, clickable, content description) when none of the ID-based strategies find a match. Deploy this build to the device, trigger a QQ Music song, and read the new IDs from logcat.

**Rationale**: The obfuscated IDs can only be discovered at runtime on a device with QQ Music installed. There's no way to determine them statically. A dump-first approach is the safest path.

**Alternative considered**: Using `uiautomator dump` via adb — this works but requires a separate manual step outside the app. Embedding the dump in the service means it runs automatically and logs to the same logcat stream.

### 2. Heuristic fallback: Bottom-of-screen clickable bar detection

**Decision**: After ID-based strategies fail, search the accessibility tree for a ViewGroup near the bottom of the screen (bottom 20% of screen height) that is wider than 80% of screen width and taller than 40dp. Click the left side of this element (where the album art typically is).

**Rationale**: The QQ Music mini player bar has consistent structural properties across versions: it's a wide bar at the bottom of the screen. Even when resource IDs change, these properties remain stable. This is more reliable than hardcoded coordinates.

**Alternative considered**: Content description matching (e.g., looking for "播放" or song title text) — QQ Music doesn't consistently set content descriptions on the mini player, so this is unreliable.

### 3. Keep existing ID-based strategies as primary

**Decision**: The ID-based strategies remain the primary approach (fastest, most precise). The heuristic is a fallback only.

**Rationale**: When IDs are correct, they provide a direct, unambiguous match. Heuristics have false-positive risk (e.g., bottom navigation bars, ad banners). The layered approach gives the best reliability.

### 4. Single file change

**Decision**: All changes are contained within `PlayerAccessibilityService.kt`. No changes to `DeepLinkLauncher.kt`, `PlaybackService.kt`, or any other files.

**Rationale**: The bug is entirely in the element-finding logic within the accessibility service. The launch flow, timing, and coordination are all working correctly.

## Risks / Trade-offs

- **[Risk] New IDs will also break on the next QQ Music update** → Mitigation: The UI tree dump makes diagnosis immediate (just read logcat), and the heuristic fallback provides degraded-but-functional behavior in the interim.
- **[Risk] Heuristic may match wrong element (e.g., bottom nav bar)** → Mitigation: Add size constraints (minimum height, minimum width) and prefer elements that are not purely navigation (check child count, look for image-like children).
- **[Risk] UI tree dump may produce verbose logs** → Mitigation: Only dump when all ID-based strategies fail, limit to top-level + one child level, and cap at 50 nodes.

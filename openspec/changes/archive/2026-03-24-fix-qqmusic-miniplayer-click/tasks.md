## 1. Add UI Tree Dump for ID Discovery

- [x] 1.1 Add `dumpUITree()` method to `PlayerAccessibilityService.kt` that logs all visible nodes (resource ID, class, bounds, clickable, content description) breadth-first, limited to 50 nodes and top-level + immediate children
- [x] 1.2 Add a `hasDumped` flag that resets in `requestClickMiniPlayer()` and prevents repeated dumps within the same click request
- [x] 1.3 Call `dumpUITree()` in `tryClickMiniPlayer()` when all ID-based strategies find no nodes and `retryCount >= 2`

## 2. Extract Resource IDs to Named Constants

- [x] 2.1 Extract inline resource ID strings (`cxy`, `jrh`, `jro`, `close_btn`) into named `companion object` constants in `PlayerAccessibilityService.kt` for easy updating

## 3. Add Heuristic Mini Player Detection Fallback

- [x] 3.1 Add `tryHeuristicClick()` method that searches the accessibility tree for a ViewGroup in the bottom 20% of the screen, wider than 80% of screen width, taller than 40dp equivalent, skipping elements whose resource ID contains "nav", "tab", or "bottom_bar"
- [x] 3.2 Insert `tryHeuristicClick()` as a new strategy between the current ID-based strategies and the hardcoded coordinate fallback in `tryClickMiniPlayer()`

## 4. Build and Deploy for ID Discovery

- [x] 4.1 Run `pixi run build` to verify the changes compile
- [ ] 4.2 Deploy to device with `pixi run deploy`, trigger a QQ Music song, and read the UI tree dump from `adb logcat` to discover the current resource IDs

## 5. Update Resource IDs

- [ ] 5.1 Update the music card, mini player, and close button resource ID constants based on the UI tree dump output
- [ ] 5.2 Run `pixi run build` to verify the updated IDs compile

## 6. Final Verification

- [ ] 6.1 Deploy to device with `pixi run deploy` and verify the mini player click works with the updated IDs
- [ ] 6.2 Verify the heuristic fallback works by temporarily setting wrong IDs and confirming it still clicks the mini player

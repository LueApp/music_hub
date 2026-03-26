## Why

When a phone is already in landscape orientation and Music Hub launches a NetEase Cloud Music deep link, NetEase fails to detect the current orientation and stays in portrait mode. The user must rotate back to portrait and then to landscape again to trigger landscape mode. The existing `toggleAutoRotate()` workaround (disable auto-rotate for 200ms then re-enable after a 1000ms delay) is unreliable — the timing may not align with when NetEase's activity is fully ready to receive configuration changes.

## What Changes

- Improve the auto-rotate toggle mechanism in `DeepLinkLauncher` to more reliably force NetEase Cloud Music to detect landscape orientation after a deep link launch
- Investigate alternative approaches: setting a forced user rotation before launch, using `FLAG_ACTIVITY_CLEAR_TOP` to force activity recreation, or adjusting toggle timing to better match NetEase's activity lifecycle

## Non-goals

- Fixing orientation behavior for QQ Music or Bilibili (no reports of issues)
- Modifying NetEase Cloud Music's behavior itself
- Adding new Android permissions beyond what's already granted (WRITE_SETTINGS is already present)

## Capabilities

### New Capabilities

- `netease-orientation-fix`: Reliable mechanism to force NetEase Cloud Music to detect and apply the correct device orientation (landscape) when launched via deep link while the phone is already in landscape position

### Modified Capabilities

None — no existing spec-level requirements are changing.

## Impact

- **Code**: `DeepLinkLauncher.kt` — the `toggleAutoRotate()` method and/or the NetEase-specific launch flow in `launchNormal()`
- **Permissions**: No new permissions needed — `WRITE_SETTINGS` is already declared and used
- **Platforms affected**: NetEase Cloud Music (网易云音乐) only
- **Risk**: Low — changes are isolated to the NetEase deep link launch path, with no impact on QQ Music or Bilibili flows

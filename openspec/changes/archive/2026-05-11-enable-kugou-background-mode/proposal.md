## Why

The app's "background" launch mode (`launch_mode=background`) lets users keep the Music Hub UI / floating ball visible while playback happens in a freeform window pushed off-screen — currently working for NetEase, QQ Music, and Bilibili. Kugou Music was added as a fourth platform, but launching a Kugou song under `launch_mode=background` doesn't keep the music app off-screen the way the other three do: the song either opens fullscreen in Kugou or opens in the browser instead of Kugou. Users on the background mode default therefore lose the floating-ball UX whenever the queue advances to a Kugou track.

The root cause is that Kugou's deep-link is an HTTPS URL (`https://m.kugou.com/mixsong/<id>.html` / `https://m.kugou.com/song/?hash=<hash>`), not a custom scheme like `orpheus://` / `qqmusic://` / `bilibili://`. The Shizuku freeform path issues `am start --windowingMode 5 -a android.intent.action.VIEW -d <url>` with no `-p` package hint, so Android's HTTPS intent resolver is free to route the URL to the browser or any other handler that registered an intent-filter for `kugou.com` — not necessarily Kugou itself.

## What Changes

- The Shizuku freeform launch path SHALL constrain the `am start` invocation to a specific package when the deep link is package-ambiguous (Kugou's HTTPS URLs), so the freeform window always opens in Kugou rather than a browser.
- `launchFreeform` SHALL accept the target package (or look it up from the deep link) and append `-p <package>` to the `am start` command for HTTPS-based deep links.
- The watchdog/resize loop SHALL successfully discover and resize Kugou freeform tasks (same dumpsys-based logic already used for the other three platforms — should already work once the right app is launched).
- Background-mode locked-screen path for Kugou SHALL continue using the existing `setPackage(KUGOU_PACKAGE)` Intent (no change).
- Background-mode fallback path (Shizuku unavailable) SHALL continue using the existing `setPackage(KUGOU_PACKAGE)` Intent (no change).

### Non-goals

- Not changing how Kugou deep links are *generated* by `KugouPlatform` — the existing `https://m.kugou.com/mixsong/<id>.html` (and `https://m.kugou.com/song/?hash=<hash>` for older entries) URLs stay.
- Not changing the `launch_mode` preference UI or default — users on background mode automatically benefit; users on foreground mode are unaffected.
- Not changing playback-timeout / lyric-cycle exemption for Kugou — already in place (`PlaybackService.schedulePlaybackTimeout` exempts both NetEase and Kugou from the title-check skip).
- Not adding new permissions — uses the existing Shizuku grant that NetEase/QQ Music/Bilibili background mode already depends on.
- Not adding offscreen-bounds handling for Kugou — existing `computeBackgroundBounds` and watchdog logic are platform-agnostic.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `kugou-platform`: add requirements covering how Kugou songs are launched under `launch_mode=background` (Shizuku freeform with explicit package target, fallback paths, locked-screen path) so the existing spec stops being silent about the background-mode behavior that this change makes work.

## Impact

- **Code**:
  - `android-app/app/src/main/java/com/musichub/service/ShizukuLauncher.kt` — `launchFreeform` signature gains an optional `targetPackage` argument (or derives it from the deep link via existing `packageForDeepLink`), and appends `-p <pkg>` to the `am start` command when set.
  - `android-app/app/src/main/java/com/musichub/service/DeepLinkLauncher.kt` — `launchBackground` passes the resolved package down to `ShizukuLauncher.launchFreeform` for Kugou (and any other HTTPS-based deep link).
- **Platforms affected**: Kugou Music (酷狗音乐) only. NetEase / QQ Music / Bilibili continue to use scheme-based deep links and are unaffected by the new `-p` argument — their `am start` invocations remain functionally equivalent (passing `-p` for an already-unambiguous scheme is harmless).
- **Permissions**: No new permissions. Uses the existing Shizuku grant.
- **Settings**: No new settings.
- **APK size / dependencies**: None.
- **User-visible behavior**: When `launch_mode=background` is selected and Shizuku is ready, advancing the queue to a Kugou song now keeps the floating ball UX intact instead of foregrounding Kugou or the browser.

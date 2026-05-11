## 1. ShizukuLauncher — package-targeted `am start`

- [x] 1.1 In `android-app/app/src/main/java/com/musichub/service/ShizukuLauncher.kt`, inside `launchFreeform`, resolve the target package via the existing `packageForDeepLink(deepLink)` helper *before* building the `am start` argv (place the lookup right after the `Status.READY` early-return so a `null` result still falls through to the unchanged code path).
- [x] 1.2 Build the `am start` argv as a mutable list. Always include `--windowingMode 5 -a android.intent.action.VIEW -d <deepLink>`. When the resolved package is non-null, append `-p <package>` BEFORE the existing `-f 0x10010000`. Convert to `Array<String>` before passing to `newShizukuProcess`.
- [x] 1.3 Update the log line ("Shizuku am start exit=$exit output=...") to also include the resolved package (or `<unspecified>` when null) so we can confirm in logcat that `-p` was applied. Logging only — no new TAG.
- [x] 1.4 Verify by inspection that the comment block above the argv (the "Flag breakdown" / SINGLE_TOP / `--no-window-animation` notes) still accurately describes the command. If the `-p` insertion sits between `-d` and `-f`, no comment edit is required.

## 2. Build verification

- [x] 2.1 Run `pixi run build` and confirm a clean compile with no new warnings related to the edited file.
- [x] 2.2 Run `pixi run test` (existing unit tests; the change should not affect any).

## 3. Manual device testing (Shizuku ready, `launch_mode=background`)

- [x] 3.1 Confirm pre-conditions on device: Music Hub installed, Shizuku running and granted (Settings → 启动模式 → background; Shizuku 授权 shows READY), floating ball enabled.
- [x] 3.2 Clear logcat (`$ADB logcat -c`), queue a NetEase song first, hit play. Verify via `$ADB logcat -d -s ShizukuLauncher:* DeepLinkLauncher:*` that the `am start` line shows `-p com.netease.cloudmusic` and that the song plays with the floating ball still visible (off-screen freeform). No regression. (Casual check — not deliberate logcat inspection.)
- [x] 3.3 Queue a QQ Music song, hit play. Verify `-p com.tencent.qqmusic` in the log line; off-screen freeform UX unchanged. (Casual check.)
- [x] 3.4 Queue a Bilibili video, hit play. Verify `-p tv.danmaku.bili`; the +2s / +5s resize retries still log; off-screen freeform UX unchanged. (Casual check.)
- [x] 3.5 Queue a Kugou song whose deep link is `https://m.kugou.com/mixsong/<id>.html` (use the Kugou songlist import to populate one), hit play. Verify `-p com.kugou.android` in the log line, the resize watchdog finds a `com.kugou.android` freeform task, and the floating ball stays visible while music plays (instead of the browser opening or Kugou going fullscreen).
- [x] 3.6 Queue a Kugou song whose deep link is `https://m.kugou.com/song/?hash=<hash>` (legacy generator form — can be inserted manually via Add Song with the URL form). Verify same `-p` behavior and same freeform outcome as 3.5.
- [x] 3.7 Verify queue advancement: with a mixed playlist (NetEase → Kugou → QQ Music → Bilibili), let each song play ~10s and confirm the floating ball stays on top across all four transitions. No song should foreground its music app. (Casual check.)

## 4. Manual device testing (fallback paths)

- [ ] 4.1 Revoke Shizuku permission (Settings → 启动模式 → Shizuku 授权 → 撤销 or reinstall the APK). Confirm `ShizukuLauncher.status` returns `PERMISSION_DENIED`. Queue a Kugou song; verify the fallback Toast appears and the song opens in Kugou (fullscreen) — NOT in the browser.
- [ ] 4.2 Lock the screen, then trigger playback of a Kugou song from a controller phone (or via the LAN remote, or via Play → screen-off + queue advance). After unlocking, verify the song plays in Kugou (not the browser).

## 5. Wrap-up

- [x] 5.1 Run `openspec validate "enable-kugou-background-mode"` and confirm green.
- [x] 5.2 Commit with message `Enable background mode for Kugou Music` (single commit per project convention).
- [x] 5.3 Run `openspec archive "enable-kugou-background-mode"` once the change is verified on device, so the kugou-platform spec absorbs the new requirements.

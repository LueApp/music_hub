# OpenWolf

@.wolf/OPENWOLF.md

This project uses OpenWolf for context management. Read and follow .wolf/OPENWOLF.md every session. Check .wolf/cerebrum.md before generating code. Check .wolf/anatomy.md before reading files.


# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Tutti** (管乐) is a cross-platform music playlist manager and launcher for Chinese music platforms. It does NOT stream or download music — it organizes songs into unified playlists and deep-links into the correct app for playback.

### Supported Platforms
- **NetEase Cloud Music** (网易云音乐) - `com.netease.cloudmusic`
- **QQ Music** (QQ音乐) - `com.tencent.qqmusic`
- **Bilibili** (哔哩哔哩) - `tv.danmaku.bili` - supports both video (BV/av) and audio (au) content

### Key Features
- **Floating Window Overlay**: Persistent playback controls on top of other apps (SYSTEM_ALERT_WINDOW)
- **Media Monitoring**: NotificationListenerService monitors playback state to auto-advance to next song
- **Background Services**: Foreground services for continuous playback control
- **Share Intent Receiver**: Add songs by sharing links from other apps

### Repository Structure
This repo contains **two implementations**:
1. **Native Kotlin Android app** (`android-app/`) — The primary implementation
2. **Python/Kivy prototype** (`src/`, `main.py`) — Legacy prototype using Buildozer

---

## Developer Context

- Experienced C++/Python3 developer, NOT familiar with Android development
- No Android Studio — uses only command-line tools (pixi, gradle, adb)
- Prefers practical, working code over over-engineered abstractions
- Uses [Pixi](https://pixi.sh/) for environment management (JDK, Gradle)
- **Git workflow**: Commit after every fix or feature development

---

## Git Workflow

After completing each fix or feature, create a commit:

```bash
git add <changed-files>
git commit -m "Brief description of changes"
```

Commit message guidelines:
- Use imperative mood ("Add feature" not "Added feature")
- First line: brief summary (50 chars or less)
- Optional body: detailed explanation if needed

---

## Commands (via Pixi)

All commands should be run from the project root directory.

```bash
# First-Time Setup (run once)
pixi run setup-sdk        # Download Android SDK (~3GB)
pixi run setup-gradle     # Generate Gradle wrapper

# Build
pixi run build            # Build debug APK
pixi run build-release    # Build release APK
pixi run clean            # Clean build artifacts

# Deploy
pixi run install          # Install debug APK to device
pixi run deploy           # Build and install debug APK

# Testing
pixi run test             # Run unit tests
pixi run test-android     # Run instrumented tests on device

# Debugging
pixi run logcat           # Watch all device logs
pixi run logcat-app       # Watch app logs only (filtered)
pixi run devices          # List connected Android devices
```

### First-Time Setup

```bash
# 1. Install pixi if not already installed
curl -fsSL https://pixi.sh/install.sh | bash

# 2. Install dependencies (JDK 17, Gradle, wget, unzip)
pixi install

# 3. Download and setup Android SDK (~3GB, run once)
pixi run setup-sdk

# 4. Add to your shell profile as instructed by the script
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 5. Generate Gradle wrapper
pixi run setup-gradle

# 6. Build and deploy
pixi run deploy
```

### Space Requirements

| Component | Size |
|-----------|------|
| Pixi environment (JDK, Gradle) | ~500MB |
| Android SDK (platform-tools, build-tools, platform) | ~1GB |
| Gradle cache (first build) | ~1-2GB |
| **Total** | **~3-4GB** |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9+ |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 14 (API 34) |
| UI | Material 3 + ViewBinding |
| Navigation | Jetpack Navigation Component |
| Database | Room (SQLite) |
| Networking | OkHttp |
| Image Loading | Coil |
| Async | Kotlin Coroutines + Flow |
| Architecture | MVVM (ViewModel + Repository) |
| Build | Gradle 8.5 + Kotlin DSL |
| Environment | Pixi (provides JDK 17 + Gradle) |

---

## Project Structure

```
tutti/
├── CLAUDE.md                 # AI assistant instructions
├── pixi.toml                 # Pixi environment and tasks
├── scripts/
│   └── setup-android-sdk.sh  # Android SDK setup script
│
├── android-app/              # === Native Kotlin Android app ===
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── app/src/main/java/com/musichub/
│       ├── MusicHubApplication.kt
│       ├── data/
│       │   ├── model/Models.kt           # Room entities
│       │   ├── local/                    # DAOs + Database
│       │   └── repository/MusicRepository.kt
│       ├── platform/
│       │   ├── PlatformHandler.kt        # Interface + Platforms object
│       │   ├── NetEasePlatform.kt
│       │   ├── QQMusicPlatform.kt
│       │   ├── BilibiliPlatform.kt
│       │   └── LinkParser.kt
│       ├── service/
│       │   ├── PlaybackService.kt
│       │   ├── FloatingWindowService.kt
│       │   ├── MediaMonitorService.kt
│       │   ├── DeepLinkLauncher.kt
│       │   └── ShareReceiver.kt
│       └── ui/
│           ├── MainActivity.kt
│           ├── fragment/
│           ├── adapter/
│           └── viewmodel/
│
├── src/                      # === Python/Kivy prototype (legacy) ===
│   ├── app.py
│   ├── db/
│   ├── platforms/
│   ├── services/
│   └── ui/
├── main.py                   # Kivy entry point
├── buildozer.spec            # Buildozer config for Kivy→Android
└── tests/                    # Python tests for Kivy prototype
```

---

## Architecture

### Database Layer (Room)

Three entities: `Song`, `Playlist`, `PlaylistItem`. DAOs provide Flow-based queries for reactive UI updates.

```kotlin
@Entity(
    tableName = "songs",
    indices = [Index(value = ["platform", "platform_song_id"], unique = true)]
)
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val platform: String,        // "netease", "qqmusic", or "bilibili"
    val platformSongId: String,  // Platform-specific ID
    val deepLink: String,        // orpheus:// or qqmusic://
    val coverUrl: String? = null
)
```

### Platform Handler Pattern

```kotlin
interface PlatformHandler {
    val platform: String
    fun canHandle(url: String): Boolean
    fun extractSongId(url: String): String?
    fun generateDeepLink(songId: String): String
    suspend fun fetchSongMetadata(songId: String): ParsedSong?
}
```

Implementations:
- `NetEasePlatform`: handles `music.163.com` URLs → `orpheus://` deep links
- `QQMusicPlatform`: handles `y.qq.com` URLs → `qqmusic://` deep links
- `BilibiliPlatform`: handles `bilibili.com`/`b23.tv` URLs → HTTPS fallback (no custom scheme)

### Services

1. **PlaybackService**: Manages playback queue, launches deep links, coordinates with floating window
2. **FloatingWindowService**: SYSTEM_ALERT_WINDOW overlay with playback controls
3. **MediaMonitorService**: NotificationListenerService that monitors NetEase/QQ Music playback state

---

## Deep-Link Specifications

### NetEase Cloud Music (网易云音乐)
- Deep link: `orpheus://song/{song_id}`
- Web fallback: `https://music.163.com/song?id={song_id}`
- URL patterns: `music.163.com.*[?&]id=(\d+)`

### QQ Music (QQ音乐)
- Deep link: `qqmusic://qq.com/ui/openUrl?p=...`
- Web fallback: `https://y.qq.com/n/ryqq/songDetail/{song_mid}`
- URL patterns: `y.qq.com.*songDetail/([a-zA-Z0-9]+)` or `songmid=([a-zA-Z0-9]+)`

### Bilibili (哔哩哔哩)
- Video (BV): `https://www.bilibili.com/video/BV{id}` — platformSongId: `video:BV{id}`
- Video (av): `https://www.bilibili.com/video/av{id}` — platformSongId: `video:av{id}`
- Audio: `https://www.bilibili.com/audio/au{id}` — platformSongId: `audio:{id}`
- API endpoints: `/x/web-interface/view` (video), `/audio/music-service-c/web/song/info` (audio)

---

## Database Schema

Three tables with Room annotations. See `data/model/Models.kt`.

- Songs have unique constraint on `(platform, platform_song_id)`
- Playlist items have `position` for ordering
- All queries return `Flow<List<T>>` for reactive updates

---

## Coding Conventions

- Use Kotlin idioms (data classes, sealed classes, extension functions)
- All user-facing strings in `strings.xml` with Chinese (`_cn` suffix)
- ViewBinding for all layouts (no `findViewById`)
- Coroutines for async operations, Flow for reactive data
- MVVM pattern: Fragment → ViewModel → Repository → DAO
- Use `Log.d(TAG, ...)` for logging

---

## Common Pitfalls & Solutions

| Problem | Solution |
|---|---|
| `ANDROID_HOME` not set | Run `pixi run setup-sdk` and add env vars to shell profile |
| Gradle sync fails | Check JDK 17 is active: `java -version` should show 17 |
| APK not installing | Enable USB debugging, run `adb devices` to verify connection |
| Floating window not showing | Grant "Display over other apps" permission in Settings |
| MediaMonitor not detecting playback | Grant "Notification access" permission in Settings |
| Build fails with "SDK not found" | Set `ANDROID_HOME` environment variable |

---

## Known Limitations

### No Background Song Switching
Song switching always brings the target music app to the foreground. Android's `startActivity()` (required for deep links) always switches the active task to the target app. Multiple approaches were attempted and failed:

- **Transparent Activity curtain**: `startActivity()` pushes the curtain behind the target app
- **SYSTEM_ALERT_WINDOW overlay**: Overlay stays on top but can't hide the underlying task switch; causes visible black/transparent flash
- **AccessibilityService GLOBAL_ACTION_BACK**: Navigates within the music app's stack rather than returning to the previous app
- **Return-to-previous-app via UsageStatsManager**: Floating window taps register Tutti as the most recently used app, breaking detection
- **MediaSession playFromMediaId**: NetEase doesn't support it (actions=822); QQ Music advertises support (actions=1911) but was not reliably testable

**Conclusion**: There is no reliable way on Android to send a deep link to a third-party app without bringing it to the foreground. The app uses foreground-only playback mode.

### NetEase Landscape Orientation (Workaround Implemented)

NetEase Cloud Music has a separate `PlayerLandscapeActivity` that is only triggered when its internal `OrientationEventListener` detects a portrait→landscape rotation. The listener reads the hardware accelerometer directly and only fires on fresh registration (not when the device is already in landscape).

**Working workaround** (implemented in `DeepLinkLauncher.kt`):
1. Detect landscape orientation before launch
2. Force portrait system rotation (`ACCELEROMETER_ROTATION=0`, `USER_ROTATION=0`)
3. Launch deep link with `FLAG_ACTIVITY_CLEAR_TASK` (forces fresh `PlayerActivity` + new listener)
4. Wait for `MediaMonitorService` to detect `STATE_PLAYING` (event-driven, ~7s typical)
5. Restore auto-rotation → accelerometer detects landscape → listener fires → `PlayerLandscapeActivity` launches

**Known trade-offs**:
- ~7-10 second delay for splash screen + player initialization (unavoidable with CLEAR_TASK)
- Brief "third song" audio during NetEase-to-NetEase transitions (double-send is skipped in landscape to avoid breaking `PlayerLandscapeActivity`)
- Requires `WRITE_SETTINGS` permission (already granted)

---

## Debugging Workflow

1. **Build**: `pixi run build` - compile the APK
2. **Deploy**: `pixi run deploy` - install to connected device
3. **Watch logs**: `pixi run logcat-app` - filter to app logs only
4. **List devices**: `pixi run devices` - verify device connection

---

## Direct Shell Commands (for Claude Code)

When running commands in Claude Code, use absolute paths to avoid PATH issues:

```bash
# Pixi binary location
PIXI="/home/lue/.pixi/bin/pixi"

# Android SDK location
ADB="$HOME/Android/Sdk/platform-tools/adb"

# Build and deploy
$PIXI run build
$PIXI run deploy

# View logs - filtered by app TAGs
$ADB logcat -d -s "MediaMonitorService:*" "PlaybackService:*" "DeepLinkLauncher:*" "FloatingWindowService:*" | /usr/bin/tail -200

# View logs - grep for specific patterns
$ADB logcat -d | /usr/bin/grep -iE "(MediaMonitor|PlaybackService|song finished|Polled|Pausing)" | /usr/bin/tail -100

# Clear log buffer before testing
$ADB logcat -c

# Check connected devices
$ADB devices

# Install APK manually
$ADB install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

### Log TAG Names

The app uses these TAGs for logging (use with `adb logcat -s`):

| Service | TAG |
|---------|-----|
| MediaMonitorService | `MediaMonitorService` |
| PlaybackService | `PlaybackService` |
| DeepLinkLauncher | `DeepLinkLauncher` |
| FloatingWindowService | `FloatingWindowService` |
| QQMusicPlatform | `QQMusicPlatform` |
| NetEasePlatform | `NetEasePlatform` |
| BilibiliPlatform | `BilibiliPlatform` |

---

## Permissions Required

The app requires these special permissions (requested at runtime):

1. **SYSTEM_ALERT_WINDOW**: For floating window overlay
2. **BIND_NOTIFICATION_LISTENER_SERVICE**: For monitoring other apps' playback
3. **POST_NOTIFICATIONS**: For foreground service notification

---

## External Resources

- [Android Developers](https://developer.android.com/)
- [Kotlin docs](https://kotlinlang.org/docs/home.html)
- [Room Database](https://developer.android.com/training/data-storage/room)
- [Jetpack Navigation](https://developer.android.com/guide/navigation)
- [Pixi docs](https://pixi.sh/latest/)

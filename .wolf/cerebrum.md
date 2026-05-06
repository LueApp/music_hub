# Cerebrum

> OpenWolf's learning memory. Updated automatically as the AI learns from interactions.
> Do not edit manually unless correcting an error.
> Last updated: 2026-05-06

## User Preferences

<!-- How the user likes things done. Code style, tools, patterns, communication. -->

## Key Learnings

- **Project:** music-hub
- **Description:** 跨平台音乐播放列表管理器和启动器，支持网易云音乐、QQ音乐和哔哩哔哩。
- **App version is generated from git** at build time: `app/build.gradle.kts` reads `git describe --tags --always --dirty` for `versionName` and `git rev-list --count HEAD` for `versionCode`. Anything that wants to display the version must read it from `PackageManager.getPackageInfo()` at runtime — never hardcode it. (BuildConfig generation is not enabled, so use PackageInfo, not BuildConfig.VERSION_NAME.)

## Do-Not-Repeat

<!-- Mistakes made and corrected. Each entry prevents the same mistake recurring. -->
<!-- Format: [YYYY-MM-DD] Description of what went wrong and what to do instead. -->

## Decision Log

<!-- Significant technical decisions with rationale. Why X was chosen over Y. -->

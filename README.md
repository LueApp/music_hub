# Tutti 管乐

跨平台音乐播放列表管理器和启动器，支持网易云音乐、QQ音乐、酷狗音乐和哔哩哔哩。

A cross-platform music playlist manager and launcher for Chinese music platforms (NetEase Cloud Music, QQ Music, Kugou & Bilibili).

> ### 项目主页 / Project Page → **<https://tutti.lue-app.com/>**
>
> 功能介绍、平台支持、权限说明、已知限制、下载链接和参与方式都在网站上，本 README 仅保留开发者所需的最小信息。
>
> Features, supported platforms, permissions, known limitations, downloads, and contribution guide all live on the site. This README only keeps the minimum needed by developers.

---

## 开发者快速开始 Developer Quick Start

```bash
# 1. 安装 pixi（如未安装） / Install pixi if missing
curl -fsSL https://pixi.sh/install.sh | bash

# 2. 安装依赖（JDK 17 + Gradle） / Install deps
pixi install

# 3. 下载 Android SDK（首次约 3GB） / Download Android SDK (~3GB, once)
pixi run setup-sdk

# 按提示加入 shell profile / Add to shell profile as instructed:
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 4. 生成 Gradle wrapper / Generate Gradle wrapper
pixi run setup-gradle

# 5. 构建并安装到已连接设备 / Build and install to a connected device
pixi run deploy-release
```

更多命令、架构说明和调试技巧见 `CLAUDE.md`。
See `CLAUDE.md` for more commands, architecture, and debugging tips.

---

## 许可证 License

[MIT License](LICENSE) © 2026 Lue

---

## 免责声明 Disclaimer

**关于非官方 API / Regarding Unofficial APIs**

本应用在获取歌曲元数据（标题、封面等）时，使用了网易云音乐、QQ 音乐、酷狗音乐和哔哩哔哩的**非官方、未公开文档的 API 接口**。这些接口并非由各平台官方授权供第三方使用，可能违反各平台的服务条款（ToS）。

This app uses **unofficial, undocumented API endpoints** from NetEase Cloud Music, QQ Music, Kugou, and Bilibili to fetch song metadata (titles, cover art, etc.). These endpoints are not officially authorized for third-party use and may violate the Terms of Service of the respective platforms.

- 音频内容始终通过各平台官方 App 播放，本项目不播放、不下载、不缓存任何音频数据
- 平台方可能随时更改或封锁其 API，导致相关功能失效
- 网易云音乐、QQ 音乐、酷狗音乐和哔哩哔哩均已提供官方开放平台 API 或 SDK，但仅对企业/商业合作伙伴开放，个人开发者无法申请接入；若上述平台向个人开发者开放官方 API，本项目将优先迁移至官方接口
- 使用本项目所带来的任何法律风险由使用者自行承担，作者不承担任何责任

- Audio content is always played through the official apps; this project does not play, download, or cache any audio data
- Platform providers may change or block their APIs at any time, breaking related features
- NetEase Cloud Music, QQ Music, Kugou, and Bilibili do provide official open platform APIs or SDKs, but access is restricted to enterprise/commercial partners and is not available to independent developers; should these platforms open their official APIs to individual developers, this project will migrate to them accordingly
- Any legal risks arising from using this project are borne solely by the user; the author assumes no liability

**非隶属关系与商标声明 / Non-Affiliation and Trademark Notice**

- Tutti 是独立的非官方项目，与网易云音乐、腾讯/QQ 音乐、酷狗音乐、哔哩哔哩及其关联公司没有隶属、赞助、认可或合作关系
- 文档和界面中出现的第三方平台名称、商标、Logo、服务名称和应用包名仅用于说明兼容性、链接解析和播放委托关系，其权利归各自所有者所有
- 本仓库的 MIT 许可证仅适用于本项目作者拥有版权的源代码和文档，不授予任何第三方平台内容、接口、商标、Logo、音乐作品或用户数据的使用权
- 使用者应自行确认其使用方式符合所在地法律法规、第三方平台服务条款和版权要求

- Tutti is an independent, unofficial project and is not affiliated with, sponsored by, endorsed by, or partnered with NetEase Cloud Music, Tencent/QQ Music, Kugou, Bilibili, or their affiliates
- Third-party platform names, trademarks, logos, service names, and package names appear only to identify compatibility, link parsing, and playback delegation behavior; all rights remain with their respective owners
- The MIT License in this repository applies only to source code and documentation owned by this project author; it does not grant rights to any third-party platform content, APIs, trademarks, logos, musical works, or user data
- Users are responsible for ensuring their own use complies with applicable laws, third-party platform terms, and copyright requirements

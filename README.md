# Tutti 管乐

跨平台音乐播放列表管理器和启动器，支持网易云音乐、QQ音乐和哔哩哔哩。

A cross-platform music playlist manager and launcher for Chinese music platforms (NetEase Cloud Music, QQ Music & Bilibili).

## 这不是一个音乐播放器 This Is NOT a Music Player

Tutti 不播放、不下载、不缓存任何音乐。它是一个**跨平台播放列表管理器**，通过深度链接调起官方App播放。

### 与其他音乐App的区别

| | 平台App<br>(网易云/QQ音乐) | 聚合播放器<br>(Listen 1等) | Tutti |
|---|---|---|---|
| **播放方式** | 自己播放 | 自己播放（爬取API） | 调起官方App播放 |
| **技术实现** | 官方客户端 | 逆向API、爬虫 | 深度链接 + 非官方元数据API |
| **曲库范围** | 仅自家平台 | 多平台（爬取） | 跨平台统一管理 |
| **播放列表** | 仅自家歌曲 | 可混合多平台 | 可混合多平台 |
| **切歌方式** | App内切歌 | App内切歌 | 跨App自动切歌 |
| **会员歌曲** | 需要对应会员 | 通常无法播放 | 使用你已有的会员 |
| **音频数据** | 流媒体/本地播放 | 爬取后播放 | 不接触音频数据 |
| **法律合规** | 合法 | 灰色地带 | 灰色地带（见免责声明） |
| **平台封禁风险** | 无 | 可能被封 | 元数据API可能被封 |
| **维护成本** | 官方维护 | API改动即失效 | 深度链接长期稳定 |

**核心定位：**
- **平台App** = 围墙花园，各自为政
- **聚合播放器** = 盗版播放器，绕过平台限制
- **Tutti** = 跨平台遥控器，组织歌曲让官方App播放

---

## 功能特点 Features

- **统一音乐库** - 将网易云音乐、QQ音乐和哔哩哔哩的歌曲整合到一个库中
- **跨平台播放列表** - 创建包含多个平台歌曲的播放列表
- **一键播放** - 点击歌曲自动打开对应的音乐App播放
- **自动切歌** - 通过监听通知检测歌曲结束，自动播放下一首
- **悬浮窗控制** - 悬浮窗覆盖在其他App上方，提供播放控制
- **分享接收** - 从其他App分享链接直接添加歌曲
- **本地存储** - 所有数据保存在本地，无需联网

---

## 快速开始 Quick Start

### 环境要求 Requirements

- [Pixi](https://pixi.sh/) (提供 JDK 17 和 Gradle)
- Android 8.0+ 手机 (用于测试)

### 安装 Installation

```bash
# 克隆项目
git clone <repository-url>
cd tutti

# 安装 pixi (如果没有)
curl -fsSL https://pixi.sh/install.sh | bash

# 安装依赖
pixi install

# 下载 Android SDK (~3GB，仅首次)
pixi run setup-sdk

# 按脚本提示添加到 shell 配置:
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 生成 Gradle wrapper
pixi run setup-gradle
```

### 构建和部署 Build & Deploy

```bash
# 构建 debug APK
pixi run build

# 构建并安装到手机
pixi run deploy

# 查看应用日志
pixi run logcat-app
```

### 网站部署 Website Deployment

项目主页位于 `site/`，推荐在 Cloudflare Pages 网站中连接 GitHub 仓库并配置构建，不需要在仓库中保存 Cloudflare 部署 Token。

当前 Pages 构建会自动安装 Android SDK、构建 debug APK，并把 APK 放入网页产物的 `downloads/` 目录，供下载按钮使用。构建脚本同时写入 `site/public/downloads/` 和 `site/downloads/`，所以 Cloudflare 发布 `site/dist` 或误发布原始 `site/` 时下载链接都可用。

Cloudflare Pages 构建设置：

| 设置 | 值 |
|---|---|
| Framework preset | Vite |
| Root directory | 留空，使用仓库根目录 |
| Build command | `npm ci && npm run build` |
| Build output directory | `site/dist` |
| Node.js version | `24` |

如果不想在 Pages 中构建 APK，也可以把 Build command 改为 `npm ci && npm run build:site`，并在 Cloudflare Pages 环境变量中配置 `VITE_DOWNLOAD_URL` 为外部 APK 下载地址。

本地预览：

```bash
cd site
npm ci
npm run build
npm run preview
```

---

## 开发命令 Development Commands

| 命令 | 说明 |
|---|---|
| `pixi run build` | 构建 debug APK |
| `pixi run build-release` | 构建 release APK |
| `pixi run deploy` | 构建并部署到手机 |
| `pixi run install` | 安装已构建的 APK |
| `pixi run test` | 运行单元测试 |
| `pixi run logcat` | 查看所有设备日志 |
| `pixi run logcat-app` | 查看应用日志 (已过滤) |
| `pixi run devices` | 列出已连接设备 |
| `pixi run clean` | 清理构建缓存 |

---

## 项目结构 Project Structure

```
tutti/
├── CLAUDE.md                 # AI 助手说明
├── pixi.toml                 # Pixi 环境和任务
├── android-app/              # === 原生 Kotlin Android 应用 ===
│   └── app/src/main/java/com/musichub/
│       ├── MusicHubApplication.kt
│       ├── data/              # Room 数据库层
│       │   ├── model/         # 实体模型
│       │   ├── local/         # DAO + Database
│       │   └── repository/    # Repository
│       ├── platform/          # 平台处理器
│       │   ├── NetEasePlatform.kt
│       │   ├── QQMusicPlatform.kt
│       │   ├── BilibiliPlatform.kt
│       │   └── LinkParser.kt
│       ├── service/           # 服务层
│       │   ├── PlaybackService.kt
│       │   ├── FloatingWindowService.kt
│       │   ├── MediaMonitorService.kt
│       │   ├── DeepLinkLauncher.kt
│       │   ├── PlayerAccessibilityService.kt
│       │   └── ShareReceiver.kt
│       └── ui/                # 界面层
│           ├── MainActivity.kt
│           ├── fragment/
│           ├── adapter/
│           └── viewmodel/
├── src/                       # Python/Kivy 原型 (旧版，不再维护)
└── tests/                     # Python 测试 (旧版)
```

---

## 技术栈 Tech Stack

| 组件 | 技术 |
|---|---|
| 语言 | Kotlin 1.9+ |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 14 (API 34) |
| UI | Material 3 + ViewBinding |
| 导航 | Jetpack Navigation Component |
| 数据库 | Room (SQLite) |
| 网络 | OkHttp |
| 图片加载 | Coil |
| 异步 | Kotlin Coroutines + Flow |
| 架构 | MVVM (ViewModel + Repository) |
| 构建 | Gradle 8.5 + Kotlin DSL |
| 环境管理 | Pixi (提供 JDK 17 + Gradle) |

---

## 支持的平台 Supported Platforms

### 网易云音乐 NetEase Cloud Music
- 包名: `com.netease.cloudmusic`
- 支持链接: `music.163.com/song?id=xxx`
- 深度链接: `orpheus://song/{id}`

### QQ音乐 QQ Music
- 包名: `com.tencent.qqmusic`
- 支持链接: `y.qq.com/n/ryqq/songDetail/xxx`
- 深度链接: `qqmusic://qq.com/ui/openUrl?p=...`

### 哔哩哔哩 Bilibili
- 包名: `tv.danmaku.bili`
- 支持视频链接 (BV/av) 和音频链接 (au)
- 使用 HTTPS 回退链接 (无自定义 URI scheme)

---

## 权限说明 Permissions

应用需要以下特殊权限:

1. **悬浮窗权限** (SYSTEM_ALERT_WINDOW) - 用于悬浮窗播放控制
2. **通知使用权** (BIND_NOTIFICATION_LISTENER_SERVICE) - 用于监听其他App的播放状态
3. **无障碍服务** (BIND_ACCESSIBILITY_SERVICE) - 用于自动打开QQ音乐播放器页面
4. **通知权限** (POST_NOTIFICATIONS) - 用于前台服务通知

---

## 已知限制 Known Limitations

### 无后台切歌
切歌时会将目标音乐App切换到前台。Android 的 `startActivity()` (深度链接所必需) 总是会将目标应用切到前台，目前没有可靠的方法在后台完成切歌。

### 网易云音乐横屏方向检测问题
当手机处于横屏状态时启动网易云音乐，网易云可能无法自动检测横屏方向而保持竖屏显示。这是网易云音乐的bug（其Activity在启动时不检查当前设备方向，仅响应物理旋转传感器事件）。**临时解决方法**：网易云打开后，手动旋转手机到竖屏再转回横屏即可触发方向检测。

---

## 使用方法 Usage

### 添加歌曲

1. **手动添加**: 在"添加歌曲"页面粘贴歌曲链接
2. **分享添加**: 从网易云/QQ音乐/哔哩哔哩App分享歌曲到 Tutti

### 播放歌曲

点击歌曲卡片，自动打开对应的音乐App并播放。

### 管理播放列表

- 创建自定义播放列表
- 添加来自不同平台的歌曲
- 支持顺序播放、列表循环、单曲循环和随机播放

---

## 常见问题 Troubleshooting

| 问题 | 解决方案 |
|---|---|
| `ANDROID_HOME` 未设置 | 运行 `pixi run setup-sdk` 并添加环境变量 |
| Gradle 同步失败 | 检查 JDK 17: `java -version` |
| APK 安装失败 | 启用 USB 调试，运行 `adb devices` 确认连接 |
| 悬浮窗不显示 | 设置中授予"显示在其他应用上方"权限 |
| 无法检测播放状态 | 设置中授予"通知使用权" |
| 构建失败提示 SDK 未找到 | 设置 `ANDROID_HOME` 环境变量 |

---

## 免责声明 Disclaimer

**关于非官方 API / Regarding Unofficial APIs**

本应用在获取歌曲元数据（标题、封面等）时，使用了网易云音乐、QQ 音乐和哔哩哔哩的**非官方、未公开文档的 API 接口**。这些接口并非由各平台官方授权供第三方使用，可能违反各平台的服务条款（ToS）。

This app uses **unofficial, undocumented API endpoints** from NetEase Cloud Music, QQ Music, and Bilibili to fetch song metadata (titles, cover art, etc.). These endpoints are not officially authorized for third-party use and may violate the Terms of Service of the respective platforms.

- 音频内容始终通过各平台官方 App 播放，本项目不播放、不下载、不缓存任何音频数据
- 平台方可能随时更改或封锁其 API，导致相关功能失效
- 网易云音乐、QQ 音乐和哔哩哔哩均已提供官方开放平台 API 或 SDK，但仅对企业/商业合作伙伴开放，个人开发者无法申请接入；若上述平台向个人开发者开放官方 API，本项目将优先迁移至官方接口
- 使用本项目所带来的任何法律风险由使用者自行承担，作者不承担任何责任

- Audio content is always played through the official apps; this project does not play, download, or cache any audio data
- Platform providers may change or block their APIs at any time, breaking related features
- NetEase Cloud Music, QQ Music, and Bilibili do provide official open platform APIs or SDKs, but access is restricted to enterprise/commercial partners and is not available to independent developers; should these platforms open their official APIs to individual developers, this project will migrate to them accordingly
- Any legal risks arising from using this project are borne solely by the user; the author assumes no liability

**非隶属关系与商标声明 / Non-Affiliation and Trademark Notice**

- Tutti 是独立的非官方项目，与网易云音乐、腾讯/QQ 音乐、哔哩哔哩及其关联公司没有隶属、赞助、认可或合作关系
- 文档和界面中出现的第三方平台名称、商标、Logo、服务名称和应用包名仅用于说明兼容性、链接解析和播放委托关系，其权利归各自所有者所有
- 本仓库的 MIT 许可证仅适用于本项目作者拥有版权的源代码和文档，不授予任何第三方平台内容、接口、商标、Logo、音乐作品或用户数据的使用权
- 使用者应自行确认其使用方式符合所在地法律法规、第三方平台服务条款和版权要求

- Tutti is an independent, unofficial project and is not affiliated with, sponsored by, endorsed by, or partnered with NetEase Cloud Music, Tencent/QQ Music, Bilibili, or their affiliates
- Third-party platform names, trademarks, logos, service names, and package names appear only to identify compatibility, link parsing, and playback delegation behavior; all rights remain with their respective owners
- The MIT License in this repository applies only to source code and documentation owned by this project author; it does not grant rights to any third-party platform content, APIs, trademarks, logos, musical works, or user data
- Users are responsible for ensuring their own use complies with applicable laws, third-party platform terms, and copyright requirements

---

## 许可证 License

[MIT License](LICENSE) © 2026 Lue

---

## 贡献 Contributing

欢迎提交 Issue 和 Pull Request！

1. Fork 项目
2. 创建功能分支
3. 提交更改
4. 发起 Pull Request

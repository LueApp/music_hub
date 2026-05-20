const FALLBACK_VERSION = 'preview';
const FALLBACK_DOWNLOAD_URL = './downloads/music-hub.apk';
const METADATA_URL = './downloads/metadata.json';

const translations = {
  zh: {
    navFeatures: '功能',
    navWorkflow: '工作方式',
    navContribute: '参与改进',
    navDownload: '下载',
    languageButtonSr: '切换到英文',
    eyebrow: '跨平台播放队列管理器',
    heroTitle: '管乐',
    heroCopy: '把网易云音乐、QQ音乐、酷狗音乐和哔哩哔哩的歌曲放进同一个本地曲库和播放队列。管乐 负责调度和切歌，真正播放仍交给各平台官方 App。',
    downloadCta: '下载 Android 预览版',
    learnCta: '查看工作方式',
    mockTitle: '播放队列',
    mockSong: '统一队列',
    mockMeta: '播放委托给官方 App',
    nameEyebrow: '关于名字',
    nameTitle: '管乐 · Tutti',
    nameCopy1: '"管乐" 是中文里对吹奏乐器（长笛、单簧管、小号等）的统称——它们音色各异，却能在同一份曲谱下合奏。"Tutti" 是意大利语乐谱中的术语，意为"全员齐奏"，指挥示意所有乐手一起演奏的那一瞬间。',
    nameCopy2: '把网易云、QQ 音乐、酷狗、哔哩哔哩看作不同的乐器，按同一份"曲谱"——你的播放队列——依次合奏，就是这个应用想做的事。我们不替换任何乐器，只递交曲谱、切换节拍。',
    nameAside: '另：管字本身也带有"管理"之意，是工程层面的一个小小双关。',
    positionEyebrow: '核心定位',
    positionTitle: '不是聚合播放器，而是播放委托系统。',
    positionCopy: '管乐 不播放、不下载、不缓存任何音频流。它只保存歌曲元数据和队列顺序，通过深度链接、系统媒体会话和平台入口把播放行为委托给官方 App。',
    cardPlatformTitle: '平台 App',
    cardPlatformCopy: '歌单、推荐和播放队列被限制在单个平台内，跨平台听歌需要频繁切换。',
    cardAggregatorTitle: '聚合播放器',
    cardAggregatorCopy: '通常依赖逆向接口或抓取音频流，自行解码播放，维护成本高，也容易触碰平台限制。',
    cardHubTitle: '管乐',
    cardHubCopy: '统一管理多平台歌曲，播放时调起官方 App，并在当前歌曲快结束时自动安排下一首。',
    workflowEyebrow: '工作方式',
    workflowTitle: '从分享链接到跨平台连续播放。',
    workflowCopy: '我们把复杂流程拆成四件事：识别歌曲、保存队列、委托播放、监听状态。用户看到的是一份统一歌单，底层仍按各平台自己的播放规则运作。',
    workflowAddTitle: '歌曲入库',
    workflowAddCopy: '从音乐 App 分享链接或直接粘贴，LinkParser 会解析短链接、平台 ID、标题和艺术家，再由对应平台的处理器补齐元数据。',
    workflowQueueTitle: '统一队列',
    workflowQueueCopy: '歌曲以“平台 + 平台歌曲 ID”为唯一标识写入本地 Room 数据库，可以混排进同一个播放列表。',
    workflowDelegateTitle: '播放委托',
    workflowDelegateCopy: '播放时生成网易云、QQ 音乐、酷狗或 B 站的目标链接，唤起对应官方 App 完成实际播放，保留会员权益。',
    workflowMonitorTitle: '状态监听',
    workflowMonitorCopy: '通过 Android MediaSession 与通知访问权限读取播放状态和进度，在接近结尾时自动切到队列里的下一首。',
    featuresEyebrow: '功能特点',
    featuresTitle: '为日常听歌场景准备的实用能力。',
    featureLibraryTitle: '跨平台曲库',
    featureLibraryCopy: '把网易云、QQ 音乐、酷狗音乐和 B 站的内容当作统一的元数据来管理，不会把音频文件搬进应用本身。',
    featurePlaylistTitle: '歌单订阅与合并',
    featurePlaylistCopy: '把远程歌单关联到本地歌单，按同步源增量更新，同时保留手动添加的歌曲。',
    featureAdvanceTitle: '智能自动切歌',
    featureAdvanceCopy: '播放前先检测可用性，失败时自动跳过；切歌时也会处理上一首平台没停掉、新一首启动较慢等情况。',
    featureFloatingTitle: '跨 App 悬浮控制',
    featureFloatingCopy: '在任意 App 上方查看当前歌曲、控制播放、拖动进度，可以在完整窗口和迷你浮球之间随意切换。',
    featureShareTitle: '发现与推荐聚合',
    featureShareCopy: '汇总排行榜、分类歌单和已登录平台的每日推荐，支持一键加入跨平台队列。',
    featureRemoteTitle: '双设备远程控制',
    featureRemoteCopy: '播放端在本机开放 HTTP/WebSocket 控制接口，控制端实时同步状态并发送操作指令。',
    featureBackgroundTitle: '后台播放模式',
    featureBackgroundCopy: '借助 Shizuku 把音乐 App 启动到 freeform 窗口里，并放到屏幕之外，让 管乐 的浮球持续显示在前台。',
    featureTimeoutTitle: '单曲提前结束',
    featureTimeoutCopy: '长按歌曲为它设置自定义的 mm:ss 时长，跳过冗长的开场或片尾，到点自动切到下一首。',
    featureBackupTitle: '数据导入与导出',
    featureBackupCopy: '把歌曲、歌单和同步源导出为 JSON 文件，方便跨设备迁移，备份之后也可以一键还原。',
    featureSetupTitle: '首次启动权限引导',
    featureSetupCopy: '首次启动时通过引导页面，带你依次完成悬浮窗、通知访问、无障碍等 6 项权限的授权。',
    featureLocalTitle: '本地优先存储',
    featureLocalCopy: '歌单数据保存在设备本地数据库里，不需要账号服务器，也不会把播放记录上传到任何地方。',
    featureShareIntakeTitle: '分享链接入库',
    featureShareIntakeCopy: '在音乐 App 的分享面板里把链接发送到 管乐，自动识别平台、歌曲 ID 和元数据。',
    platformNetease: '网易云音乐',
    platformQq: 'QQ音乐',
    platformKugou: '酷狗音乐',
    platformBili: '哔哩哔哩',
    detailsEyebrow: '安装前说明',
    detailsTitle: '把权限和限制讲清楚。',
    detailsCopy: '管乐 把播放委托给官方 App，这种架构本身就有边界。下面把需要的权限，以及目前还没有完美解决方案的问题都列了出来，希望你在试用之前心里有数。',
    permissionsTitle: '需要的权限',
    permissionsAutoGrantNote: '如果你已经在设备上启用了 Shizuku 并允许 Tutti 使用，那么以下权限会在每次启动时由 Tutti 通过 Shizuku 自动批量授予，不需要手动一个一个点开系统设置。完成时会弹一条可滑动关闭的通知，列出本次新增的授权项目。可以在"设置 → 启动模式 → 通过 Shizuku 自动授予所需权限"里关掉。',
    permTagRequired: '必需',
    permTagRecommended: '推荐',
    permTagOptional: '可选',
    permissionOverlay: '悬浮窗权限：在其他 App 上方显示播放控制，并在切歌时直接拉起音乐 App，绕开"是否打开外部应用"弹窗。',
    permissionPost: '通知权限：用于显示前台服务通知（Android 13 及以上），系统据此判断后台服务是否合法运行。',
    permissionNotification: '通知使用权：读取系统媒体会话状态，监听进度与歌曲结束事件，实现自动切到下一首。不授权则无法自动切歌。',
    permissionAccessibility: '无障碍服务（QQ 音乐）：QQ 音乐的深度链接只会跳到首页或迷你卡片，不能直接打开播放页；这个服务在 QQ 音乐前台时模拟点击迷你播放器，把页面切到完整播放/歌词页。网易云、酷狗、B 站的深度链接已经能直达播放页，所以不需要它。',
    permissionWriteSettings: '系统设置写入：网易云的横屏播放页（PlayerLandscapeActivity）是一个独立的 Activity，只在 PlayerActivity 检测到方向变化时才会启动；所以前台模式下播放网易云且设备已经处于横屏时，需要先把系统旋转锁成竖屏，等 PlayerActivity 启动后再恢复原设置，凑出一次"竖→横"转变（约 7–10 秒）。其他平台的播放页通过标准的方向变更回调在同一个 Activity 里切换横竖屏布局，不需要这个绕路。',
    permissionUsage: '使用情况访问：浮球双击返回上一个 App 时需要查询最近任务列表。',
    permissionFreeformResize: '无障碍服务（后台启动）：后台模式下，HyperOS 的手势导航偶尔会把音乐 App 的 freeform 窗口"吸"回主屏；这个服务侦测窗口位置变化，被拉回后立即再次推到屏幕外。注意：它不能消除切歌瞬间 freeform 窗口创建到 resize 之间的短暂闪现——那是 Android 先按默认尺寸创建任务、然后才能 resize 的固有时序，目前没有完美解决办法。',
    permissionShizuku: 'Shizuku：启用后台启动模式需要它来调用 am start --windowingMode 5。不安装则只能使用前台模式（切歌时官方 App 会跳到前台）。',
    limitsTitle: '已知限制',
    limitForeground: '前台模式下切歌时官方 App 会跳到前台；后台模式可以避开这一点，但需要 Shizuku。',
    limitMediaSession: 'MediaSession 的 playFromMediaId / playFromUri / playFromSearch 在网易云和 QQ 音乐里都不会触发实际播放，只能依赖深度链接启动。',
    limitNeteaseLandscape: '前台模式下播放网易云时，如果设备已经处于横屏，需要先把系统旋转方向锁成竖屏再恢复，约 7–10 秒——网易云的横屏播放页是独立的 PlayerLandscapeActivity，只在 PlayerActivity 检测到方向变化时才会启动，所以必须制造一次"竖→横"转变。后台模式下 freeform 窗口规避了这个问题。',
    limitQQPlayer: 'QQ 音乐没有提供能直接打开播放页的深度链接。前台模式下我们用无障碍服务模拟点击迷你播放器条进入完整播放页，从启动到切入大约 1–8 秒，偶尔会因为页面层级变化而失败、回退到 QQ 音乐首页。后台模式下没有可见的页面切换，这个延迟和失败都看不到。',
    limitKugou: '四家平台都没有对外开放过官方文档化的歌单 API——我们用的都是各家 Web/App 自己在调的内部接口。差别在于：网易云、QQ 音乐、B 站的这类接口不要求登录就能拿到公开歌单的数据；只有酷狗会在未登录时统一返回 4xx。所以酷狗歌单通过解析分享页里服务端渲染的歌曲数据来读取，个别字段缺失的歌曲可能无法导入。',
    limitPlatform: '会员歌曲仍然需要你拥有对应平台的会员，管乐 不会绕过任何鉴权。',
    limitPreview: '当前发布的是预览版，可能在边角场景下崩溃，欢迎在 GitHub 上提 Issue。',
    contributeEyebrow: '寻求帮助',
    contributeTitle: '我们希望和更多人一起把这些限制慢慢解掉。',
    contributeCopy: '管乐 完全开源，目前由一位非 Android 出身的开发者在维护。下面列出的每一项都不是我们故意这么做，而是暂时还没找到解决方案的实际问题。如果你在其中任何一项上有经验，欢迎在 GitHub 上提 Issue、发 PR，或者只是分享一些思路。',
    contributeTagAndroid: 'Android 系统层',
    contributeAndroidTitle: '无 Shizuku 的后台播放',
    contributeAndroidCopy: 'setLaunchWindowingMode 只对持有签名级权限的 App 生效，目前我们只能借助 Shizuku 调用 am start --windowingMode 5。如果你知道有什么办法能让一个普通 App 把音乐 App 放到后台或画中画窗口里，请告诉我们。',
    contributeTagPlatform: '平台 API',
    contributePlatformTitle: '酷狗 / QQ / 网易官方接口',
    contributePlatformCopy: '酷狗的 songlist 接口在未登录状态下全部返回 4xx，我们目前只能抓取服务端渲染的 HTML。如果你熟悉这几家平台的公开 API、登录签名或合作伙伴接入方式，可以帮我们摆脱抓取页面的做法。',
    contributeTagMediaSession: 'MediaSession',
    contributeMediaSessionTitle: '让官方 App 真正接受播放指令',
    contributeMediaSessionCopy: '所有 playFromMediaId / playFromUri / playFromSearch 调用都会被网易云和 QQ 音乐忽略；actions 字段虽然声明了对应能力，实际却是空操作。我们想知道有没有 MediaBrowserService 或厂商私有指令可以真正触发切歌。',
    contributeTagNewPlatform: '新平台',
    contributeNewPlatformTitle: '新增平台处理器',
    contributeNewPlatformCopy: '我们希望把酷我、咪咕、Apple Music 中国区等平台也接入进来。只要实现 PlatformHandler 接口（链接解析、元数据获取、深度链接生成），就能加入跨平台队列。欢迎提 PR。',
    contributeTagOEM: '机型适配',
    contributeOEMTitle: 'HyperOS / MIUI / EMUI 适配',
    contributeOEMCopy: 'HyperOS 会在重装应用后悄悄解绑 NotificationListenerService，手势导航也会把 freeform 窗口吸附回主屏。如果你在自己的设备上发现 管乐 表现异常，欢迎附上 logcat 和复现步骤。',
    contributeTagDocs: '文档与翻译',
    contributeDocsTitle: '使用文档与多语言',
    contributeDocsCopy: '目前文档主要支持中文和英文。如果你愿意帮忙翻译界面、撰写权限设置教程，或者把疑难解答整理成文章，欢迎直接向 site/ 或 README.md 提 PR。',
    contributeCta: '在 GitHub 上参与',
    contributeIssue: '提交 Issue',
    downloadEyebrow: 'Android 下载',
    downloadTitle: '获取 管乐 预览版',
    downloadCopy: '下载 APK 后在 Android 8.0 及以上设备安装。首次使用需要按提示授予悬浮窗、通知访问和无障碍相关权限。',
    downloadButton: '下载 APK',
    downloadNote: 'APK 会随 Cloudflare Pages 构建一起生成；也可用 VITE_DOWNLOAD_URL 指向外部下载地址。',
    footerText: '基于播放委托与媒体会话监听的跨平台音乐队列管理器。'
  },
  en: {
    navFeatures: 'Features',
    navWorkflow: 'How it works',
    navContribute: 'Help wanted',
    navDownload: 'Download',
    languageButtonSr: 'Switch to Chinese',
    eyebrow: 'Cross-platform queue manager',
    heroTitle: 'Tutti',
    heroCopy: 'Put NetEase Cloud Music, QQ Music, Kugou, and Bilibili songs into one local library and queue. Tutti manages scheduling and handoff while official apps keep doing the actual playback.',
    downloadCta: 'Download Android Preview',
    learnCta: 'See the workflow',
    mockTitle: 'Queue',
    mockSong: 'Unified queue',
    mockMeta: 'Delegated to official apps',
    nameEyebrow: 'About the name',
    nameTitle: '管乐 · Tutti',
    nameCopy1: 'In Chinese, "管乐" (guǎn yuè) is the collective term for wind instruments — flutes, clarinets, trumpets, trombones — each with its own voice but able to perform a single piece together under one score. "Tutti" is the Italian score-marking meaning "everyone plays," the moment a conductor cues every musician to sound at once.',
    nameCopy2: 'Together the pair describes what the app does: it treats NetEase Cloud Music, QQ Music, Kugou, and Bilibili as different instruments performing from the same score — your queue. We don’t replace any instrument; we just hand out the score and cue the changes.',
    nameAside: 'A small bonus: the character 管 also carries the meaning of "manage" — a quiet nod to the engineering work behind the scenes.',
    positionEyebrow: 'Positioning',
    positionTitle: 'Not an aggregator player. A playback delegation system.',
    positionCopy: 'Tutti does not play, download, or cache audio streams. It stores metadata and queue order, then delegates playback to official apps through deep links, system media sessions, and platform entry points.',
    cardPlatformTitle: 'Platform Apps',
    cardPlatformCopy: 'Playlists, recommendations, and queues stay inside one platform, so cross-platform listening means constant app switching.',
    cardAggregatorTitle: 'Aggregator Players',
    cardAggregatorCopy: 'They often depend on reverse-engineered APIs or scraped audio streams, then decode playback themselves. That is fragile and risky.',
    cardHubTitle: 'Tutti',
    cardHubCopy: 'Manage songs from multiple platforms, launch official apps for playback, and take over next-song scheduling before playback ends.',
    workflowEyebrow: 'Workflow',
    workflowTitle: 'From shared link to continuous cross-platform playback.',
    workflowCopy: 'The system reduces the hard parts to four jobs: identify the song, save the queue, delegate playback, and monitor state. Users see one playlist while each platform still handles its own playback boundary.',
    workflowAddTitle: 'Import songs',
    workflowAddCopy: 'Share from a music app or paste a link. LinkParser resolves short links, platform IDs, titles, artists, and platform-specific metadata.',
    workflowQueueTitle: 'Build one queue',
    workflowQueueCopy: 'Songs are deduplicated by platform and platform song ID in the local Room database, then mixed into one playlist.',
    workflowDelegateTitle: 'Delegate playback',
    workflowDelegateCopy: 'When playback starts, Tutti generates a NetEase, QQ Music, Kugou, or Bilibili target link and opens the official app.',
    workflowMonitorTitle: 'Monitor state',
    workflowMonitorCopy: 'Android MediaSession and notification access provide playback state and progress so Tutti can advance near the end.',
    featuresEyebrow: 'Features',
    featuresTitle: 'Capabilities for real listening workflows.',
    featureLibraryTitle: 'Cross-platform library',
    featureLibraryCopy: 'Manage NetEase, QQ Music, Kugou, and Bilibili content as unified metadata without moving audio into the app.',
    featurePlaylistTitle: 'Playlist subscription and merge',
    featurePlaylistCopy: 'Attach remote playlists to a local playlist, sync source changes, and preserve manually added songs.',
    featureAdvanceTitle: 'Smart auto advance',
    featureAdvanceCopy: 'Check availability before playback, skip failures, and handle old-platform playback or delayed starts.',
    featureFloatingTitle: 'Cross-app floating control',
    featureFloatingCopy: 'See the current song, control playback, scrub progress, and switch between full and mini overlay modes.',
    featureShareTitle: 'Discovery and recommendations',
    featureShareCopy: 'Aggregate charts, category playlists, and signed-in daily recommendations into one cross-platform queue.',
    featureRemoteTitle: 'Two-device remote control',
    featureRemoteCopy: 'A player device exposes a local HTTP/WebSocket control surface; a controller device mirrors state and sends actions over LAN.',
    featureBackgroundTitle: 'Background playback mode',
    featureBackgroundCopy: 'Shizuku freeform pins the music app to an off-screen window so the Tutti floating ball stays in the foreground while audio keeps playing.',
    featureTimeoutTitle: 'Per-song early end',
    featureTimeoutCopy: 'Long-press a song to set a custom mm:ss timeout so long intros or outros do not delay the next track in the queue.',
    featureBackupTitle: 'Backup and restore',
    featureBackupCopy: 'Export songs, playlists, and sync sources as a JSON file. Import on another device or restore after reinstall without losing custom data.',
    featureSetupTitle: 'First-run setup flow',
    featureSetupCopy: 'A guided setup screen walks first-time users through all six required permissions before they touch the main UI.',
    featureLocalTitle: 'Local-first storage',
    featureLocalCopy: 'Playlists live in the on-device database. No account server is required and no playback telemetry leaves the phone.',
    featureShareIntakeTitle: 'Share-link intake',
    featureShareIntakeCopy: 'Send a link from any music app share sheet and Tutti identifies the platform, song ID, and metadata automatically.',
    platformNetease: 'NetEase Cloud Music',
    platformQq: 'QQ Music',
    platformKugou: 'Kugou Music',
    platformBili: 'Bilibili',
    detailsEyebrow: 'Before installing',
    detailsTitle: 'Clear permissions and clear limits.',
    detailsCopy: 'Tutti delegates playback to the official apps, and that architecture comes with real edges. Here is the full permission list and every problem we have not yet solved cleanly, so you know what to expect before you try it.',
    permissionsTitle: 'Required Permissions',
    permissionsAutoGrantNote: 'If you have Shizuku installed and granted to Tutti, every permission below is auto-granted in a single batch at app startup — no need to walk through individual system Settings pages. You\'ll get a dismissable notification listing exactly what was newly granted each time. Toggle off under "Settings → Launch mode → Auto-grant permissions via Shizuku".',
    permTagRequired: 'Required',
    permTagRecommended: 'Recommended',
    permTagOptional: 'Optional',
    permissionOverlay: 'Overlay: show playback controls above other apps, and launch the music app on song change without the "open external app?" prompt.',
    permissionPost: 'Post notifications: show the foreground service notification (Android 13+); the system uses this to verify the background service is legitimate.',
    permissionNotification: 'Notification access: read system media-session state to monitor progress and end-of-song events for auto-advance. Without it, automatic next-song does not work.',
    permissionAccessibility: 'Accessibility service (QQ Music): QQ Music\'s deep link only opens the home screen or a mini card — it cannot land directly on the full player. This service taps the mini-player bar once QQ Music is in the foreground so the page switches to the full player/lyrics view. NetEase, Kugou, and Bilibili deep links open their players directly, so they don\'t need it.',
    permissionWriteSettings: 'Write settings: NetEase\'s landscape player (PlayerLandscapeActivity) is a separate Activity that only launches when its PlayerActivity catches an orientation change. So in foreground mode, if NetEase is opened while the device is already in landscape, we lock system rotation to portrait, wait for PlayerActivity to start, then restore the original setting — manufacturing a portrait→landscape transition (about 7–10 seconds). Other platforms handle rotation in-place inside the same Activity via standard configuration callbacks, so no toggle is needed.',
    permissionUsage: 'Usage access: list recent tasks so the floating ball can return you to the previous app on double-tap.',
    permissionFreeformResize: 'Accessibility service (background mode): on HyperOS, gesture navigation occasionally snaps the music app\'s freeform window back to a visible position. This service watches window-position changes on the four music apps and re-fires the off-screen resize whenever the window is pulled back. Note: it does not eliminate the brief window flash at song change — that\'s a fixed timing gap between `am start --windowingMode 5` creating the task at default bounds and `am task resize` shrinking it, and we have no perfect fix for it yet.',
    permissionShizuku: 'Shizuku: required for background-launch mode so we can invoke am start --windowingMode 5. Without it only foreground mode is available (the official app comes to the front on song change).',
    limitsTitle: 'Known Limits',
    limitForeground: 'Foreground mode brings the music app to the front when songs change. Background mode avoids this but requires Shizuku.',
    limitMediaSession: 'MediaSession playFromMediaId / playFromUri / playFromSearch are silently ignored by NetEase and QQ Music; only deep-link launches actually switch songs.',
    limitNeteaseLandscape: 'In foreground mode, if the device is already in landscape when NetEase is opened, we have to lock system rotation to portrait and then restore it — about a 7–10s wait. NetEase\'s landscape player is a separate PlayerLandscapeActivity that only launches when its PlayerActivity catches a portrait→landscape transition, so we have to manufacture that transition. Background-mode freeform windows sidestep the problem.',
    limitQQPlayer: 'QQ Music has no deep link that lands directly on the player page. In foreground mode, an accessibility service taps the mini-player bar for you; the switch takes 1–8 seconds and occasionally falls back to the QQ Music home screen if the UI tree changes mid-tap. Background mode has no visible page transition, so this delay and failure mode are invisible.',
    limitKugou: 'None of the four platforms publish an officially documented playlist API — we use the internal endpoints each platform\'s own web/app client calls. The difference: NetEase, QQ Music, and Bilibili\'s endpoints return public-playlist data without requiring login; only Kugou returns 4xx to unauthenticated requests across the board. So for Kugou we parse server-side-rendered song data from the share page; a small number of songs with missing fields may fail to import.',
    limitPlatform: 'Membership-only songs still need the matching platform subscription. Tutti does not bypass any authentication.',
    limitPreview: 'The current build is a preview that may crash on edge cases. Please file issues on GitHub.',
    contributeEyebrow: 'Help wanted',
    contributeTitle: 'We want help turning every one of these limits into history.',
    contributeCopy: 'Tutti is open source and maintained by a developer who is not an Android specialist. Every limit listed below is a real unsolved problem, not a design choice. If you have experience in any area, we welcome issues, PRs, or just a tip.',
    contributeTagAndroid: 'Android internals',
    contributeAndroidTitle: 'Background playback without Shizuku',
    contributeAndroidCopy: 'setLaunchWindowingMode only honors signature-level permissions, so we currently lean on Shizuku and am start --windowingMode 5. If you know any path for a regular app to push a music app into a hidden window or PiP, please share.',
    contributeTagPlatform: 'Platform APIs',
    contributePlatformTitle: 'Kugou / QQ / NetEase official APIs',
    contributePlatformCopy: 'Kugou playlist endpoints all return 4xx for unauthenticated requests, so we scrape SSR HTML. If you know the official public APIs, login signing, or any partner integration paths, you can help us leave the scraping path behind.',
    contributeTagMediaSession: 'MediaSession',
    contributeMediaSessionTitle: 'Make official apps accept media commands',
    contributeMediaSessionCopy: 'Every playFromMediaId / playFromUri / playFromSearch call is ignored by NetEase and QQ Music. The actions bitmask advertises support but the methods are no-ops. We would love to find a MediaBrowserService or vendor-private command that actually switches tracks.',
    contributeTagNewPlatform: 'New platforms',
    contributeNewPlatformTitle: 'New platform handlers',
    contributeNewPlatformCopy: 'We would like to add Kuwo, Migu, Apple Music (China), and more. Implementing the PlatformHandler interface — link parsing, metadata fetching, deep-link generation — is enough to join the cross-platform queue. PRs welcome.',
    contributeTagOEM: 'OEM quirks',
    contributeOEMTitle: 'HyperOS / MIUI / EMUI quirks',
    contributeOEMCopy: 'HyperOS silently unbinds NotificationListenerService after reinstalls and the gesture navigation snaps freeform windows back to the home screen. If Tutti behaves oddly on your phone, please attach logcat and reproduction steps.',
    contributeTagDocs: 'Docs and translation',
    contributeDocsTitle: 'Documentation and translations',
    contributeDocsCopy: 'Documentation is currently bilingual Chinese and English. If you want to translate the UI, write up permission setup guides, or organize troubleshooting articles, PRs to site/ and README.md are appreciated.',
    contributeCta: 'Join on GitHub',
    contributeIssue: 'Open an issue',
    downloadEyebrow: 'Android Download',
    downloadTitle: 'Get the Tutti preview',
    downloadCopy: 'Download the APK and install it on Android 8.0 or later. First use requires overlay, notification access, and accessibility permissions.',
    downloadButton: 'Download APK',
    downloadNote: 'The APK is generated during the Cloudflare Pages build; VITE_DOWNLOAD_URL can point to an external download instead.',
    footerText: 'Cross-platform music queue manager based on playback delegation and media-session monitoring.'
  }
};

const toggle = document.querySelector('[data-language-toggle]');
const downloadLink = document.querySelector('[data-download-link]');
const versionLabel = document.querySelector('[data-version-label]');

function applyLanguage(language) {
  const dictionary = translations[language] ?? translations.zh;
  document.documentElement.lang = language === 'zh' ? 'zh-CN' : 'en';
  document.body.dataset.lang = language;

  document.querySelectorAll('[data-i18n]').forEach((node) => {
    const key = node.dataset.i18n;
    if (dictionary[key]) {
      node.textContent = dictionary[key];
    }
  });

  if (toggle) {
    toggle.querySelector('span[aria-hidden="true"]').textContent = language === 'zh' ? 'EN' : '中';
    toggle.setAttribute('aria-label', dictionary.languageButtonSr);
  }

  localStorage.setItem('music-hub-language', language);
}

async function applyApkMetadata() {
  let meta = null;
  try {
    const response = await fetch(METADATA_URL, { cache: 'no-cache' });
    if (response.ok) meta = await response.json();
  } catch {
    // Falls through to placeholder values below.
  }
  const override = import.meta.env?.VITE_DOWNLOAD_URL;
  const url = override
    || (meta?.filename ? `./downloads/${meta.filename}` : FALLBACK_DOWNLOAD_URL);
  downloadLink?.setAttribute('href', url);
  if (versionLabel) versionLabel.textContent = meta?.version ?? FALLBACK_VERSION;
}

applyApkMetadata();

const initialLanguage = localStorage.getItem('music-hub-language') === 'en' ? 'en' : 'zh';
applyLanguage(initialLanguage);

toggle?.addEventListener('click', () => {
  const nextLanguage = document.body.dataset.lang === 'zh' ? 'en' : 'zh';
  applyLanguage(nextLanguage);
});

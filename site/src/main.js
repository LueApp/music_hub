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
    heroTitle: '音乐中心',
    heroCopy: '把网易云音乐、QQ音乐、酷狗音乐和哔哩哔哩的歌曲放进同一个本地曲库和播放队列。Music Hub 负责管理、调度和切歌，真正播放仍交给各平台官方 App。',
    downloadCta: '下载 Android 预览版',
    learnCta: '查看工作方式',
    mockTitle: '播放队列',
    mockSong: '统一队列',
    mockMeta: '播放委托给官方 App',
    positionEyebrow: '核心定位',
    positionTitle: '不是聚合播放器，而是播放委托系统。',
    positionCopy: 'Music Hub 不播放、不下载、不缓存任何音频流。它只保存歌曲元数据和队列顺序，通过深度链接、系统媒体会话和平台入口把播放行为委托给官方 App。',
    cardPlatformTitle: '平台 App',
    cardPlatformCopy: '歌单、推荐和播放队列被限制在单个平台内，跨平台听歌需要频繁切换。',
    cardAggregatorTitle: '聚合播放器',
    cardAggregatorCopy: '通常依赖逆向接口或抓取音频流，自行解码播放，维护成本高，也容易触碰平台限制。',
    cardHubTitle: 'Music Hub',
    cardHubCopy: '统一管理多平台歌曲，播放时调起官方 App，并在当前歌曲快结束时自动安排下一首。',
    workflowEyebrow: '工作方式',
    workflowTitle: '从分享链接到跨平台连续播放。',
    workflowCopy: '我们把复杂流程拆成四件事：识别歌曲、保存队列、委托播放、监听状态。用户看到的是一份统一歌单，底层仍按各平台自己的播放规则运作。',
    workflowAddTitle: '1. 歌曲入库',
    workflowAddCopy: '从音乐 App 分享链接或直接粘贴，LinkParser 会解析短链接、平台 ID、标题和艺术家，再由对应平台的处理器补齐元数据。',
    workflowQueueTitle: '2. 统一队列',
    workflowQueueCopy: '歌曲以“平台 + 平台歌曲 ID”为唯一标识写入本地 Room 数据库，可以混排进同一个播放列表。',
    workflowDelegateTitle: '3. 播放委托',
    workflowDelegateCopy: '播放时生成网易云、QQ 音乐、酷狗或 B 站的目标链接，唤起对应官方 App 完成实际播放，保留会员权益。',
    workflowMonitorTitle: '4. 状态监听',
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
    featureBackgroundCopy: '借助 Shizuku 把音乐 App 启动到 freeform 窗口里，并放到屏幕之外，让 Music Hub 的浮球持续显示在前台。',
    featureTimeoutTitle: '单曲提前结束',
    featureTimeoutCopy: '长按歌曲为它设置自定义的 mm:ss 时长，跳过冗长的开场或片尾，到点自动切到下一首。',
    featureBackupTitle: '数据导入与导出',
    featureBackupCopy: '把歌曲、歌单和同步源导出为 JSON 文件，方便跨设备迁移，备份之后也可以一键还原。',
    featureSetupTitle: '首次启动权限引导',
    featureSetupCopy: '首次启动时通过引导页面，带你依次完成悬浮窗、通知访问、无障碍等 6 项权限的授权。',
    featureLocalTitle: '本地优先存储',
    featureLocalCopy: '歌单数据保存在设备本地数据库里，不需要账号服务器，也不会把播放记录上传到任何地方。',
    featureShareIntakeTitle: '分享链接入库',
    featureShareIntakeCopy: '在音乐 App 的分享面板里把链接发送到 Music Hub，自动识别平台、歌曲 ID 和元数据。',
    platformNetease: '网易云音乐',
    platformQq: 'QQ音乐',
    platformKugou: '酷狗音乐',
    platformBili: '哔哩哔哩',
    detailsEyebrow: '安装前说明',
    detailsTitle: '把权限和限制讲清楚。',
    detailsCopy: 'Music Hub 把播放委托给官方 App，这种架构本身就有边界。下面把需要的权限，以及目前还没有完美解决方案的问题都列了出来，希望你在试用之前心里有数。',
    permissionsTitle: '需要的权限',
    permissionOverlay: '悬浮窗权限：在其他 App 上方显示播放控制。',
    permissionNotification: '通知使用权：读取系统媒体会话状态，用于检测播放进度和歌曲结束。',
    permissionAccessibility: '无障碍服务：帮助 QQ 音乐进入播放器页面，并处理部分错误弹窗。',
    permissionUsage: '使用情况访问：浮球双击返回上一个 App 时需要查询最近任务列表。',
    permissionWriteSettings: '系统设置写入：临时切换系统旋转方向，用来绕开网易云的横屏检测。',
    permissionShizuku: 'Shizuku（可选）：启用后台模式时用来把音乐 App 启动到 freeform 窗口里。',
    permissionPost: '前台通知：显示后台服务的运行状态。',
    limitsTitle: '已知限制',
    limitForeground: '前台模式下切歌时官方 App 会跳到前台；后台模式可以避开这一点，但需要 Shizuku。',
    limitMediaSession: 'MediaSession 的 playFromMediaId / playFromUri / playFromSearch 在网易云和 QQ 音乐里都不会触发实际播放，只能依赖深度链接启动。',
    limitNeteaseLandscape: '网易云的横屏播放需要先把系统旋转锁成竖屏再恢复，大约要等 7–10 秒，这是我们目前找到的最稳定办法。',
    limitKugou: '酷狗音乐的歌单接口在未登录状态下不返回数据，目前只能抓取服务端渲染的页面，少量歌曲可能无法导入。',
    limitPlatform: '会员歌曲仍然需要你拥有对应平台的会员，Music Hub 不会绕过任何鉴权。',
    limitSync: '歌单自动同步目前主要支持网易云和 QQ 音乐；B 站和酷狗的内容可以导入单曲与歌单，但同步功能还在完善中。',
    limitPreview: '当前发布的是预览版，可能在边角场景下崩溃，欢迎在 GitHub 上提 Issue。',
    contributeEyebrow: '寻求帮助',
    contributeTitle: '我们希望和更多人一起把这些限制慢慢解掉。',
    contributeCopy: 'Music Hub 完全开源，目前由一位非 Android 出身的开发者在维护。下面列出的每一项都不是我们故意这么做，而是暂时还没找到解决方案的实际问题。如果你在其中任何一项上有经验，欢迎在 GitHub 上提 Issue、发 PR，或者只是分享一些思路。',
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
    contributeOEMCopy: 'HyperOS 会在重装应用后悄悄解绑 NotificationListenerService，手势导航也会把 freeform 窗口吸附回主屏。如果你在自己的设备上发现 Music Hub 表现异常，欢迎附上 logcat 和复现步骤。',
    contributeTagDocs: '文档与翻译',
    contributeDocsTitle: '使用文档与多语言',
    contributeDocsCopy: '目前文档主要支持中文和英文。如果你愿意帮忙翻译界面、撰写权限设置教程，或者把疑难解答整理成文章，欢迎直接向 site/ 或 README.md 提 PR。',
    contributeCta: '在 GitHub 上参与',
    contributeIssue: '提交 Issue',
    downloadEyebrow: 'Android 下载',
    downloadTitle: '获取 Music Hub 预览版',
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
    heroTitle: 'Music Hub',
    heroCopy: 'Put NetEase Cloud Music, QQ Music, Kugou, and Bilibili songs into one local library and queue. Music Hub manages scheduling and handoff while official apps keep doing the actual playback.',
    downloadCta: 'Download Android Preview',
    learnCta: 'See the workflow',
    mockTitle: 'Queue',
    mockSong: 'Unified queue',
    mockMeta: 'Delegated to official apps',
    positionEyebrow: 'Positioning',
    positionTitle: 'Not an aggregator player. A playback delegation system.',
    positionCopy: 'Music Hub does not play, download, or cache audio streams. It stores metadata and queue order, then delegates playback to official apps through deep links, system media sessions, and platform entry points.',
    cardPlatformTitle: 'Platform Apps',
    cardPlatformCopy: 'Playlists, recommendations, and queues stay inside one platform, so cross-platform listening means constant app switching.',
    cardAggregatorTitle: 'Aggregator Players',
    cardAggregatorCopy: 'They often depend on reverse-engineered APIs or scraped audio streams, then decode playback themselves. That is fragile and risky.',
    cardHubTitle: 'Music Hub',
    cardHubCopy: 'Manage songs from multiple platforms, launch official apps for playback, and take over next-song scheduling before playback ends.',
    workflowEyebrow: 'Workflow',
    workflowTitle: 'From shared link to continuous cross-platform playback.',
    workflowCopy: 'The system reduces the hard parts to four jobs: identify the song, save the queue, delegate playback, and monitor state. Users see one playlist while each platform still handles its own playback boundary.',
    workflowAddTitle: '1. Import songs',
    workflowAddCopy: 'Share from a music app or paste a link. LinkParser resolves short links, platform IDs, titles, artists, and platform-specific metadata.',
    workflowQueueTitle: '2. Build one queue',
    workflowQueueCopy: 'Songs are deduplicated by platform and platform song ID in the local Room database, then mixed into one playlist.',
    workflowDelegateTitle: '3. Delegate playback',
    workflowDelegateCopy: 'When playback starts, Music Hub generates a NetEase, QQ Music, Kugou, or Bilibili target link and opens the official app.',
    workflowMonitorTitle: '4. Monitor state',
    workflowMonitorCopy: 'Android MediaSession and notification access provide playback state and progress so Music Hub can advance near the end.',
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
    featureBackgroundCopy: 'Shizuku freeform pins the music app to an off-screen window so the Music Hub floating ball stays in the foreground while audio keeps playing.',
    featureTimeoutTitle: 'Per-song early end',
    featureTimeoutCopy: 'Long-press a song to set a custom mm:ss timeout so long intros or outros do not delay the next track in the queue.',
    featureBackupTitle: 'Backup and restore',
    featureBackupCopy: 'Export songs, playlists, and sync sources as a JSON file. Import on another device or restore after reinstall without losing custom data.',
    featureSetupTitle: 'First-run setup flow',
    featureSetupCopy: 'A guided setup screen walks first-time users through all six required permissions before they touch the main UI.',
    featureLocalTitle: 'Local-first storage',
    featureLocalCopy: 'Playlists live in the on-device database. No account server is required and no playback telemetry leaves the phone.',
    featureShareIntakeTitle: 'Share-link intake',
    featureShareIntakeCopy: 'Send a link from any music app share sheet and Music Hub identifies the platform, song ID, and metadata automatically.',
    platformNetease: 'NetEase Cloud Music',
    platformQq: 'QQ Music',
    platformKugou: 'Kugou Music',
    platformBili: 'Bilibili',
    detailsEyebrow: 'Before installing',
    detailsTitle: 'Clear permissions and clear limits.',
    detailsCopy: 'Music Hub delegates playback to the official apps, and that architecture comes with real edges. Here is the full permission list and every problem we have not yet solved cleanly, so you know what to expect before you try it.',
    permissionsTitle: 'Required Permissions',
    permissionOverlay: 'Overlay: show playback controls above other apps.',
    permissionNotification: 'Notification access: read system media-session state for progress and end-of-song detection.',
    permissionAccessibility: 'Accessibility service: help QQ Music enter its player screen and close some error dialogs.',
    permissionUsage: 'Usage access: list recent tasks so the floating ball can return you to the previous app on double-tap.',
    permissionWriteSettings: 'Write settings: temporarily toggle system rotation to work around NetEase landscape detection.',
    permissionShizuku: 'Shizuku (optional): required for background mode so the music app can be launched into a freeform window.',
    permissionPost: 'Post notifications: show the foreground service status.',
    limitsTitle: 'Known Limits',
    limitForeground: 'Foreground mode brings the music app to the front when songs change. Background mode avoids this but requires Shizuku.',
    limitMediaSession: 'MediaSession playFromMediaId / playFromUri / playFromSearch are silently ignored by NetEase and QQ Music; only deep-link launches actually switch songs.',
    limitNeteaseLandscape: 'NetEase landscape playback requires a portrait toggle and a 7–10 second wait. It is the most reliable approach we have found so far.',
    limitKugou: 'Kugou rejects unauthenticated playlist APIs, so Music Hub scrapes the server-side-rendered page. A small number of songs may fail to import.',
    limitPlatform: 'Membership-only songs still need the matching platform subscription. Music Hub does not bypass any authentication.',
    limitSync: 'Playlist sync currently focuses on NetEase and QQ Music. Bilibili and Kugou support single-song and playlist import but the sync loop is still being hardened.',
    limitPreview: 'The current build is a preview that may crash on edge cases. Please file issues on GitHub.',
    contributeEyebrow: 'Help wanted',
    contributeTitle: 'We want help turning every one of these limits into history.',
    contributeCopy: 'Music Hub is open source and maintained by a developer who is not an Android specialist. Every limit listed below is a real unsolved problem, not a design choice. If you have experience in any area, we welcome issues, PRs, or just a tip.',
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
    contributeOEMCopy: 'HyperOS silently unbinds NotificationListenerService after reinstalls and the gesture navigation snaps freeform windows back to the home screen. If Music Hub behaves oddly on your phone, please attach logcat and reproduction steps.',
    contributeTagDocs: 'Docs and translation',
    contributeDocsTitle: 'Documentation and translations',
    contributeDocsCopy: 'Documentation is currently bilingual Chinese and English. If you want to translate the UI, write up permission setup guides, or organize troubleshooting articles, PRs to site/ and README.md are appreciated.',
    contributeCta: 'Join on GitHub',
    contributeIssue: 'Open an issue',
    downloadEyebrow: 'Android Download',
    downloadTitle: 'Get the Music Hub preview',
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

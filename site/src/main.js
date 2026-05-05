const FALLBACK_VERSION = 'preview';
const FALLBACK_DOWNLOAD_URL = './downloads/music-hub.apk';
const METADATA_URL = './downloads/metadata.json';

const translations = {
  zh: {
    navFeatures: '功能',
    navWorkflow: '工作方式',
    navDownload: '下载',
    languageButtonSr: '切换到英文',
    eyebrow: '跨平台播放队列管理器',
    heroTitle: '音乐中心',
    heroCopy: '把网易云音乐、QQ音乐和哔哩哔哩的歌曲放进同一个本地曲库和播放队列。Music Hub 负责管理、调度和切歌，真正播放仍交给各平台官方 App。',
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
    cardHubCopy: '统一管理多平台歌曲，播放时调起官方 App，并在歌曲结束前接管下一首调度。',
    workflowEyebrow: '工作方式',
    workflowTitle: '从分享链接到跨平台连续播放。',
    workflowCopy: '系统把复杂流程拆成四件事：识别歌曲、保存队列、委托播放、监听状态。用户看到的是一个统一歌单，底层仍然尊重各平台的播放边界。',
    workflowAddTitle: '1. 歌曲入库',
    workflowAddCopy: '从音乐 App 分享或粘贴链接后，LinkParser 解析短链接、平台 ID、标题和艺术家，并按平台处理器补齐元数据。',
    workflowQueueTitle: '2. 统一队列',
    workflowQueueCopy: '歌曲以“平台 + 平台歌曲 ID”去重写入本地 Room 数据库，可混合编入同一个播放列表。',
    workflowDelegateTitle: '3. 播放委托',
    workflowDelegateCopy: '播放时生成网易云、QQ 音乐或 B 站的目标链接，启动官方 App 执行真实播放，保留会员权益。',
    workflowMonitorTitle: '4. 状态监听',
    workflowMonitorCopy: '通过 Android MediaSession/通知访问读取播放状态和进度，在接近结尾时自动切到队列里的下一首。',
    architectureEyebrow: '技术结构',
    architectureTitle: '关键模块各司其职。',
    architectureCopy: '系统架构可以概括为五个内部层次和一个外部播放层：界面负责组织，悬浮窗负责控制，服务层负责调度，平台层负责解析和启动，数据层负责持久化。',
    layerUiTitle: '表现层',
    layerUiCopy: '首页、曲库、歌单、发现、添加歌曲、同步源和设置页面统一承载日常操作。',
    layerOverlayTitle: '悬浮控制层',
    layerOverlayCopy: '完整控制窗和迷你浮球覆盖在其他 App 上方，提供播放、进度和队列入口。',
    layerServiceTitle: '服务层',
    layerServiceCopy: '播放服务、媒体监控、深度链接启动器、无障碍辅助和远程控制服务协同完成切歌。',
    layerPlatformTitle: '平台处理层',
    layerPlatformCopy: 'NetEase、QQ Music、Bilibili 处理器负责链接识别、元数据获取、可用性检测和播放入口生成。',
    layerDataTitle: '数据层',
    layerDataCopy: 'Room + SQLite 保存歌曲、歌单、队列顺序和同步源，使用 Kotlin Flow 更新界面。',
    layerOfficialTitle: '官方 App',
    layerOfficialCopy: '音频解码、流媒体传输和 DRM 验证仍由各平台官方客户端完成。',
    featuresEyebrow: '功能特点',
    featuresTitle: '面向真实使用场景的能力。',
    featureLibraryTitle: '跨平台曲库',
    featureLibraryCopy: '把网易云、QQ音乐和 B 站内容作为统一元数据管理，不把音频搬进应用。',
    featurePlaylistTitle: '歌单订阅与合并',
    featurePlaylistCopy: '将远程歌单关联到本地歌单，按同步源增量更新，同时保留手动添加的歌曲。',
    featureAdvanceTitle: '智能自动切歌',
    featureAdvanceCopy: '播放前检测可用性，失败时自动跳过；切换时处理旧平台继续播放和延迟启动问题。',
    featureFloatingTitle: '跨 App 悬浮控制',
    featureFloatingCopy: '在任意 App 上方查看当前歌曲、控制播放、拖动进度，并在完整窗和迷你浮球间切换。',
    featureShareTitle: '发现与推荐聚合',
    featureShareCopy: '聚合排行榜、分类歌单和已登录平台的每日推荐，支持一键加入跨平台队列。',
    featureLocalTitle: '双设备控制',
    featureLocalCopy: '播放器设备开放本地 HTTP/WebSocket 控制接口，控制器设备实时同步状态并发送操作。',
    platformNetease: '网易云音乐',
    platformQq: 'QQ音乐',
    platformBili: '哔哩哔哩',
    detailsEyebrow: '安装前说明',
    detailsTitle: '权限和限制说清楚。',
    permissionsTitle: '需要的权限',
    permissionOverlay: '悬浮窗权限：在其他 App 上方显示播放控制。',
    permissionNotification: '通知使用权：读取系统媒体会话状态，用于检测播放进度和歌曲结束。',
    permissionAccessibility: '无障碍服务：帮助 QQ 音乐进入播放器页面，并处理部分错误弹窗。',
    permissionPost: '通知权限：显示前台服务状态。',
    limitsTitle: '已知限制',
    limitForeground: '切歌时目标音乐 App 会切到前台。',
    limitPlatform: '会员歌曲仍然需要你拥有对应平台会员。',
    limitSync: '歌单同步当前以网易云和 QQ 音乐为主，B 站内容仍可作为歌曲入库和播放。',
    limitPreview: '当前下载为预览版，适合早期试用。',
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
    navDownload: 'Download',
    languageButtonSr: 'Switch to Chinese',
    eyebrow: 'Cross-platform queue manager',
    heroTitle: 'Music Hub',
    heroCopy: 'Put NetEase Cloud Music, QQ Music, and Bilibili songs into one local library and queue. Music Hub manages scheduling and handoff while official apps keep doing the actual playback.',
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
    workflowDelegateCopy: 'When playback starts, Music Hub generates a NetEase, QQ Music, or Bilibili target link and opens the official app.',
    workflowMonitorTitle: '4. Monitor state',
    workflowMonitorCopy: 'Android MediaSession and notification access provide playback state and progress so Music Hub can advance near the end.',
    architectureEyebrow: 'Architecture',
    architectureTitle: 'The main modules have clear responsibilities.',
    architectureCopy: 'The architecture can be read as five internal layers plus one external playback layer: UI organizes music, overlay controls playback, services schedule handoff, platform handlers parse and launch, and data stays local.',
    layerUiTitle: 'UI Layer',
    layerUiCopy: 'Home, library, playlists, discovery, add-song, sync-source, and settings screens hold everyday workflows.',
    layerOverlayTitle: 'Overlay Layer',
    layerOverlayCopy: 'A full controller and mini floating ball stay above other apps with playback, progress, and queue controls.',
    layerServiceTitle: 'Service Layer',
    layerServiceCopy: 'Playback, media monitoring, deep-link launch, accessibility assistance, and remote control services coordinate handoff.',
    layerPlatformTitle: 'Platform Layer',
    layerPlatformCopy: 'NetEase, QQ Music, and Bilibili handlers identify links, fetch metadata, check availability, and build launch targets.',
    layerDataTitle: 'Data Layer',
    layerDataCopy: 'Room + SQLite store songs, playlists, queue order, and sync sources while Kotlin Flow keeps screens updated.',
    layerOfficialTitle: 'Official Apps',
    layerOfficialCopy: 'Audio decoding, streaming, and DRM checks remain inside each platform’s official client.',
    featuresEyebrow: 'Features',
    featuresTitle: 'Capabilities for real listening workflows.',
    featureLibraryTitle: 'Cross-platform library',
    featureLibraryCopy: 'Manage NetEase, QQ Music, and Bilibili content as unified metadata without moving audio into the app.',
    featurePlaylistTitle: 'Playlist subscription and merge',
    featurePlaylistCopy: 'Attach remote playlists to a local playlist, sync source changes, and preserve manually added songs.',
    featureAdvanceTitle: 'Smart auto advance',
    featureAdvanceCopy: 'Check availability before playback, skip failures, and handle old-platform playback or delayed starts.',
    featureFloatingTitle: 'Cross-app floating control',
    featureFloatingCopy: 'See the current song, control playback, scrub progress, and switch between full and mini overlay modes.',
    featureShareTitle: 'Discovery and recommendations',
    featureShareCopy: 'Aggregate charts, category playlists, and signed-in daily recommendations into one cross-platform queue.',
    featureLocalTitle: 'Two-device control',
    featureLocalCopy: 'A player device exposes local HTTP/WebSocket control while a controller device mirrors state and sends actions.',
    platformNetease: 'NetEase Cloud Music',
    platformQq: 'QQ Music',
    platformBili: 'Bilibili',
    detailsEyebrow: 'Before installing',
    detailsTitle: 'Clear permissions and limits.',
    permissionsTitle: 'Required Permissions',
    permissionOverlay: 'Overlay permission: show playback controls above other apps.',
    permissionNotification: 'Notification access: read system media-session state for progress and end-of-song detection.',
    permissionAccessibility: 'Accessibility service: help QQ Music enter its player screen and close some error dialogs.',
    permissionPost: 'Notification permission: show foreground service status.',
    limitsTitle: 'Known Limits',
    limitForeground: 'Switching songs brings the target music app to the foreground.',
    limitPlatform: 'Membership-only songs still require the matching platform membership.',
    limitSync: 'Playlist sync currently focuses on NetEase and QQ Music; Bilibili content can still be imported and played as songs.',
    limitPreview: 'The current download is a preview build for early testing.',
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

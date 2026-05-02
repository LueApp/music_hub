const APP_VERSION = typeof __APP_VERSION__ === 'undefined' ? '1.0.0' : __APP_VERSION__;
const APK_FILENAME =
  typeof __APK_FILENAME__ === 'undefined' ? `music-hub-${APP_VERSION}-debug.apk` : __APK_FILENAME__;
const DOWNLOAD_URL = import.meta.env?.VITE_DOWNLOAD_URL || `./downloads/${APK_FILENAME}`;

const translations = {
  zh: {
    navFeatures: '功能',
    navDownload: '下载',
    languageButtonSr: '切换到英文',
    eyebrow: '跨平台音乐遥控器',
    heroTitle: '音乐中心',
    heroCopy: '把网易云音乐、QQ音乐和哔哩哔哩的歌曲放进同一个曲库，用官方 App 播放，用本应用管理队列和切歌。',
    downloadCta: '下载 Android 预览版',
    learnCta: '了解它的工作方式',
    mockTitle: '播放队列',
    mockSong: '跨平台歌单',
    mockMeta: '官方 App 播放',
    positionEyebrow: '核心定位',
    positionTitle: '它不是播放器，而是跨平台遥控器。',
    positionCopy: 'Music Hub 不播放、不下载、不缓存任何音乐。它通过深度链接打开官方 App，让你继续使用原平台会员和曲库。',
    cardPlatformTitle: '平台 App',
    cardPlatformCopy: '只管理自己的歌单和曲库，很难把多平台歌曲放进同一个播放队列。',
    cardAggregatorTitle: '聚合播放器',
    cardAggregatorCopy: '通常依赖逆向接口或抓取音频数据，维护成本高，也容易触碰平台限制。',
    cardHubTitle: 'Music Hub',
    cardHubCopy: '统一管理跨平台歌单，播放时调起官方 App，自动衔接下一首。',
    featuresEyebrow: '功能特点',
    featuresTitle: '面向真实播放习惯的歌单管理。',
    featureLibraryTitle: '统一音乐库',
    featureLibraryCopy: '把不同平台的歌曲集中到一个本地曲库中管理。',
    featurePlaylistTitle: '跨平台播放列表',
    featurePlaylistCopy: '创建包含网易云、QQ音乐和 B 站歌曲的混合歌单。',
    featureAdvanceTitle: '自动切歌',
    featureAdvanceCopy: '监听官方 App 播放状态，当前歌曲结束后打开下一首。',
    featureFloatingTitle: '悬浮窗控制',
    featureFloatingCopy: '在其他 App 上方保留播放控制，减少来回切换。',
    featureShareTitle: '分享接收',
    featureShareCopy: '从音乐 App 分享链接到 Music Hub，快速添加歌曲。',
    featureLocalTitle: '本地存储',
    featureLocalCopy: '歌单数据保存在设备本地，不需要账号服务器。',
    platformNetease: '网易云音乐',
    platformQq: 'QQ音乐',
    platformBili: '哔哩哔哩',
    detailsEyebrow: '安装前说明',
    detailsTitle: '透明说明权限和限制。',
    permissionsTitle: '需要的权限',
    permissionOverlay: '悬浮窗权限：在其他 App 上方显示播放控制。',
    permissionNotification: '通知使用权：检测歌曲播放结束。',
    permissionAccessibility: '无障碍服务：帮助 QQ 音乐进入播放器页面。',
    permissionPost: '通知权限：显示前台服务状态。',
    limitsTitle: '已知限制',
    limitForeground: '切歌时目标音乐 App 会切到前台。',
    limitPlatform: '会员歌曲仍然需要你拥有对应平台会员。',
    limitPreview: '当前下载为调试预览版，适合早期试用。',
    downloadEyebrow: 'Android 下载',
    downloadTitle: '获取 Music Hub 预览版',
    downloadCopy: '下载 debug APK 后在 Android 8.0 及以上设备安装。首次使用需要按提示授予悬浮窗、通知和无障碍相关权限。',
    downloadButton: '下载 APK',
    downloadNote: 'APK 会随 Cloudflare Pages 构建一起生成；也可用 VITE_DOWNLOAD_URL 指向外部下载地址。',
    footerText: '跨平台音乐播放列表管理器和启动器。'
  },
  en: {
    navFeatures: 'Features',
    navDownload: 'Download',
    languageButtonSr: 'Switch to Chinese',
    eyebrow: 'Cross-platform music remote',
    heroTitle: 'Music Hub',
    heroCopy: 'Put NetEase Cloud Music, QQ Music, and Bilibili songs in one library. Official apps play the music; Music Hub manages queues and handoff.',
    downloadCta: 'Download Android Preview',
    learnCta: 'See how it works',
    mockTitle: 'Queue',
    mockSong: 'Mixed-platform playlist',
    mockMeta: 'Played by official apps',
    positionEyebrow: 'Positioning',
    positionTitle: 'Not a player. A cross-platform remote.',
    positionCopy: 'Music Hub does not play, download, or cache music. It opens official apps through deep links, so you keep using your existing memberships and platform libraries.',
    cardPlatformTitle: 'Platform Apps',
    cardPlatformCopy: 'Each app manages its own music and playlists, making mixed-platform queues awkward.',
    cardAggregatorTitle: 'Aggregator Players',
    cardAggregatorCopy: 'They often depend on reverse-engineered APIs or scraped audio data, which is fragile and risky.',
    cardHubTitle: 'Music Hub',
    cardHubCopy: 'Manage mixed-platform playlists, launch official apps for playback, and advance to the next song automatically.',
    featuresEyebrow: 'Features',
    featuresTitle: 'Playlist management for real listening habits.',
    featureLibraryTitle: 'Unified Library',
    featureLibraryCopy: 'Collect songs from different platforms in one local library.',
    featurePlaylistTitle: 'Mixed Playlists',
    featurePlaylistCopy: 'Create playlists containing NetEase, QQ Music, and Bilibili songs.',
    featureAdvanceTitle: 'Auto Advance',
    featureAdvanceCopy: 'Watch official app playback state and open the next song when the current one ends.',
    featureFloatingTitle: 'Floating Controls',
    featureFloatingCopy: 'Keep playback controls above other apps to reduce app switching.',
    featureShareTitle: 'Share Receiver',
    featureShareCopy: 'Share links from music apps to Music Hub and add songs quickly.',
    featureLocalTitle: 'Local Storage',
    featureLocalCopy: 'Playlist data stays on the device with no account server required.',
    platformNetease: 'NetEase Cloud Music',
    platformQq: 'QQ Music',
    platformBili: 'Bilibili',
    detailsEyebrow: 'Before installing',
    detailsTitle: 'Clear about permissions and limits.',
    permissionsTitle: 'Required Permissions',
    permissionOverlay: 'Overlay permission: show playback controls above other apps.',
    permissionNotification: 'Notification access: detect when songs finish playing.',
    permissionAccessibility: 'Accessibility service: help QQ Music enter its player screen.',
    permissionPost: 'Notification permission: show foreground service status.',
    limitsTitle: 'Known Limits',
    limitForeground: 'Switching songs brings the target music app to the foreground.',
    limitPlatform: 'Membership-only songs still require the matching platform membership.',
    limitPreview: 'The current download is a debug preview build for early testing.',
    downloadEyebrow: 'Android Download',
    downloadTitle: 'Get the Music Hub preview',
    downloadCopy: 'Download the debug APK and install it on Android 8.0 or later. First use requires overlay, notification, and accessibility permissions.',
    downloadButton: 'Download APK',
    downloadNote: 'The APK is generated during the Cloudflare Pages build; VITE_DOWNLOAD_URL can point to an external download instead.',
    footerText: 'Cross-platform music playlist manager and launcher.'
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

downloadLink?.setAttribute('href', DOWNLOAD_URL);
versionLabel.textContent = `v${APP_VERSION} debug`;

const initialLanguage = localStorage.getItem('music-hub-language') === 'en' ? 'en' : 'zh';
applyLanguage(initialLanguage);

toggle?.addEventListener('click', () => {
  const nextLanguage = document.body.dataset.lang === 'zh' ? 'en' : 'zh';
  applyLanguage(nextLanguage);
});

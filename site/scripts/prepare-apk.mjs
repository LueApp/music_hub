import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const siteDir = resolve(scriptDir, '..');
const repoRoot = resolve(siteDir, '..');
const gradleFile = resolve(repoRoot, 'android-app/app/build.gradle.kts');
const sourceApk = resolve(repoRoot, 'android-app/app/build/outputs/apk/debug/app-debug.apk');
const downloadsDirs = [
  resolve(siteDir, 'public/downloads'), // Vite copies this to dist/downloads.
  resolve(siteDir, 'downloads') // Fallback if Cloudflare publishes raw site/.
];

const gradleContents = readFileSync(gradleFile, 'utf8');
const versionName = gradleContents.match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? '1.0.0';
const apkFilename = `music-hub-${versionName}-debug.apk`;

if (!existsSync(sourceApk)) {
  console.error(`Missing debug APK: ${sourceApk}`);
  console.error('Run `pixi run build` from the repository root first.');
  process.exit(1);
}

const bytes = statSync(sourceApk).size;
const metadata = {
  version: versionName,
  variant: 'debug',
  filename: apkFilename,
  bytes,
  updatedAt: new Date().toISOString()
};

for (const downloadsDir of downloadsDirs) {
  mkdirSync(downloadsDir, { recursive: true });
  copyFileSync(sourceApk, resolve(downloadsDir, apkFilename));
  writeFileSync(resolve(downloadsDir, 'metadata.json'), `${JSON.stringify(metadata, null, 2)}\n`);
}

console.log(
  `Copied ${apkFilename} (${(bytes / 1024 / 1024).toFixed(1)} MB) to site/public/downloads/ and site/downloads/`
);

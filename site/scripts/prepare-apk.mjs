import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const siteDir = resolve(scriptDir, '..');
const repoRoot = resolve(siteDir, '..');
const apkDir = resolve(repoRoot, 'android-app/app/build/outputs/apk/release');
const sourceApk = resolve(apkDir, 'app-release.apk');
const outputMetadataPath = resolve(apkDir, 'output-metadata.json');
const downloadsDirs = [
  resolve(siteDir, 'public/downloads'), // Vite copies this to dist/downloads.
  resolve(siteDir, 'downloads') // Fallback if Cloudflare publishes raw site/.
];

if (!existsSync(sourceApk)) {
  console.error(`Missing release APK: ${sourceApk}`);
  console.error('Run `pixi run build-release` from the repository root first.');
  process.exit(1);
}

const outputMetadata = JSON.parse(readFileSync(outputMetadataPath, 'utf8'));
const element = outputMetadata.elements?.[0];
if (!element?.versionName) {
  console.error(`Could not read versionName from ${outputMetadataPath}`);
  process.exit(1);
}

const versionName = element.versionName;
const versionCode = element.versionCode;
const apkFilename = `music-hub-${versionName}.apk`;

const bytes = statSync(sourceApk).size;
const metadata = {
  version: versionName,
  versionCode,
  variant: 'release',
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

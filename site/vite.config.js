import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';

function readAndroidVersion() {
  const gradleFile = resolve('../android-app/app/build.gradle.kts');
  const contents = readFileSync(gradleFile, 'utf8');
  const match = contents.match(/versionName\s*=\s*"([^"]+)"/);
  return match?.[1] ?? '1.0.0';
}

const appVersion = readAndroidVersion();
const apkFilename = `music-hub-${appVersion}-debug.apk`;

export default defineConfig({
  base: './',
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
    __APK_FILENAME__: JSON.stringify(apkFilename)
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    reportCompressedSize: true
  },
  server: {
    port: 5173,
    strictPort: false
  },
  preview: {
    port: 4173,
    strictPort: false
  }
});

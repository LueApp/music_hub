package com.musichub.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.musichub.R
import com.musichub.platform.Platforms

/**
 * Handles transitions between `launch_mode = background` and
 * `launch_mode = foreground`. Triggered by [PlaybackService]'s SharedPreferences
 * change listener.
 *
 * On a real mode change (fromMode != toMode), purges stale music-app tasks
 * via [ShizukuLauncher.purgeMusicAppTasks] so the next launch starts from a
 * clean slate, then clears in-process Shizuku tracking via
 * [ShizukuLauncher.clearTargetState]. Surfaces a Chinese Toast summarizing
 * the outcome (success / Shizuku-unavailable / failed).
 *
 * Currently-playing package is excluded from the purge so the live song is
 * not interrupted. Its task keeps whatever windowing mode it was launched in
 * (off-screen freeform or fullscreen); the new mode takes full effect only on
 * the next song-switch. Trade-off accepted: preserving audio > immediate
 * visual consistency.
 *
 * Why a separate object instead of inlining into [DeepLinkLauncher] or
 * [ShizukuLauncher]: keeps the "what happens on a mode toggle" logic in one
 * audit-able place. [DeepLinkLauncher] already mixes per-launch rotation
 * hacks, locked-screen branches, and per-platform package-name special cases
 * — adding cross-mode migration logic there would compound the tangle.
 */
object LaunchModeSwitcher {

    private const val TAG = "LaunchModeSwitcher"

    fun onModeChanged(context: Context, fromMode: String, toMode: String) {
        if (fromMode == toMode) {
            Log.d(TAG, "onModeChanged: no-op (fromMode==toMode==$fromMode)")
            return
        }
        Log.i(TAG, "onModeChanged: $fromMode -> $toMode")

        val appCtx = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val toLabel = modeLabelCn(toMode)

        if (ShizukuLauncher.status(appCtx) != ShizukuLauncher.Status.READY) {
            ShizukuLauncher.clearTargetState()
            mainHandler.post {
                Toast.makeText(
                    appCtx,
                    appCtx.getString(R.string.launch_mode_switch_no_shizuku_cn, toLabel),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val excludePackages = currentlyPlayingPackages()
        Log.d(TAG, "onModeChanged: excluding currently-playing $excludePackages")

        Thread({
            try {
                val result = ShizukuLauncher.purgeMusicAppTasks(appCtx, excludePackages)

                // bg → fg: promote the currently-playing app's task to fullscreen
                // by re-delivering its deep link with CLEAR_TASK via Shizuku.
                // CLEAR_TASK destroys the freeform Activity stack and forces a
                // fresh fullscreen Activity; the music app's audio Service is
                // independent of the Activity stack so audio continues.
                if (toMode == DeepLinkLauncher.LAUNCH_MODE_FOREGROUND) {
                    val currentSong = PlaybackService.getInstance()?.getCurrentSong()
                    if (currentSong != null) {
                        val pkg = Platforms.PACKAGE_NAMES[currentSong.platform]
                        if (pkg != null && pkg in excludePackages) {
                            ShizukuLauncher.promoteTaskToFullscreen(
                                appCtx, pkg, currentSong.deepLink
                            )
                        }
                    }
                }

                ShizukuLauncher.clearTargetState()
                mainHandler.post {
                    Toast.makeText(
                        appCtx,
                        appCtx.getString(
                            R.string.launch_mode_switch_done_cn,
                            result.total,
                            toLabel
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "onModeChanged purge threw: ${e.javaClass.simpleName}: ${e.message}")
                ShizukuLauncher.clearTargetState()
                mainHandler.post {
                    Toast.makeText(
                        appCtx,
                        appCtx.getString(
                            R.string.launch_mode_switch_failed_cn,
                            e.message ?: e.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, "LaunchModeSwitcher").start()
    }

    /**
     * Single-package exclude set: the package backing the current song in
     * Tutti's queue. This is the only package we deliberately want to keep
     * alive across a mode toggle.
     *
     * Why not also union with `MediaMonitorService.hasActiveController(pkg)`:
     * music apps frequently leave their MediaController alive after Tutti has
     * switched to a different platform (e.g., NetEase keeps a controller
     * around for many seconds after playback ends, or while paused). Including
     * those packages in the exclude set causes the purge to leave stale
     * freeform tasks on screen — defeating the whole mode-switch cleanup.
     *
     * Returns empty set when:
     *   - PlaybackService is not bound yet (returns null instance).
     *   - The current song's platform has no entry in Platforms.PACKAGE_NAMES.
     *   - The queue is empty.
     */
    private fun currentlyPlayingPackages(): Set<String> {
        val song = PlaybackService.getInstance()?.getCurrentSong() ?: return emptySet()
        val pkg = Platforms.PACKAGE_NAMES[song.platform] ?: return emptySet()
        return setOf(pkg)
    }

    private fun modeLabelCn(mode: String): String =
        if (mode == DeepLinkLauncher.LAUNCH_MODE_FOREGROUND) "前台" else "后台"
}

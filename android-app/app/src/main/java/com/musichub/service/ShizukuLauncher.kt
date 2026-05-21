package com.musichub.service

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import rikka.shizuku.Shizuku

/**
 * Status of one permission's auto-grant attempt. Surfaced back to UI / logs so
 * the user can see which permissions were missing, which were granted, and
 * which ones the shell command could not satisfy.
 */
data class PermissionState(
    val key: String,
    val displayName: String,
    val alreadyGranted: Boolean,
    val grantAttempted: Boolean,
    val grantSucceeded: Boolean,
)

/**
 * Aggregate of all permission states from one `autoGrantAllPermissions` call.
 * `newlyGranted` is the subset the notification cares about; `failed` lets
 * callers decide whether to follow up with a Toast or a deeper diagnostic.
 */
data class AutoGrantResult(
    val states: List<PermissionState>,
    val shizukuStatus: ShizukuLauncher.Status,
) {
    val newlyGranted: List<PermissionState>
        get() = states.filter { !it.alreadyGranted && it.grantSucceeded }
    val attempted: List<PermissionState>
        get() = states.filter { it.grantAttempted }
    val failed: List<PermissionState>
        get() = states.filter { it.grantAttempted && !it.grantSucceeded }
    val allReady: Boolean
        get() = states.isNotEmpty() && states.all { it.alreadyGranted || it.grantSucceeded }
}

/**
 * Bridge to the Shizuku app (https://shizuku.rikka.app) for executing shell-UID
 * commands from a regular app. We use it specifically to run
 * `am start --windowingMode 5` (freeform), which the Android framework otherwise
 * silently strips because it requires the signature-only `MANAGE_ACTIVITY_TASKS`
 * permission.
 *
 * Shizuku is started by the user via `adb pair` / wireless debugging or root, runs
 * in a separate process at shell UID, and exposes a Binder API. The
 * `dev.rikka.shizuku:api` library handles the IPC; we only check status,
 * request permission, and call `Shizuku.newProcess` (reflective to dodge
 * the @RestrictTo lint).
 */
object ShizukuLauncher {

    private const val TAG = "ShizukuLauncher"
    const val PERMISSION_REQUEST_CODE = 5042
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    // Watchdog: a slow safety-net only. The fast path is event-driven via
    // PlayerAccessibilityService — when HyperOS pulls our task back on-screen
    // during a home gesture, the accessibility window-bounds-changed event
    // fires within ~100 ms and we can re-resize immediately. This periodic
    // watchdog catches anything the event listener misses (e.g. when the
    // accessibility service is briefly disconnected, or events are dropped).
    // 5 s is plenty for a safety net.
    private const val WATCHDOG_PERIOD_MS = 5_000L
    private const val WATCHDOG_DURATION_MS = 5L * 60_000L

    // Shared state so PlayerAccessibilityService can trigger a resize on
    // demand using the same package + bounds-provider that the launching
    // flow set up. Volatile because the AccessibilityService callback runs
    // on a different thread from launchFreeform.
    @Volatile
    private var currentTargetPkg: String? = null

    @Volatile
    private var currentBoundsProvider: (() -> Rect)? = null

    @Volatile
    private var currentInitialBounds: Rect? = null

    // Generation counter so a new song's watchdog supersedes the previous one's.
    // Without this, back-to-back launches would accumulate threads, each trying
    // to resize the same shared QQ Music / NetEase task.
    private val resizeGeneration = AtomicLong(0)

    // SPEC: freeform-multi-task-hide
    // Per-package off-screen bounds memory. Populated by scheduleResize for every
    // music-app package Tutti has launched in this process lifetime; never
    // evicted on platform switch. Lets PlayerAccessibilityService re-hide a
    // prior platform's freeform task (e.g. NetEase) after the user has switched
    // to a different platform (e.g. QQ Music) and a HOME gesture pulls the
    // stale NetEase task back on-screen.
    private val pkgOffscreenBounds = ConcurrentHashMap<String, Rect>()

    // SPEC: freeform-multi-task-hide
    // Per-package throttle for triggerResize. HyperOS TYPE_WINDOWS_CHANGED events
    // can storm during transitions (5-10 events in <1s); throttling per-pkg
    // collapses each storm to a single resize without losing inter-pkg events.
    private val lastTriggerAtMs = ConcurrentHashMap<String, Long>()
    private const val TRIGGER_THROTTLE_MS = 200L

    enum class Status {
        NOT_INSTALLED,
        SERVICE_NOT_RUNNING,
        PERMISSION_DENIED,
        READY,
    }

    fun status(context: Context): Status {
        if (!isInstalled(context)) return Status.NOT_INSTALLED
        return try {
            if (!Shizuku.pingBinder()) return Status.SERVICE_NOT_RUNNING
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Status.PERMISSION_DENIED
            } else {
                Status.READY
            }
        } catch (e: Throwable) {
            // Shizuku binder may throw IllegalStateException if not bound yet.
            Log.d(TAG, "Shizuku status check threw ${e.javaClass.simpleName}: ${e.message}")
            Status.SERVICE_NOT_RUNNING
        }
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Open the Shizuku app's launcher activity so the user can start the service
     * or grant our permission. Returns false if the app isn't installed (caller
     * should direct the user to install it).
     */
    fun openShizukuApp(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku app: ${e.message}")
            false
        }
    }

    /**
     * Open Shizuku's website / Play Store entry. Used when Shizuku isn't installed.
     */
    fun openInstallPage(context: Context) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://shizuku.rikka.app/")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku install page: ${e.message}")
        }
    }

    /**
     * Request Shizuku permission. Result is delivered to the listener that was
     * added beforehand via [Shizuku.addRequestPermissionResultListener].
     * Returns false if Shizuku is not running (caller should handle).
     */
    fun requestPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku.requestPermission failed: ${e.message}")
            false
        }
    }

    /**
     * Launch a deep link in freeform windowing mode.
     *
     * Two paths, decided atomically inside one shell script to avoid binder
     * round-trips and to keep the off-screen invariant tight:
     *
     *   REUSE: if a visible freeform task for the target package already exists,
     *   the deep link is delivered without `--windowingMode 5`. The intent goes
     *   to the existing task via onNewIntent; bounds stay where they were. If
     *   the task drifted away from [bounds] (e.g. a home-gesture snap-back), it
     *   is `am task resize`d *before* the intent is delivered so the new song's
     *   first frame draws off-screen. No visible flash.
     *
     *   COLD: no existing freeform task. We fall back to `am start --windowingMode 5`
     *   plus the legacy poll-and-resize watchdog. The first cold start of a music
     *   app session still flashes briefly (HyperOS draws the task at its default
     *   centered freeform bounds before our resize lands ~80–200ms later), but
     *   every subsequent switch hits the REUSE path.
     *
     * Constrain intent resolution to the music app for this deep link. Kugou's
     * deep link is an HTTPS URL that browsers also claim — without `-p`, HyperOS's
     * resolver routes m.kugou.com to the browser instead of com.kugou.android.
     * Custom-scheme deep links (orpheus://, qqmusic://, bilibili://) are
     * unambiguous, so `-p` is redundant but harmless there.
     *
     * Flag breakdown:
     *   0x10000000 = FLAG_ACTIVITY_NEW_TASK
     *   0x00010000 = FLAG_ACTIVITY_NO_ANIMATION (suppress activity-side launch animation)
     *
     * NOTE: don't add FLAG_ACTIVITY_SINGLE_TOP. When SINGLE_TOP delivers to an
     * existing top activity via onNewIntent and the --windowingMode hint is used,
     * the existing activity keeps its current windowing mode — which is exactly
     * what we want in the REUSE path, but only when we *don't* pass --windowingMode.
     * For COLD launches we need a fresh task in freeform mode, so SINGLE_TOP would
     * defeat the windowingMode hint there. Different flags for the two paths is
     * the simplest correct answer.
     *
     * Also don't add `--no-window-animation`: this HyperOS build's `am` rejects
     * it with "Unknown option" and the whole `am start` then throws.
     *
     * Returns true iff the launch script exited 0. The follow-up watchdog is
     * fire-and-forget on a background thread.
     */
    fun launchFreeform(
        context: Context,
        deepLink: String,
        bounds: Rect? = null,
        boundsProvider: (() -> Rect)? = null
    ): Boolean {
        if (status(context) != Status.READY) {
            Log.d(TAG, "Shizuku not ready, skipping freeform launch")
            return false
        }

        val targetPackage = packageForDeepLink(deepLink)

        return try {
            val script = buildLaunchScript(deepLink, targetPackage, bounds)
            val args = if (targetPackage != null) {
                arrayOf("sh", "-c", script, "musichub", targetPackage)
            } else {
                arrayOf("sh", "-c", script, "musichub", "")
            }
            val process = newShizukuProcess(args) ?: return false
            val exit = process.waitFor()
            val output = try {
                process.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }
            val mode = Regex("""MODE=(\w+)""").find(output)?.groupValues?.get(1) ?: "unknown"
            Log.i(TAG, "Shizuku launch exit=$exit pkg=${targetPackage ?: "<unspecified>"} mode=$mode output=${output.take(200).replace("\n", " | ")}")

            if (exit == 0 && bounds != null) {
                // Same scheduleResize path for both REUSE and COLD: the multi-offset
                // resize is a no-op when bounds are already correct (REUSE), and a
                // safety re-application when bounds are still settling (COLD,
                // Bilibili's IntentHandler→UnitedBizDetails chain resets task
                // bounds within 2-5s of launch). The watchdog also tracks the
                // floating-ball position if the user drags it.
                scheduleResize(deepLink, bounds, boundsProvider)
            }
            exit == 0
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku launchFreeform failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Single shell script that:
     *   1. Looks up the visible freeform task for [targetPackage] in dumpsys.
     *   2. REUSE path: if found, resizes it to [bounds] iff it drifted, then
     *      delivers the deep link via plain `am start` (no --windowingMode). The
     *      existing task receives onNewIntent without changing windowing mode →
     *      no visible flash.
     *   3. COLD path: no task found → `am start --windowingMode 5` to create a
     *      fresh freeform task. Kotlin then takes over via scheduleResize.
     *
     * Output ends with a one-line `MODE=reuse` or `MODE=cold` marker so the
     * caller can decide whether the cold-path watchdog is needed.
     */
    private fun buildLaunchScript(deepLink: String, targetPackage: String?, bounds: Rect?): String {
        // Shell-quote the deep link. Single-quoting + escaping any single quote
        // inside the URL is sufficient — neither orpheus:// nor https:// URLs
        // produced by our platform handlers contain single quotes.
        val quotedDeepLink = "'" + deepLink.replace("'", """'\''""") + "'"

        // Build the -p argument conditionally — empty package means no -p.
        val packageArg = if (!targetPackage.isNullOrEmpty()) "-p \"\$PKG\"" else ""

        // Target bounds for resize-before-intent in the reuse path. If bounds
        // is null we skip the resize check entirely.
        val (L, T, R, B) = if (bounds != null) {
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map { it.toString() }
        } else {
            listOf("", "", "", "")
        }
        val hasBounds = bounds != null

        return """
            PKG="${'$'}1"

            findTaskInfo() {
              dumpsys activity activities 2>/dev/null | awk -v pkg="${'$'}1" '
                /^[[:space:]]*\* Task\{/ {
                  match(${'$'}0, /#[0-9]+/)
                  cur_id = substr(${'$'}0, RSTART+1, RLENGTH-1)
                  ff = (${'$'}0 ~ /mode=freeform/)
                  vis = (${'$'}0 ~ /visible=true/)
                  cur_bounds = ""
                }
                ff && vis && cur_bounds == "" && /^[[:space:]]+mBounds=Rect\(/ {
                  match(${'$'}0, /Rect\([-0-9, ]+\)/)
                  cur_bounds = substr(${'$'}0, RSTART, RLENGTH)
                }
                ff && vis && /Hist/ && cur_bounds != "" && index(${'$'}0, pkg "/") > 0 {
                  print cur_id "|" cur_bounds
                  exit
                }
              '
            }

            if [ -n "${'$'}PKG" ]; then
              INFO=${'$'}(findTaskInfo "${'$'}PKG")
            else
              INFO=""
            fi

            if [ -n "${'$'}INFO" ]; then
              TID=${'$'}(echo "${'$'}INFO" | cut -d'|' -f1)
              OLDBOUNDS=${'$'}(echo "${'$'}INFO" | cut -d'|' -f2-)
              ${if (hasBounds) """
              TARGET="Rect($L, $T - $R, $B)"
              if [ "${'$'}OLDBOUNDS" != "${'$'}TARGET" ]; then
                am task resize "${'$'}TID" $L $T $R $B
              fi
              """ else ""}
              am start -a android.intent.action.VIEW -d $quotedDeepLink $packageArg -f 0x10010000
              echo "MODE=reuse TID=${'$'}TID OLDBOUNDS=${'$'}OLDBOUNDS"
              exit 0
            fi

            am start --windowingMode 5 -a android.intent.action.VIEW -d $quotedDeepLink $packageArg -f 0x10010000
            AM_EXIT=${'$'}?
            ${if (hasBounds) """
            # Race the resize against the first frame of the new freeform task.
            # Polling+resizing inside the same shell call (one binder IPC) cuts
            # the visible-flash window in half vs. handing off to Kotlin's
            # scheduleResize, which spawns a thread and a fresh Shizuku process.
            # Tight 10ms polling for the first 300ms covers the typical task
            # appearance latency on this device.
            if [ "${'$'}PKG" != "" ] && [ "${'$'}AM_EXIT" = "0" ]; then
              i=0
              while [ ${'$'}i -lt 30 ]; do
                INFO=${'$'}(findTaskInfo "${'$'}PKG")
                if [ -n "${'$'}INFO" ]; then
                  TID=${'$'}(echo "${'$'}INFO" | cut -d'|' -f1)
                  am task resize "${'$'}TID" $L $T $R $B
                  echo "MODE=cold AM_EXIT=${'$'}AM_EXIT TID=${'$'}TID RESIZE_AT=${'$'}{i}x10ms"
                  exit 0
                fi
                sleep 0.01
                i=${'$'}((i+1))
              done
            fi
            """ else ""}
            echo "MODE=cold AM_EXIT=${'$'}AM_EXIT"
            exit ${'$'}AM_EXIT
        """.trimIndent()
    }

    /**
     * After launching, find the new task's id by package name (via `dumpsys
     * activity activities`) and resize it to [bounds]. Runs on a background
     * thread because it sleeps to wait for the task to register, then makes
     * two more Shizuku IPC calls.
     */
    private fun scheduleResize(
        deepLink: String,
        initialBounds: Rect,
        boundsProvider: (() -> Rect)? = null
    ) {
        val pkg = packageForDeepLink(deepLink)
        if (pkg == null) {
            Log.d(TAG, "Resize skipped: unknown package for deep link $deepLink")
            return
        }

        // Newer launch supersedes any in-flight watchdog from a prior song.
        val myGeneration = resizeGeneration.incrementAndGet()

        // Publish current target so the AccessibilityService's event handler
        // can fire on-demand resizes using the same pkg + bounds.
        currentTargetPkg = pkg
        currentBoundsProvider = boundsProvider
        currentInitialBounds = initialBounds

        // SPEC: freeform-multi-task-hide
        // Snapshot the off-screen bounds per package so the accessibility-driven
        // re-hide path in triggerResize can still target this package after the
        // user switches to a different platform (and currentTargetPkg moves on).
        val snapshotBounds = try {
            boundsProvider?.invoke() ?: initialBounds
        } catch (e: Throwable) {
            Log.w(TAG, "scheduleResize boundsProvider snapshot threw: ${e.message}")
            initialBounds
        }
        pkgOffscreenBounds[pkg] = snapshotBounds

        // Build a `am task resize` command for the given bounds. Used for
        // both the initial multi-attempt resize and the watchdog loop —
        // the watchdog rebuilds the command each tick so it picks up the
        // latest target (e.g. floating-ball position changes).
        fun buildResizeCmd(taskId: String, b: Rect) = arrayOf(
            "am", "task", "resize", taskId,
            b.left.toString(),
            b.top.toString(),
            b.right.toString(),
            b.bottom.toString()
        )

        Thread({
            try {
                // Cold-launch apps need a few seconds before the freeform task is
                // discoverable. We walk Task blocks in dumpsys with awk and find
                // the first VISIBLE freeform task whose Hist references our
                // package. We also can't grep by `A=[0-9]*:<pkg>` (task affinity):
                // apps like QQ Music declare a custom affinity
                // (`android.task.qqmusic`) that doesn't match the package name.
                // The Hist line always carries the actual package as part of
                // the activity component.
                //
                // Crucially, the `visible=true` filter is required because QQ
                // Music creates a NEW task for every deep-link launch and
                // leaves the old ones lingering as invisible freeform tasks.
                // Without the filter, we'd cache a stale task id whose window
                // has been replaced by a newer one — and our watchdog would
                // resize a hidden ghost while the actually-visible task stays
                // at QQ Music's default huge bounds.
                // Two-phase poll: rapid 20ms ticks for the first 600ms (covers
                // the typical task-creation latency and minimizes the visible
                // flash of HyperOS chrome before our first resize fires), then
                // back off to 200ms for up to 6s more (covers slow cold launches
                // where the Hist line takes a while to appear).
                val initialPollScript = """
                    findTask() {
                      dumpsys activity activities 2>/dev/null | awk -v pkg="${'$'}1" '
                        /^[[:space:]]*\* Task\{/ { cur=${'$'}0; ff=(${'$'}0 ~ /mode=freeform/); vis=(${'$'}0 ~ /visible=true/) }
                        ff && vis && index(${'$'}0, pkg "/") > 0 && /Hist/ { match(cur, /#[0-9]+/); print substr(cur, RSTART+1, RLENGTH-1); exit }
                      '
                    }
                    i=0
                    while [ ${'$'}i -lt 30 ]; do
                      T=${'$'}(findTask "${'$'}1")
                      if [ -n "${'$'}T" ]; then echo "${'$'}T"; exit 0; fi
                      sleep 0.02
                      i=${'$'}((i+1))
                    done
                    i=0
                    while [ ${'$'}i -lt 30 ]; do
                      T=${'$'}(findTask "${'$'}1")
                      if [ -n "${'$'}T" ]; then echo "${'$'}T"; exit 0; fi
                      sleep 0.2
                      i=${'$'}((i+1))
                    done
                    exit 1
                """.trimIndent()
                val findProc = newShizukuProcess(
                    arrayOf("sh", "-c", initialPollScript, "musichub", pkg)
                ) ?: return@Thread
                val taskId = findProc.inputStream.bufferedReader().readText().trim()
                val pollExit = findProc.waitFor()

                if (pollExit != 0 || taskId.toIntOrNull() == null) {
                    Log.w(TAG, "Resize: could not resolve task id for $pkg (exit=$pollExit, got '$taskId')")
                    return@Thread
                }
                Log.d(TAG, "Resize: resolved $pkg taskId=$taskId")

                // Fire the resize at multiple offsets. Bilibili specifically
                // launches through `IntentHandlerActivity` → `UnitedBizDetailsActivity`,
                // and resizing while the *first* of those is top gets undone
                // when the second takes over. The transition typically lands
                // somewhere in the 0.5-2s window. Resizing again at +2s and +5s
                // catches the post-transition state regardless of exact timing.
                // For NetEase / QQ Music whose bounds stick from the first
                // resize, the follow-ups are no-ops (~3 cheap binder calls).
                val offsetsMs = longArrayOf(0L, 2000L, 5000L)
                var lastSchedule = 0L
                offsetsMs.forEachIndexed { idx, offset ->
                    val sleepFor = offset - lastSchedule
                    if (sleepFor > 0) Thread.sleep(sleepFor)
                    lastSchedule = offset
                    val cmd = buildResizeCmd(taskId, initialBounds)
                    val proc = newShizukuProcess(cmd) ?: return@forEachIndexed
                    val exit = proc.waitFor()
                    Log.i(TAG, "Shizuku am task resize taskId=$taskId offset=${offset}ms exit=$exit (attempt ${idx + 1}/${offsetsMs.size})")
                }

                // Watchdog loop. Three jobs:
                //   1. Recover from HyperOS's gesture-snap (it auto-resizes
                //      freeform tasks to its enforced minimum during home-swipe
                //      / recents events).
                //   2. Track the floating-ball position if the user drags it
                //      mid-playback — boundsProvider re-reads the ball position
                //      on each tick, so the music-app window follows.
                //   3. Re-target the currently-visible task each tick. QQ Music
                //      creates a new task per deep-link launch; the task id we
                //      cached at launch time may have been replaced by a newer
                //      visible one. Re-polling each tick keeps us pointed at
                //      the task the user actually sees.
                //
                // Stop when a newer launch increments the generation, or after
                // WATCHDOG_DURATION_MS. Transient newShizukuProcess failures or
                // missing-task polls skip the iteration rather than break the
                // loop — the music app may briefly have no visible freeform
                // task during transitions.
                val watchdogPollScript = """
                    dumpsys activity activities 2>/dev/null | awk -v pkg="${'$'}1" '
                      /^[[:space:]]*\* Task\{/ { cur=${'$'}0; ff=(${'$'}0 ~ /mode=freeform/); vis=(${'$'}0 ~ /visible=true/) }
                      ff && vis && index(${'$'}0, pkg "/") > 0 && /Hist/ { match(cur, /#[0-9]+/); print substr(cur, RSTART+1, RLENGTH-1); exit }
                    '
                """.trimIndent()
                val deadline = System.currentTimeMillis() + WATCHDOG_DURATION_MS
                while (System.currentTimeMillis() < deadline &&
                       resizeGeneration.get() == myGeneration) {
                    Thread.sleep(WATCHDOG_PERIOD_MS)
                    if (resizeGeneration.get() != myGeneration) break

                    val pollProc = newShizukuProcess(arrayOf("sh", "-c", watchdogPollScript, "musichub", pkg))
                    if (pollProc == null) {
                        Log.d(TAG, "Watchdog: poll process null, will retry")
                        continue
                    }
                    val currentTaskId = pollProc.inputStream.bufferedReader().readText().trim()
                    pollProc.waitFor()
                    if (currentTaskId.toIntOrNull() == null) {
                        // No visible freeform task right now — user may have
                        // hidden it or switched apps. Skip this tick.
                        continue
                    }

                    val targetBounds = try {
                        boundsProvider?.invoke() ?: initialBounds
                    } catch (e: Throwable) {
                        Log.w(TAG, "boundsProvider threw: ${e.message}")
                        initialBounds
                    }
                    val cmd = buildResizeCmd(currentTaskId, targetBounds)
                    val proc = newShizukuProcess(cmd) ?: continue
                    proc.waitFor()
                }
                Log.d(TAG, "Watchdog stopped for $pkg (gen=$myGeneration)")
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku resize failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "ShizukuResize").start()
    }

    /**
     * The packages PlayerAccessibilityService should listen to for
     * window-bounds-changed events. Matches the music apps we know how
     * to launch in freeform.
     */
    fun musicAppPackages(): Set<String> = setOf(
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "tv.danmaku.bili",
        "com.kugou.android",
    )

    /**
     * The HyperOS Security Center package — host of the
     * `com.miui.wakepath.ui.ConfirmStartActivity` dialog that asks the user
     * to confirm cross-app launches ("Tutti 想要打开 QQ 音乐"). The
     * PlayerAccessibilityService also listens to this package so it can
     * auto-tap "始终允许" the first time the dialog appears for each
     * (Tutti → target) pair; after that, HyperOS records the choice and
     * the dialog stops firing.
     */
    const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"

    fun accessibilityListenPackages(): Set<String> =
        musicAppPackages() + MIUI_SECURITY_CENTER_PACKAGE

    /**
     * Fire a resize for whichever music-app package is currently being
     * managed (no argument required). Used by display-rotation handlers and
     * other system-wide events that don't carry a specific package.
     *
     * Throttled to at most one trigger per 100 ms to absorb fast bursts of
     * events (e.g. window-state-changed during a rotation animation can
     * fire 5-10 times in rapid succession; we only need one resize per
     * burst since the post-rotation state is what matters).
     */
    @Volatile
    private var lastTriggerMs: Long = 0

    fun triggerResizeForCurrentTarget() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < 100L) return
        lastTriggerMs = now
        val pkg = currentTargetPkg ?: return
        triggerResize(pkg)
    }

    /**
     * Drop all background-mode tracking state so foreground-mode launches
     * (and other paths that don't manage windowing) don't inherit a stale
     * target package + bounds-provider from a prior background launch.
     *
     * Increments [resizeGeneration] so any in-flight watchdog thread for the
     * old target sees the bump on its next iteration and exits cleanly.
     *
     * Called from [DeepLinkLauncher.launchForeground] at entry and from a
     * `launch_mode` preference-change listener in [PlaybackService] when the
     * user switches from background to foreground.
     */
    fun clearTargetState() {
        val newGen = resizeGeneration.incrementAndGet()
        currentTargetPkg = null
        currentBoundsProvider = null
        currentInitialBounds = null
        Log.d(TAG, "Cleared target state (gen=$newGen)")
    }

    /**
     * Fire-and-forget single-shot resize for a specific music-app package.
     * Called by [PlayerAccessibilityService] on TYPE_WINDOWS_CHANGED events
     * when HyperOS pulls one of our freeform tasks back on-screen (typical
     * during HOME / recents gestures).
     *
     * SPEC: freeform-multi-task-hide — no longer gated on `currentTargetPkg`;
     * any package with an entry in [pkgOffscreenBounds] is eligible. Throttled
     * per-pkg at [TRIGGER_THROTTLE_MS] to absorb HyperOS event storms.
     */
    fun triggerResize(pkg: String) {
        // SPEC: freeform-multi-task-hide
        // No longer gated on `currentTargetPkg == pkg`. Every music-app package
        // that has an entry in pkgOffscreenBounds (i.e. one Tutti launched in
        // this session) is eligible for off-screen re-hide, even if it is not
        // currently the active platform. This is the mechanism that re-hides
        // a stale NetEase freeform task after the user has switched to QQ Music
        // and a HOME gesture pulls the NetEase task back on-screen.
        val now = System.currentTimeMillis()
        val last = lastTriggerAtMs[pkg] ?: 0L
        if (now - last < TRIGGER_THROTTLE_MS) return
        lastTriggerAtMs[pkg] = now

        // Active target uses the live boundsProvider so the freeform task
        // follows the floating-ball if the user dragged it. Inactive prior
        // platforms reuse their last-known snapshot — still off-screen, which
        // is what hide-on-HOME needs.
        val bounds = if (pkg == currentTargetPkg) {
            currentBoundsProvider?.invoke() ?: currentInitialBounds ?: pkgOffscreenBounds[pkg]
        } else {
            pkgOffscreenBounds[pkg]
        } ?: return

        Log.d(
            TAG,
            "triggerResize($pkg): pkg in pkgOffscreenBounds (size=${pkgOffscreenBounds.size}), resizing to $bounds"
        )

        Thread({
            try {
                val pollScript = """
                    dumpsys activity activities 2>/dev/null | awk -v pkg="${'$'}1" '
                      /^[[:space:]]*\* Task\{/ { cur=${'$'}0; ff=(${'$'}0 ~ /mode=freeform/); vis=(${'$'}0 ~ /visible=true/) }
                      ff && vis && index(${'$'}0, pkg "/") > 0 && /Hist/ { match(cur, /#[0-9]+/); print substr(cur, RSTART+1, RLENGTH-1); exit }
                    '
                """.trimIndent()
                val pollProc = newShizukuProcess(arrayOf("sh", "-c", pollScript, "wrapper", pkg)) ?: return@Thread
                val taskId = pollProc.inputStream.bufferedReader().readText().trim()
                pollProc.waitFor()
                if (taskId.toIntOrNull() == null) return@Thread

                val cmd = arrayOf(
                    "am", "task", "resize", taskId,
                    bounds.left.toString(),
                    bounds.top.toString(),
                    bounds.right.toString(),
                    bounds.bottom.toString()
                )
                val proc = newShizukuProcess(cmd) ?: return@Thread
                val exit = proc.waitFor()
                Log.d(TAG, "triggerResize: $pkg taskId=$taskId bounds=$bounds exit=$exit")
            } catch (e: Throwable) {
                Log.w(TAG, "triggerResize failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "ShizukuTriggerResize").start()
    }

    private fun packageForDeepLink(deepLink: String): String? = when {
        deepLink.startsWith("orpheus://") -> "com.netease.cloudmusic"
        deepLink.startsWith("qqmusic://") -> "com.tencent.qqmusic"
        deepLink.startsWith("bilibili://") || deepLink.contains("bilibili.com") ->
            "tv.danmaku.bili"
        deepLink.startsWith("kugou://") || deepLink.contains("kugou.com") ->
            "com.kugou.android"
        deepLink.contains("music.163.com") -> "com.netease.cloudmusic"
        deepLink.contains("y.qq.com") -> "com.tencent.qqmusic"
        else -> null
    }

    /**
     * Read a `Settings.Global` value. Tries the regular Android API first
     * (which works for the public Global namespace on most ROMs), then falls
     * back to `settings get global <name>` via Shizuku for hidden/restricted
     * keys like `force_resizable_activities` on some Android builds.
     *
     * Returns the value as a trimmed string, or `null` if the setting is
     * unreadable. A direct read returning `-1` is treated as "absent" and
     * triggers the Shizuku fallback, because the framework reports `-1` for
     * missing keys when called with that as the default.
     *
     * Empirical note (verify per-device, see tasks.md 1.2): on HyperOS the
     * direct read of `force_resizable_activities` works without Shizuku; on
     * some stock Android builds the value sits on the hidden-API list and
     * direct reads return the default. The Shizuku fallback covers both.
     */
    fun readGlobalSetting(context: Context, name: String): String? {
        try {
            val direct = android.provider.Settings.Global.getInt(
                context.contentResolver,
                name,
                Int.MIN_VALUE
            )
            if (direct != Int.MIN_VALUE) {
                return direct.toString()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "readGlobalSetting direct $name failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (status(context) != Status.READY) return null
        return try {
            val proc = newShizukuProcess(arrayOf("settings", "get", "global", name)) ?: return null
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isEmpty() || out == "null") null else out
        } catch (e: Throwable) {
            Log.w(TAG, "readGlobalSetting Shizuku $name failed: ${e.message}")
            null
        }
    }

    /**
     * Write a `Settings.Global` value via Shizuku. Writing globals requires
     * `WRITE_SECURE_SETTINGS`, which a regular app can't hold, so this only
     * works when Shizuku is `READY`. Returns `true` iff the underlying
     * `settings put global <name> <value>` exited 0.
     *
     * Logs the old → new transition so the audit trail in tasks.md 5.1 is
     * preserved end-to-end across the diagnostic Reset action.
     */
    fun writeGlobalSetting(context: Context, name: String, value: String): Boolean {
        if (status(context) != Status.READY) {
            Log.d(TAG, "writeGlobalSetting $name -> $value skipped: Shizuku not ready")
            return false
        }
        val old = readGlobalSetting(context, name) ?: "<unknown>"
        return try {
            val proc = newShizukuProcess(arrayOf("settings", "put", "global", name, value))
                ?: return false
            val exit = proc.waitFor()
            Log.i(TAG, "Shizuku settings put global $name $old -> $value (exit=$exit)")
            exit == 0
        } catch (e: Throwable) {
            Log.w(TAG, "writeGlobalSetting $name -> $value failed: ${e.message}")
            false
        }
    }

    /**
     * Force the system to re-bind a NotificationListenerService by toggling
     * the listener allow-list off and on via shell. HyperOS sometimes leaves
     * NotificationListenerService unbound after APK reinstalls or after the
     * process is killed; `NotificationListenerService.requestRebind()` is
     * supposed to fix that but appears unreliable on HyperOS.
     *
     * Toggling via `cmd notification disallow_listener` + `allow_listener`
     * always works (verified on the device), but requires shell UID — which
     * is exactly what Shizuku gives us.
     *
     * Returns true iff the toggle commands ran. Safe to call when Shizuku
     * is unavailable (returns false; caller should fall back to
     * NotificationListenerService.requestRebind).
     */
    fun rebindNotificationListener(context: Context, componentName: String): Boolean {
        if (status(context) != Status.READY) {
            Log.d(TAG, "Shizuku not ready, can't toggle listener for $componentName")
            return false
        }
        val script =
            "cmd notification disallow_listener $componentName 2>/dev/null; sleep 0.3; cmd notification allow_listener $componentName 2>/dev/null"
        return try {
            val proc = newShizukuProcess(arrayOf("sh", "-c", script)) ?: return false
            val exit = proc.waitFor()
            Log.i(TAG, "Shizuku notification listener toggle for $componentName exit=$exit")
            exit == 0
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku rebindNotificationListener failed: ${e.message}")
            false
        }
    }

    /**
     * Re-enable our accessibility services in Settings.Secure when AOSP's
     * `am force-stop` handler has silently revoked them. This is *the* fix
     * for HyperOS users (and stock Android users on aggressive task killers)
     * who repeatedly find 无障碍服务权限 missing — when a package is force-
     * stopped it goes into `stopped=true` state, and AccessibilityManager
     * excludes services from stopped packages on its next sweep, which
     * removes them from `enabled_accessibility_services`. Re-launching the
     * app clears the stopped flag but the system does NOT auto-restore the
     * setting. The only way back without user intervention is to re-write
     * the setting, which requires WRITE_SECURE_SETTINGS — granted to shell
     * UID, accessible to us via Shizuku.
     *
     * Preserves any third-party services already in the list; appends only
     * the components in [desired] that are missing.
     *
     * Component-name matching is form-tolerant: a desired entry written as
     * `pkg/com.full.Class` is treated as already-present if the setting
     * stores `pkg/.Class` (relative form). The system uses canonical form on
     * API 26+ HyperOS empirically, but defensive normalization protects
     * against future Android versions that may rewrite to either form.
     *
     * After writing the setting, reads it back via Settings.Secure to verify
     * the desired components actually landed (the shell call exiting 0 only
     * means the command ran — it does not guarantee the value persisted; on
     * some ROMs an over-aggressive security audit can roll back the write).
     * Returns true iff exit == 0 AND every desired component is observed in
     * the post-write read.
     */
    fun restoreAccessibilityServices(context: Context, desired: Set<String>): Boolean {
        val currentStatus = status(context)
        if (currentStatus != Status.READY) {
            Log.d(TAG, "restoreA11y skipped: Shizuku status=$currentStatus")
            return false
        }
        if (desired.isEmpty()) return true

        val before = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val existing = before.split(':').filter { it.isNotBlank() }
        val missing = desired.filter { !isComponentPresent(it, existing) }.toSet()
        if (missing.isEmpty()) {
            Log.i(TAG, "restoreA11y no-op: desired components already present (before='$before')")
            return true
        }

        val merged = (existing + missing).joinToString(":")
        val script =
            "settings put secure enabled_accessibility_services '$merged' && settings put secure accessibility_enabled 1"
        return try {
            val proc = newShizukuProcess(arrayOf("sh", "-c", script)) ?: return false
            val exit = proc.waitFor()
            val after = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val afterEntries = after.split(':').filter { it.isNotBlank() }
            val landed = desired.all { isComponentPresent(it, afterEntries) }
            val logMsg =
                "restoreA11y exit=$exit landed=$landed missing=$missing before='$before' merged='$merged' after='$after'"
            if (landed) {
                Log.i(TAG, logMsg)
            } else {
                Log.w(TAG, logMsg)
            }
            exit == 0 && landed
        } catch (e: Throwable) {
            Log.w(TAG, "restoreA11y failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Form-tolerant membership: a desired component like `pkg/com.full.Class`
     * matches both the canonical entry `pkg/com.full.Class` and the relative
     * entry `pkg/.Class` (where `.Class` resolves to `pkg.Class` once joined
     * with the package). Likewise the other direction. Anything that doesn't
     * have a `/` separator falls back to exact equality.
     */
    private fun isComponentPresent(component: String, existing: Collection<String>): Boolean {
        if (existing.contains(component)) return true
        val parts = component.split('/', limit = 2)
        if (parts.size != 2) return false
        val pkg = parts[0]
        val cls = parts[1]
        val alternative = when {
            cls.startsWith(".") -> "$pkg/$pkg$cls"
            cls.startsWith("$pkg.") -> "$pkg/." + cls.removePrefix("$pkg.")
            else -> null
        }
        return alternative != null && existing.contains(alternative)
    }

    /**
     * Bulk auto-grant every special permission Tutti needs, via shell commands
     * delivered through Shizuku. Skips permissions that are already granted, so
     * repeated invocations are cheap and idempotent.
     *
     * Order matters: POST_NOTIFICATIONS is granted first so the auto-grant
     * notification can be posted afterwards. Accessibility is granted last via
     * `restoreAccessibilityServices` (so the merge-with-existing logic
     * preserves any third-party services already in the list).
     *
     * Returns an [AutoGrantResult] enumerating each permission's pre/post state.
     * When Shizuku is not READY, returns immediately with an empty result and a
     * `Log.d` line identifying the blocker.
     */
    fun autoGrantAllPermissions(context: Context): AutoGrantResult {
        val currentStatus = status(context)
        if (currentStatus != Status.READY) {
            Log.d(TAG, "autoGrant skipped: status=$currentStatus")
            return AutoGrantResult(emptyList(), currentStatus)
        }
        val pkg = context.packageName
        val states = mutableListOf<PermissionState>()

        // 1. POST_NOTIFICATIONS — granted first so notify() is allowed afterwards.
        states += grantViaShell(
            key = "POST_NOTIFICATIONS",
            displayName = "通知发送",
            probe = { isPostNotificationsGranted(context) },
            cmd = "pm grant $pkg android.permission.POST_NOTIFICATIONS",
        )

        // 2. SYSTEM_ALERT_WINDOW — floating ball overlay.
        states += grantViaShell(
            key = "SYSTEM_ALERT_WINDOW",
            displayName = "悬浮窗",
            probe = { Settings.canDrawOverlays(context) },
            cmd = "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        )

        // 3. WRITE_SETTINGS — NetEase landscape rotation workaround.
        states += grantViaShell(
            key = "WRITE_SETTINGS",
            displayName = "修改系统设置",
            probe = { Settings.System.canWrite(context) },
            cmd = "appops set $pkg WRITE_SETTINGS allow",
        )

        // 4. PACKAGE_USAGE_STATS — double-click floating-ball navigation.
        states += grantViaShell(
            key = "PACKAGE_USAGE_STATS",
            displayName = "应用使用情况",
            probe = { isUsageStatsGranted(context) },
            cmd = "appops set $pkg GET_USAGE_STATS allow",
        )

        // 5. NotificationListener (MediaMonitorService) — auto-advance detection.
        val listenerCn = ComponentName(context, MediaMonitorService::class.java).flattenToString()
        states += grantViaShell(
            key = "NOTIFICATION_LISTENER",
            displayName = "通知访问",
            probe = { MediaMonitorService.isEnabled(context) },
            cmd = "cmd notification allow_listener $listenerCn",
        )

        // 6. HyperOS-specific MIUIOP(10021) — controls the "想要打开 XX 音乐"
        // background-activity-launch dialog that fires when Tutti launches a
        // music app's deep link. Not in the public appops vocabulary; the
        // probe via `appops get <pkg> 10021` returns "MIUIOP(10021): allow"
        // on HyperOS and an error / no-MIUIOP output on stock Android — so
        // the entry self-skips on non-HyperOS devices.
        val miuiBalCurrent = probeMiuiOp(context.packageName, "10021")
        if (miuiBalCurrent != null) {
            val alreadyAllow = miuiBalCurrent == "allow"
            val attempted = !alreadyAllow
            val after: Boolean
            if (alreadyAllow) {
                after = true
            } else {
                val proc = newShizukuProcess(
                    arrayOf("appops", "set", context.packageName, "10021", "allow")
                )
                val exit = proc?.waitFor() ?: -1
                Thread.sleep(150)
                after = probeMiuiOp(context.packageName, "10021") == "allow"
                if (exit != 0 || !after) {
                    Log.w(
                        TAG,
                        "autoGrant HYPEROS_BAL failed: exit=$exit before=$miuiBalCurrent after=$after"
                    )
                }
            }
            states += PermissionState(
                key = "HYPEROS_BAL",
                displayName = "应用启动",
                alreadyGranted = alreadyAllow,
                grantAttempted = attempted,
                grantSucceeded = after,
            )
        }

        // 7. Accessibility — re-use restoreAccessibilityServices so the
        // existing setting (other apps' services) is preserved.
        val a11yCn = "$pkg/${PlayerAccessibilityService::class.java.canonicalName}"
        val a11yBefore = PlayerAccessibilityService.isEnabled(context)
        val a11yAttempted = !a11yBefore
        val a11yAfter = if (a11yBefore) {
            true
        } else {
            restoreAccessibilityServices(context, setOf(a11yCn))
            Thread.sleep(150)
            PlayerAccessibilityService.isEnabled(context)
        }
        states += PermissionState(
            key = "ACCESSIBILITY",
            displayName = "无障碍服务",
            alreadyGranted = a11yBefore,
            grantAttempted = a11yAttempted,
            grantSucceeded = a11yAfter,
        )

        val result = AutoGrantResult(states, currentStatus)
        Log.i(
            TAG,
            "autoGrant newly=${result.newlyGranted.map { it.key }} " +
                "alreadyGranted=${states.count { it.alreadyGranted }} " +
                "failed=${result.failed.map { it.key }} total=${states.size}"
        )
        return result
    }

    /**
     * Probe → if missing, run the shell command via Shizuku → wait ~150 ms for
     * the system to propagate the change → re-probe → record the result.
     */
    private fun grantViaShell(
        key: String,
        displayName: String,
        probe: () -> Boolean,
        cmd: String,
    ): PermissionState {
        val before = probe()
        if (before) {
            return PermissionState(
                key = key,
                displayName = displayName,
                alreadyGranted = true,
                grantAttempted = false,
                grantSucceeded = true,
            )
        }
        val proc = newShizukuProcess(arrayOf("sh", "-c", cmd))
        val exit = proc?.waitFor() ?: -1
        Thread.sleep(150)
        val after = probe()
        if (exit != 0 || !after) {
            Log.w(TAG, "autoGrant $key failed: cmd='$cmd' exit=$exit before=$before after=$after")
        }
        return PermissionState(
            key = key,
            displayName = displayName,
            alreadyGranted = false,
            grantAttempted = true,
            grantSucceeded = after,
        )
    }

    private fun isPostNotificationsGranted(context: Context): Boolean {
        // POST_NOTIFICATIONS only exists as a runtime permission on API 33+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.POST_NOTIFICATIONS"
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isUsageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Probe a HyperOS / MIUI-specific opcode by its integer ID via `appops
     * get`. Returns "allow" / "ignore" / "deny" / "default", or `null` if
     * the opcode does not exist on this ROM (so we skip the entry on stock
     * Android). Requires Shizuku to be `READY`.
     *
     * The output format on HyperOS is `MIUIOP(<id>): <mode>`; on stock
     * Android the same command emits an error or `No operations`.
     */
    private fun probeMiuiOp(pkg: String, opNumber: String): String? {
        return try {
            val proc = newShizukuProcess(arrayOf("appops", "get", pkg, opNumber))
                ?: return null
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (!out.contains("MIUIOP")) return null
            out.substringAfterLast(":").trim().lowercase()
        } catch (e: Throwable) {
            Log.d(TAG, "probeMiuiOp $opNumber failed: ${e.message}")
            null
        }
    }

    /**
     * Reflectively call [Shizuku.newProcess]. The method is annotated
     * `@RestrictTo(LIBRARY_GROUP_PREFIX)`, so direct calls trip a lint error;
     * reflection sidesteps that and also future-proofs against minor signature
     * changes between Shizuku-API versions.
     */
    private fun newShizukuProcess(cmd: Array<String>): java.lang.Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? java.lang.Process
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku.newProcess reflection failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}

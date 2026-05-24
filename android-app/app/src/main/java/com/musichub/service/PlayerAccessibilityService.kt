package com.musichub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Persistent record of whether the user has ever granted PlayerAccessibilityService.
 * AOSP's `am force-stop` handler silently revokes the grant on the next
 * AccessibilityManagerService sweep, so we need a separate memory of "the user
 * said yes once" to know whether to attempt Shizuku-mediated restoration later.
 * Cleared only when the user clears app data.
 */
object AccessibilityGrantStore {
    internal const val A11Y_PREFS = "musichub_a11y"
    internal const val PREF_GRANTED = "accessibility_granted"

    fun setGranted(context: Context) {
        context.getSharedPreferences(A11Y_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_GRANTED, true).apply()
    }

    fun wasGranted(context: Context): Boolean =
        context.getSharedPreferences(A11Y_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_GRANTED, false)
}

/**
 * Accessibility service that can click the mini player bar in QQ Music
 * to navigate to the full player/lyrics page.
 *
 * This is needed because QQ Music doesn't expose any deep link or intent
 * to navigate to the player/lyrics page. The only way is to tap the mini
 * player bar at the bottom of the QQ Music screen.
 */
class PlayerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClick = false
    private var retryCount = 0
    private var clickCount = 0  // Track how many times we've clicked
    private var foundUIElement = false  // Track if we found the actual UI element
    private var lastFoundCard = false  // Track if card was found in last attempt
    private var lastFoundMiniPlayer = false  // Track if mini player was found in last attempt
    private var hasDumped = false  // Prevent repeated UI tree dumps per click request

    // Broadcast receiver to handle click requests from other components
    private val clickRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_CLICK) {
                Log.d(TAG, "Received click request via broadcast")
                requestClickMiniPlayer()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Record the grant the instant the system binds us. Closes the gap where
        // the user enables the service while the app process is alive — in that
        // flow Application.onCreate never re-fires to observe the grant, so the
        // pref would otherwise stay false and the post-force-stop restore path
        // would never run.
        AccessibilityGrantStore.setGranted(applicationContext)
        instance = this
        Log.d(TAG, "PlayerAccessibilityService connected")

        // Register broadcast receiver
        val filter = IntentFilter(ACTION_REQUEST_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clickRequestReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(clickRequestReceiver, filter)
        }

        // Runtime serviceInfo mirrors accessibility_service_config.xml. This
        // service handles three unrelated jobs across the music apps + the
        // HyperOS Security Center:
        //   - QQ Music: receive TYPE_WINDOW_STATE/CONTENT_CHANGED, then use
        //     canRetrieveWindowContent + canPerformGestures to tap the
        //     mini-player bar and open the lyrics page.
        //   - All four apps in background-launch mode: receive
        //     TYPE_WINDOWS_CHANGED so we can re-fire ShizukuLauncher.triggerResize
        //     when HyperOS pulls our freeform task back on-screen.
        //   - HyperOS Security Center wake-path dialog (`ConfirmStartActivity`):
        //     auto-tap "始终允许" so the per-(Tutti, target) confirmation only
        //     interrupts the user once and never again. This handles the
        //     "应用关联启动" permission via Shizuku-restored accessibility.
        // FLAG_RETRIEVE_INTERACTIVE_WINDOWS is required for TYPE_WINDOWS_CHANGED
        // event delivery.
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            packageNames = ShizukuLauncher.accessibilityListenPackages().toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Window-bounds-changed events fire when HyperOS pulls a freeform task
        // back on-screen during a home-gesture/recents transition. Re-fire the
        // resize immediately so the music-app stays sized down off-screen.
        // SPEC: freeform-multi-task-hide — ripple to ALL music-app packages
        // on any music-app window event. The TYPE_WINDOWS_CHANGED event only
        // carries the package whose window animated; stale prior-session
        // freeform tasks that sit visible-and-unchanging never fire it on
        // their own, so we proactively re-hide every music app whenever one
        // of them moves. Per-pkg 200 ms throttle in ShizukuLauncher absorbs
        // the spam.
        //
        // We also listen to com.miui.home (HyperOS launcher) events: on a
        // HOME gesture HyperOS rescues an off-screen freeform task into its
        // `miui_multi_sence` sidebar widget. The launcher receives focus
        // first; the music-app task's own bounds may not change so it
        // wouldn't fire a music-app TYPE_WINDOWS_CHANGED. Catching launcher
        // events ensures we re-resize even in that path.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val isMusicApp = pkg != null && pkg in ShizukuLauncher.musicAppPackages()
            val isLauncher = pkg == ShizukuLauncher.MIUI_HOME_PACKAGE
            val isSystemUi = pkg == ShizukuLauncher.ANDROID_SYSTEMUI_PACKAGE
            if (isMusicApp || isLauncher) {
                ShizukuLauncher.triggerResizeForAllMusicApps()
            }
            // Launcher and system-UI events fire during system gestures
            // (HOME swipe, slow swipe-up-hold for Recent apps). Schedule a
            // debounced one-shot multi_sence-dismissal nudge — each event
            // resets the timer, so the nudge only fires once the gesture
            // burst has settled. This keeps the floating ball perfectly
            // idle during the gesture (no input dispatcher competition)
            // and only dismisses the widget after the user has finished.
            if (isLauncher || isSystemUi) {
                FloatingWindowService.getInstance()?.scheduleNudgeAfterGesture(800L)
            }
        }

        // HyperOS Security Center wake-path dialog. Tutti's startActivity
        // call into a music app triggers `com.miui.wakepath.ui.ConfirmStartActivity`
        // — a per-(source, target) confirmation. The proper HyperOS allowlist
        // sits behind `miui.permission.READ_AND_WIRTE_PERMISSION_MANAGER`
        // (signature-only), so neither Shizuku nor anything short of root can
        // pre-allow programmatically. The dialog is the user's intended grant
        // surface; they tap "始终允许" once per target and HyperOS remembers.
        //
        // Opt-in workaround: when the user explicitly enables the
        // `auto_confirm_wakepath` preference, this service taps "始终允许"
        // for them. Default is OFF — leaving manual confirmation as the
        // honest UX.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event.packageName == ShizukuLauncher.MIUI_SECURITY_CENTER_PACKAGE) {
            val cls = event.className?.toString().orEmpty()
            if (cls.contains("ConfirmStartActivity") && isAutoConfirmWakePathEnabled()) {
                handler.postDelayed({ autoConfirmWakePath() }, 250)
            }
        }

        if (!pendingClick) return

        if (event.packageName == QQMUSIC_PACKAGE) {
            // Try to find and click the mini player bar
            handler.postDelayed({
                if (pendingClick) {
                    tryClickMiniPlayer()
                }
            }, 300)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "PlayerAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(clickRequestReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        Log.d(TAG, "PlayerAccessibilityService destroyed")
    }

    /**
     * Auto-tap "始终允许" on HyperOS's wake-path confirmation dialog. The
     * dialog is launched as `com.miui.wakepath.ui.ConfirmStartActivity` and
     * has three buttons: 本次允许 / 始终允许 / 拒绝. We click 始终允许 so
     * HyperOS records the (Tutti, target) pair in its allowlist and the
     * dialog stops firing for that pair on subsequent launches.
     *
     * Safety: only fires when the dialog text actually references Tutti
     * (the dialog body reads "Tutti 想要打开 XXX"). This guards against
     * accidentally clicking "always allow" on some other system dialog that
     * happens to surface in the Security Center package.
     */
    private fun autoConfirmWakePath() {
        val rootNode = rootInActiveWindow ?: run {
            Log.d(TAG, "Wake-path auto-confirm: rootInActiveWindow null")
            return
        }
        try {
            // Verify the dialog actually belongs to Tutti's launch by
            // looking for "Tutti" or the package name in the body text.
            val containsTuttiLabel = nodeTextContainsAny(
                rootNode,
                listOf("Tutti", "管乐", "Music Hub", packageName)
            )
            if (!containsTuttiLabel) {
                Log.d(TAG, "Wake-path dialog present but no Tutti reference; skipping auto-tap")
                return
            }
            val allowAlwaysNodes = rootNode.findAccessibilityNodeInfosByText("始终允许")
            val button = allowAlwaysNodes.firstOrNull { node ->
                node.className?.toString()?.contains("Button") == true || node.isClickable
            } ?: allowAlwaysNodes.firstOrNull()
            if (button == null) {
                Log.w(TAG, "Wake-path dialog: '始终允许' button not found")
                return
            }
            val rect = android.graphics.Rect()
            button.getBoundsInScreen(rect)
            val x = rect.exactCenterX()
            val y = rect.exactCenterY()
            if (performGestureClick(x, y)) {
                Log.i(TAG, "Auto-tapped 始终允许 on HyperOS wake-path dialog at ($x, $y)")
            } else if (button.isClickable) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Auto-clicked 始终允许 via ACTION_CLICK (gesture rejected)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "autoConfirmWakePath failed: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    private fun nodeTextContainsAny(node: AccessibilityNodeInfo, needles: List<String>): Boolean {
        val text = node.text?.toString().orEmpty()
        if (needles.any { text.contains(it, ignoreCase = true) }) return true
        val desc = node.contentDescription?.toString().orEmpty()
        if (needles.any { desc.contains(it, ignoreCase = true) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeTextContainsAny(child, needles)) return true
        }
        return false
    }

    private fun isAutoConfirmWakePathEnabled(): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_AUTO_CONFIRM_WAKEPATH, false)

    /**
     * Request clicking the QQ Music mini player bar.
     * Call this after launching a QQ Music deep link in foreground mode.
     */
    fun requestClickMiniPlayer() {
        pendingClick = true
        retryCount = 0
        clickCount = 0
        foundUIElement = false
        lastFoundCard = false
        lastFoundMiniPlayer = false
        hasDumped = false
        Log.d(TAG, "Mini player click requested")

        // Wait 2 seconds for QQ Music card animation to finish before first attempt
        handler.postDelayed({
            scheduleRetry()
        }, 2000L)

        // Timeout: give up after 10 seconds
        handler.postDelayed({
            if (pendingClick) {
                pendingClick = false
                Log.w(TAG, "Mini player click timed out after $retryCount retries, $clickCount clicks")
            }
        }, 10000L)
    }

    private fun scheduleRetry() {
        handler.postDelayed({
            if (pendingClick) {
                retryCount++
                val clicked = tryClickMiniPlayer()
                if (clicked) {
                    clickCount++
                    Log.i(TAG, "Click successful, total clicks: $clickCount")
                }
                // Stop only if we found UI element AND it's no longer visible (page changed)
                // OR if we've exceeded max retries
                if (retryCount < MAX_RETRIES) {
                    scheduleRetry()
                } else {
                    pendingClick = false
                    Log.i(TAG, "Finished after $retryCount retries, $clickCount clicks")
                }
            }
        }, RETRY_INTERVAL_MS)
    }

    private fun tryClickMiniPlayer(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.d(TAG, "rootInActiveWindow is null (retry $retryCount)")
            return false
        }

        try {
            // Strategy 1: Try the music card - appears during song transitions
            val cardNodes = ID_MUSIC_CARD_CANDIDATES.firstNotNullOfOrNull { id ->
                val nodes = rootNode.findAccessibilityNodeInfosByViewId("$QQMUSIC_PACKAGE:id/$id")
                if (nodes.isNotEmpty()) nodes else null
            } ?: emptyList()
            Log.d(TAG, "Found ${cardNodes.size} card nodes (candidates=$ID_MUSIC_CARD_CANDIDATES)")
            if (cardNodes.isNotEmpty()) {
                val node = cardNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                Log.d(TAG, "Music card - clickable: ${node.isClickable}, enabled: ${node.isEnabled}, bounds: $rect, size: ${rect.width()}x${rect.height()}")

                if (rect.width() > 50 && rect.height() > 50) {
                    lastFoundCard = true
                    foundUIElement = true

                    // Click in the title-row area: cap the vertical offset so
                    // we land above the SeekBar/controls on tall expanded
                    // music cards (c85 is ~610 px tall on a 1080×2400 device;
                    // 1/4 of that would land on the SeekBar at y≈1905, which
                    // intercepts the touch instead of bubbling to the
                    // clickable card and navigating to the player page).
                    val x = (rect.left + rect.right) / 2f
                    val y = rect.top + kotlin.math.min(rect.height() / 4f, 100f)

                    if (performGestureClick(x, y)) {
                        Log.i(TAG, "Clicked music card via gesture at ($x, $y) (retry $retryCount)")
                        return true
                    }
                }
            } else if (lastFoundCard) {
                // Card was present before but now disappeared - page changed!
                Log.i(TAG, "Music card disappeared, page changed successfully")
                pendingClick = false
                return true
            }

            // Strategy 2: Try the mini player container (multiple candidate IDs)
            var miniPlayerNodes: List<AccessibilityNodeInfo> = emptyList()
            for (id in ID_MINI_PLAYER_CANDIDATES) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId("$QQMUSIC_PACKAGE:id/$id")
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "Found ${nodes.size} mini-player nodes via id=$id")
                    miniPlayerNodes = nodes
                    break
                }
            }
            if (miniPlayerNodes.isNotEmpty()) {
                val node = miniPlayerNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                if (rect.width() > 100 && rect.height() > 100) {
                    lastFoundMiniPlayer = true
                    foundUIElement = true
                    // Use gesture click on the album art area (left side of mini player).
                    // performAction(ACTION_CLICK) on the ViewGroup container is received
                    // by QQ Music but doesn't trigger navigation to the player page —
                    // only a real touch event (gesture) opens it.
                    val x = rect.left + (rect.height() / 2f)  // center of album art area
                    val y = (rect.top + rect.bottom) / 2f
                    if (performGestureClick(x, y)) {
                        Log.i(TAG, "Clicked mini player via gesture at ($x, $y) (retry $retryCount)")
                        return true
                    }
                } else {
                    Log.i(TAG, "Mini player too small, assuming player page already open")
                    pendingClick = false
                    return true
                }
            } else if (lastFoundMiniPlayer) {
                // Mini player was present before but now disappeared - player page opened!
                Log.i(TAG, "Mini player disappeared, player page opened successfully")
                pendingClick = false
                return true
            }

            // Dump UI tree when ID-based strategies fail, for diagnosing ID changes
            if (cardNodes.isEmpty() && miniPlayerNodes.isEmpty() && retryCount >= 2 && !hasDumped) {
                hasDumped = true
                dumpUITree()
            }

            // Strategy 2b: Content-desc-based detection. QQ Music's mini-player
            // has stable Chinese labels (歌曲队列 / 播放 / 暂停) on its
            // ImageView children — these survive obfuscated-ID churn between
            // releases. Locate them near the bottom of the screen, then click
            // their nearest clickable ancestor's left-side song-info area.
            if (cardNodes.isEmpty() && miniPlayerNodes.isEmpty()) {
                if (tryContentDescMiniPlayerClick(rootNode)) {
                    return true
                }
            }

            // Strategy 3: Heuristic - find a bottom-of-screen bar by position and size
            if (cardNodes.isEmpty() && miniPlayerNodes.isEmpty() && retryCount >= 2) {
                if (tryHeuristicClick(rootNode)) {
                    return true
                }
            }

            // Strategy 4: If no elements found after retries, assume already on player page
            if (retryCount > 3) {
                Log.i(TAG, "No clickable elements found after $retryCount retries, assuming player page already open")
                pendingClick = false
                return true
            }

            // Earlier retries: nothing actionable found yet. Wait for the next
            // scheduled tick — do NOT fall back to hardcoded coordinates. The
            // old hardcoded fallback at (554, 2117) lands inside a clickable
            // song row on QQ Music's current singer-detail page, causing the
            // wrong song to play and a title-mismatch skip.
            Log.d(TAG, "No mini-player elements found (retry $retryCount), waiting for next tick")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to click mini player: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }

        return false
    }

    /**
     * Find QQ Music's mini-player by stable Chinese content-desc strings on
     * its child ImageViews (歌曲队列 / 播放 / 暂停). These labels are
     * accessibility/i18n strings that don't change across releases, unlike
     * the obfuscated resource IDs.
     *
     * Once a marker is located near the bottom of the screen, walk up to find
     * its nearest clickable ancestor (the mini-player title/cover area whose
     * click navigates to the full player page) and tap that.
     */
    private fun tryContentDescMiniPlayerClick(rootNode: AccessibilityNodeInfo): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val bottomThreshold = (screenHeight * 0.75).toInt()  // bottom 25%

        val markers = mutableListOf<AccessibilityNodeInfo>()
        for (desc in MINI_PLAYER_DESC_MARKERS) {
            findNodesByContentDescDeep(rootNode, desc, markers)
        }
        if (markers.isEmpty()) {
            Log.d(TAG, "Content-desc strategy: no mini-player markers found")
            return false
        }

        // Keep only markers in the bottom region of the screen
        val markerRect = android.graphics.Rect()
        val bottomMarkers = markers.filter { m ->
            m.getBoundsInScreen(markerRect)
            markerRect.top >= bottomThreshold
        }
        if (bottomMarkers.isEmpty()) {
            Log.d(TAG, "Content-desc strategy: ${markers.size} markers found but none in bottom region")
            return false
        }

        // Use the first marker's row to anchor the mini-player vertical span,
        // then search for a wide clickable element on the same row that is to
        // the LEFT of the marker (the song-info area).
        val anchor = bottomMarkers[0]
        val anchorRect = android.graphics.Rect()
        anchor.getBoundsInScreen(anchorRect)
        val rowCenterY = (anchorRect.top + anchorRect.bottom) / 2

        // Walk up to find a clickable ancestor — this is what we want to tap
        // (avoids tapping the 歌曲队列 / 播放 button itself which would change
        // the queue/play state instead of navigating to the player page).
        val clickTarget = findMiniPlayerClickTarget(rootNode, anchorRect, rowCenterY)
        if (clickTarget == null) {
            Log.w(TAG, "Content-desc strategy: marker found but no clickable song-info area on the same row")
            return false
        }

        val targetRect = android.graphics.Rect()
        clickTarget.getBoundsInScreen(targetRect)
        // Click near the TOP of the target instead of the center. The center
        // lands on the SeekBar / progress region on tall music cards (the
        // expanded c85 card is ~600 px tall, and its SeekBar sits in the
        // middle — taps there get consumed as seek gestures instead of
        // bubbling to the clickable card). top + 70 keeps us in the title /
        // album-cover row for both compact mini-players (~170 px tall) and
        // expanded music cards (~600 px tall).
        val x = (targetRect.left + targetRect.right) / 2f
        val y = targetRect.top + kotlin.math.min(targetRect.height() / 4f, 100f)
        lastFoundMiniPlayer = true
        foundUIElement = true
        if (performGestureClick(x, y)) {
            Log.i(TAG, "Content-desc strategy: clicked mini-player at ($x, $y), target bounds=$targetRect")
            return true
        }
        return false
    }

    /**
     * Find the clickable song-info area of the mini-player. Searches the tree
     * for a wide clickable node whose bounds overlap the anchor row vertically
     * and sit to the LEFT of the anchor (歌曲队列/播放 buttons live on the
     * right side; the song-info clickable region is on the left).
     */
    private fun findMiniPlayerClickTarget(
        root: AccessibilityNodeInfo,
        anchorRect: android.graphics.Rect,
        rowCenterY: Int
    ): AccessibilityNodeInfo? {
        val displayMetrics = resources.displayMetrics
        val minWidth = (displayMetrics.widthPixels * 0.4).toInt()
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        collectClickableInRow(root, rowCenterY, minWidth, anchorRect, candidates)
        // Prefer the widest candidate that is to the left of the anchor
        return candidates.maxByOrNull { it.second.width() }?.first
    }

    private fun collectClickableInRow(
        node: AccessibilityNodeInfo,
        rowCenterY: Int,
        minWidth: Int,
        anchorRect: android.graphics.Rect,
        out: MutableList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>
    ) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (node.isClickable
            && rect.top <= rowCenterY && rect.bottom >= rowCenterY
            && rect.width() >= minWidth
            && rect.left < anchorRect.left
        ) {
            out.add(node to android.graphics.Rect(rect))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableInRow(child, rowCenterY, minWidth, anchorRect, out)
        }
    }

    private fun findNodesByContentDescDeep(
        node: AccessibilityNodeInfo,
        desc: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.contentDescription?.toString() == desc) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByContentDescDeep(child, desc, out)
        }
    }

    /**
     * Heuristic fallback: find a mini-player-like bar at the bottom of the screen
     * by structural properties (position, size) rather than resource ID.
     */
    private fun tryHeuristicClick(rootNode: AccessibilityNodeInfo): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val bottomThreshold = screenHeight * 0.8  // bottom 20%
        val minWidth = screenWidth * 0.8
        val minHeight = (40 * displayMetrics.density).toInt()  // 40dp

        var bestCandidate: AccessibilityNodeInfo? = null
        var bestRect = android.graphics.Rect()

        // Search top-level children for a wide bar near the bottom
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i) ?: continue
            val rect = android.graphics.Rect()
            child.getBoundsInScreen(rect)

            // Skip elements that are too small or not in the bottom area
            if (rect.top < bottomThreshold) continue
            if (rect.width() < minWidth) continue
            if (rect.height() < minHeight) continue

            // Skip navigation-like elements by resource ID
            val resId = child.viewIdResourceName ?: ""
            if (resId.contains("nav") || resId.contains("tab") || resId.contains("bottom_bar")) {
                continue
            }

            // Prefer the candidate closest to the bottom but not the very bottom edge (nav bar)
            if (bestCandidate == null || rect.top > bestRect.top) {
                bestCandidate = child
                bestRect = rect
            }
        }

        if (bestCandidate != null) {
            // Click the left side (album art area)
            val x = bestRect.left + (bestRect.height() / 2f)
            val y = (bestRect.top + bestRect.bottom) / 2f
            Log.i(TAG, "Heuristic: found candidate bar at $bestRect (id=${bestCandidate.viewIdResourceName}), clicking at ($x, $y)")
            if (performGestureClick(x, y)) {
                foundUIElement = true
                return true
            }
        }

        return false
    }

    /**
     * Dump the QQ Music UI tree to logcat for discovering current resource IDs.
     * Logs top-level nodes and their immediate children, breadth-first, capped at 50 nodes.
     */
    private fun dumpUITree() {
        val rootNode = rootInActiveWindow ?: return
        try {
            Log.w(TAG, "=== UI TREE DUMP (ID-based strategies failed) ===")
            var count = 0
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()  // node, depth
            queue.add(rootNode to 0)
            while (queue.isNotEmpty() && count < 50) {
                val (node, depth) = queue.removeFirst()
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val indent = "  ".repeat(depth)
                Log.w(TAG, "${indent}[${count}] id=${node.viewIdResourceName}, class=${node.className}, " +
                    "bounds=${rect}, clickable=${node.isClickable}, " +
                    "desc=${node.contentDescription}, text=${node.text}, " +
                    "children=${node.childCount}")
                count++
                // Only descend to depth 2 (top-level + immediate children)
                if (depth < 2) {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        queue.add(child to depth + 1)
                    }
                }
            }
            Log.w(TAG, "=== END UI TREE DUMP ($count nodes) ===")
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping UI tree: ${e.message}")
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    private fun performGestureClick(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return false
        }

        val path = android.graphics.Path()
        path.moveTo(x, y)

        val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
        val strokeDescription = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, 100  // 100ms tap duration
        )
        gestureBuilder.addStroke(strokeDescription)

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "Gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "Gesture cancelled at ($x, $y)")
            }
        }

        return dispatchGesture(gestureBuilder.build(), callback, handler)
    }

    /**
     * Dismiss QQ Music error/no-copyright dialog.
     * Tries to find and click the close button, falls back to GLOBAL_ACTION_BACK.
     */
    fun dismissErrorDialog() {
        // Cancel any pending mini player click attempts — they'll just hit the dialog
        cancelPendingClick()

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.d(TAG, "dismissErrorDialog: rootInActiveWindow is null, sending BACK")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        try {
            val closeNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "$QQMUSIC_PACKAGE:id/$ID_CLOSE_BTN"
            )
            if (closeNodes.isNotEmpty()) {
                val node = closeNodes[0]
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val x = (rect.left + rect.right) / 2f
                val y = (rect.top + rect.bottom) / 2f
                Log.i(TAG, "Found close_btn at $rect, clicking at ($x, $y)")
                performGestureClick(x, y)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                closeNodes.forEach { it.recycle() }
            } else {
                Log.d(TAG, "close_btn not found, sending BACK")
            }
            // Always send BACK as belt-and-suspenders — gesture clicks on the close_btn
            // are unreliable and the dialog can persist across song transitions
            performGlobalAction(GLOBAL_ACTION_BACK)
            // Verify dismissal after a short delay; send another BACK if still present
            handler.postDelayed({
                val checkRoot = rootInActiveWindow ?: return@postDelayed
                try {
                    val stillPresent = checkRoot.findAccessibilityNodeInfosByViewId(
                        "$QQMUSIC_PACKAGE:id/$ID_CLOSE_BTN"
                    )
                    if (stillPresent.isNotEmpty()) {
                        Log.w(TAG, "Dialog still present after first BACK, sending another BACK")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        stillPresent.forEach { it.recycle() }
                    } else {
                        Log.d(TAG, "Dialog dismissed successfully")
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    checkRoot.recycle()
                }
            }, 300L)
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing dialog: ${e.message}")
            performGlobalAction(GLOBAL_ACTION_BACK)
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    /**
     * Cancel any pending mini player click request.
     */
    private fun cancelPendingClick() {
        if (pendingClick) {
            pendingClick = false
            handler.removeCallbacksAndMessages(null)
            Log.d(TAG, "Cancelled pending mini player click")
        }
    }

    companion object {
        private const val TAG = "PlayerA11yService"
        private const val QQMUSIC_PACKAGE = "com.tencent.qqmusic"
        private const val RETRY_INTERVAL_MS = 500L
        private const val MAX_RETRIES = 16  // 16 * 500ms = 8 seconds
        private const val MAX_TIMEOUT_MS = 8000L
        const val ACTION_REQUEST_CLICK = "com.musichub.action.REQUEST_CLICK_MINIPLAYER"
        const val PREF_AUTO_CONFIRM_WAKEPATH = "auto_confirm_wakepath"

        // QQ Music obfuscated resource IDs — update these when QQ Music changes them.
        // These IDs change with every QQ Music release; treat them as best-effort
        // shortcuts. The content-desc-based strategy below is the reliable path.
        // - Music card (big "now playing" widget on the home page): c85 (current), cxy (older)
        // - Mini-player (compact bar on singer/playlist pages): h58/h51 (current), jrh/jro (older)
        private val ID_MUSIC_CARD_CANDIDATES = listOf("c85", "cxy")
        private val ID_MINI_PLAYER_CANDIDATES = listOf("h58", "h51", "jrh", "jro")
        private const val ID_CLOSE_BTN = "close_btn"

        // Stable content-desc strings on QQ Music's mini-player. The ImageView
        // children of the mini-player container carry these descriptions in
        // every recent QQ Music release — searching by content-desc is robust
        // against obfuscated-ID churn.
        private val MINI_PLAYER_DESC_MARKERS = listOf("歌曲队列", "播放", "暂停")

        @Volatile
        private var instance: PlayerAccessibilityService? = null

        fun getInstance(): PlayerAccessibilityService? = instance

        /**
         * Dismiss QQ Music error dialog if the accessibility service is running.
         * @return true if the service was available and dismissal was attempted
         */
        fun dismissQQMusicDialog(): Boolean {
            val service = instance
            if (service != null) {
                service.dismissErrorDialog()
                return true
            }
            Log.d(TAG, "Cannot dismiss dialog: accessibility service not running")
            return false
        }

        /**
         * Request clicking the mini player via broadcast.
         * This works even if getInstance() returns null, as long as the service is running.
         */
        fun requestClickViaBroadcast(context: Context) {
            val intent = Intent(ACTION_REQUEST_CLICK)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent click request broadcast")
        }

        /**
         * Check if the accessibility service is enabled.
         */
        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains("${context.packageName}/${PlayerAccessibilityService::class.java.canonicalName}")
        }

        /**
         * Open accessibility settings so the user can enable this service.
         */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

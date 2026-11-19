package com.example.processA

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.data.UiNodeSnapshot
import com.example.engine.AppCapabilityRegistry
import com.example.engine.AutonomousDialogResolver
import com.example.engine.DeferredToolRegistry
import com.example.engine.RandomSongCatalog
import com.example.engine.ScreenTextSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Process A Autonomous UI Node Scanner, Screenshot Capturer & Gesture Executor.
 * Scans ONLY real AccessibilityNodeInfo trees from rootInActiveWindow — zero simulated UI trees.
 */
class NovaAccessibilityService : AccessibilityService() {

    data class UiActOutcome(
        val success: Boolean,
        val message: String,
        val updatedNodes: List<UiNodeSnapshot>
    )

    private var floatingOverlay: FloatingRoutineOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "NovaAccessibilityService connected")
        if (RoutineRecorderManager.state.value.isFloatingVisible) {
            syncFloatingRoutineOverlay(true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            pendingWindowSignal?.complete(Unit)
        }

        if (!RoutineRecorderManager.isRecording()) return
        if (RoutineRecorderManager.isOverlaySuppressingCapture()) return
        if (isOverlayWindow(event.windowId)) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName || isImePackage(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val src = event.source
                val bounds = resolveNodeBoundsInScreen(src)
                val label = listOfNotNull(
                    src?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    src?.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    event.text?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() },
                    findDescendantLabel(src, 2).takeIf { it.isNotBlank() }
                ).firstOrNull().orEmpty()
                val cx = if (bounds.width() > 0 && bounds.height() > 0) bounds.centerX().toFloat() else 0f
                val cy = if (bounds.width() > 0 && bounds.height() > 0) bounds.centerY().toFloat() else 0f
                if (cx > 0f || cy > 0f || label.isNotEmpty()) {
                    RoutineRecorderManager.onAccessibilityTapCaptured(cx, cy, label, pkg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val dm = resources.displayMetrics
                val midX = dm.widthPixels * 0.5f
                val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    event.scrollDeltaY
                } else 0
                val isScrollDownContent = when {
                    deltaY > 0 -> true
                    deltaY < 0 -> false
                    event.fromIndex > 0 -> true
                    else -> true
                }
                val startY = if (isScrollDownContent) dm.heightPixels * 0.68f else dm.heightPixels * 0.34f
                val endY = if (isScrollDownContent) dm.heightPixels * 0.32f else dm.heightPixels * 0.68f
                RoutineRecorderManager.onAccessibilityScrollCaptured(midX, startY, midX, endY, pkg)
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val typed = event.text?.joinToString("")?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    val src = event.source
                    val fieldLabel = listOfNotNull(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) src?.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null,
                        src?.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                        "Input"
                    ).first()
                    RoutineRecorderManager.onAccessibilityTextCaptured(fieldLabel, typed, pkg)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val isLaunchableApp = !pkg.startsWith("com.android.systemui") &&
                    !pkg.contains("launcher", ignoreCase = true) &&
                    !pkg.contains("permissioncontroller", ignoreCase = true) &&
                    runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
                if (isLaunchableApp) {
                    val appLabel = runCatching {
                        val ai = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(ai).toString()
                    }.getOrDefault(pkg.substringAfterLast('.'))
                    RoutineRecorderManager.onAccessibilityAppSwitched(appLabel, pkg)
                }
            }
        }
    }

    override fun onInterrupt() {
        abortAutomation("Accessibility service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isAutomationActive.get()) {
                abortAutomation("Hardware Volume-Down kill switch triggered")
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onDestroy() {
        floatingOverlay?.destroy()
        floatingOverlay = null
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    fun syncFloatingRoutineOverlay(show: Boolean) {
        if (show) {
            val overlay = floatingOverlay ?: FloatingRoutineOverlay(this).also { floatingOverlay = it }
            overlay.attach()
        } else {
            floatingOverlay?.detach()
        }
    }

    suspend fun <T> withFloatingOverlayPassthrough(block: suspend () -> T): T {
        val overlay = floatingOverlay ?: return block()
        overlay.setTouchableForPlayback(false)
        delay(35L)
        return try {
            block()
        } finally {
            overlay.setTouchableForPlayback(true)
        }
    }

    private fun isOverlayWindow(windowId: Int): Boolean {
        if (windowId < 0) return false
        return runCatching {
            windows.any { it.id == windowId && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        }.getOrDefault(false)
    }

    private fun resolveNodeBoundsInScreen(node: AccessibilityNodeInfo?): Rect {
        val rect = Rect()
        if (node == null) return rect
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) return rect
        var cur = node.parent
        var depth = 0
        while (cur != null && depth < 4) {
            cur.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return rect
            cur = cur.parent
            depth++
        }
        return rect
    }

    fun resolveCurrentForegroundPackage(): String? {
        val pkg = resolveBestRoot()?.packageName?.toString()
        return pkg?.takeIf { it.isNotBlank() && !isImePackage(it) }
    }

    fun findLabelAtPoint(x: Float, y: Float): String? {
        val root = resolveBestRoot() ?: return null
        val ix = x.toInt()
        val iy = y.toInt()
        var bestArea = Int.MAX_VALUE
        var bestLabel: String? = null
        var visits = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || visits >= MAX_DFS_VISITS) return
            visits++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0 && rect.contains(ix, iy)) {
                val own = listOfNotNull(
                    node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null
                ).firstOrNull()
                val label = own ?: if (node.isClickable) findDescendantLabel(node, 2).takeIf { it.isNotBlank() } else null
                if (!label.isNullOrBlank()) {
                    val area = rect.width() * rect.height()
                    if (area <= bestArea) {
                        bestArea = area
                        bestLabel = label.take(60)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }
        visit(root)
        return bestLabel
    }

    private fun isImePackage(pkg: String): Boolean =
        pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("keyboard", ignoreCase = true) ||
            pkg.contains("gboard", ignoreCase = true)

    private data class ScannedNodeCandidate(
        val node: AccessibilityNodeInfo,
        val label: String,
        val nodeType: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val visibleToUser: Boolean,
        val traversalOrder: Int
    )

    fun scanActiveScreenNodes(preferredPackage: String? = null): List<UiNodeSnapshot> {
        val root = resolveBestRoot(preferredPackage) ?: return emptyList()
        liveNodeCache.clear()
        val rawCandidates = ArrayList<ScannedNodeCandidate>(64)
        var visitCount = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || visitCount >= MAX_DFS_VISITS) return
            visitCount++

            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            val tooltip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.tooltipText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.paneTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            val resId = node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.trim()

            val ownLabel = listOfNotNull(
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                hint,
                tooltip,
                paneTitle,
                resId?.takeIf { it.isNotEmpty() && (node.isClickable || node.isEditable) }
            ).firstOrNull().orEmpty()
            val rawLabel = ownLabel.ifEmpty {
                if (node.isClickable) findDescendantLabel(node, maxDepth = 3) else ""
            }

            val simpleType = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val isInteresting = node.isClickable || node.isEditable || node.isCheckable || rawLabel.isNotEmpty()

            if (isInteresting) {
                val baseLabel = rawLabel.ifEmpty {
                    when {
                        node.isEditable -> "Text input ($simpleType)"
                        node.isCheckable -> "Toggle ($simpleType)"
                        node.isClickable -> "Button ($simpleType)"
                        else -> ""
                    }
                }
                if (baseLabel.isNotEmpty()) {
                    val stateSuffix = when {
                        node.isCheckable -> if (node.isChecked) " [ON]" else " [OFF]"
                        stateDesc != null && !baseLabel.contains(stateDesc, ignoreCase = true) -> " [$stateDesc]"
                        else -> ""
                    }
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val maxLen = if (node.isClickable || node.isEditable || node.isCheckable) 96 else 220
                    rawCandidates.add(
                        ScannedNodeCandidate(
                            node = node,
                            label = "$baseLabel$stateSuffix".take(maxLen),
                            nodeType = simpleType,
                            bounds = bounds,
                            clickable = node.isClickable || node.isCheckable,
                            editable = node.isEditable,
                            visibleToUser = node.isVisibleToUser,
                            traversalOrder = rawCandidates.size
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)
        if (rawCandidates.isEmpty()) return emptyList()

        // Filter out scrolled-away / invisible RecyclerView items when the OS reports visibility flags
        val hasVisibleFlags = rawCandidates.any { it.visibleToUser }
        val hasNonEmptyBounds = rawCandidates.any { it.bounds.width() > 0 && it.bounds.height() > 0 }
        val filtered = rawCandidates.filter { cand ->
            val visOk = !hasVisibleFlags || cand.visibleToUser || cand.editable
            val boundsOk = !hasNonEmptyBounds || (cand.bounds.width() > 0 && cand.bounds.height() > 0) || cand.editable
            visOk && boundsOk
        }.ifEmpty { rawCandidates }

        // Ensure editable and interactive buttons are never squeezed out by passive feed text
        val prioritized = if (filtered.size > MAX_SCAN_NODES) {
            val interactive = filtered.filter { it.editable || it.clickable }
            val passive = filtered.filterNot { it.editable || it.clickable }
            (interactive.take(MAX_SCAN_NODES) + passive).take(MAX_SCAN_NODES).sortedBy { it.traversalOrder }
        } else {
            filtered
        }

        val snapshots = ArrayList<UiNodeSnapshot>(prioritized.size)
        prioritized.forEachIndexed { idx, cand ->
            val shortId = "n%02d".format(idx + 1)
            liveNodeCache[shortId] = cand.node
            snapshots.add(
                UiNodeSnapshot(
                    id = shortId,
                    label = cand.label,
                    nodeType = cand.nodeType,
                    boundsLabel = "[${cand.bounds.left},${cand.bounds.top},${cand.bounds.right},${cand.bounds.bottom}]",
                    clickable = cand.clickable,
                    editable = cand.editable
                )
            )
        }
        return snapshots
    }

    private fun resolveBestRoot(preferredPackage: String? = null): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        val allWindows = runCatching { windows }.getOrDefault(emptyList())
        if (allWindows.isEmpty()) return activeRoot

        val appWindows = allWindows.filter { win ->
            win.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                win.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                win.root != null
        }

        if (!preferredPackage.isNullOrBlank()) {
            appWindows
                .filter { it.root?.packageName?.toString() == preferredPackage }
                .maxByOrNull { it.layer }
                ?.root
                ?.let { return it }
        }

        val activePkg = activeRoot?.packageName?.toString()
        val isActiveIme = activePkg != null && (
            activePkg.contains("inputmethod", ignoreCase = true) ||
                activePkg.contains("keyboard", ignoreCase = true) ||
                activePkg.contains("gboard", ignoreCase = true)
            )
        if (activeRoot != null && !isActiveIme && (preferredPackage.isNullOrBlank() || activePkg != packageName)) {
            return activeRoot
        }

        appWindows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != packageName }
            .maxByOrNull { it.layer }
            ?.root
            ?.let { return it }

        return activeRoot
    }

    private fun findEditableDescendant(node: AccessibilityNodeInfo?, maxDepth: Int): AccessibilityNodeInfo? {
        if (node == null || maxDepth < 0) return null
        if (node.isEditable) return node
        if (maxDepth == 0) return null
        for (i in 0 until node.childCount) {
            findEditableDescendant(node.getChild(i), maxDepth - 1)?.let { return it }
        }
        return null
    }

    private fun findLiveEditableNode(preferredPackage: String? = null): AccessibilityNodeInfo? {
        val root = resolveBestRoot(preferredPackage) ?: return null
        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?.takeIf { it.isEditable }
            ?.let { return it }
        return findEditableDescendant(root, maxDepth = 10)
    }

    private fun findDescendantLabel(node: AccessibilityNodeInfo?, maxDepth: Int): String {
        if (node == null || maxDepth <= 0) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val candidate = listOfNotNull(
                child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    child.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    child.tooltipText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } else null,
                child.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.trim()
                    ?.takeIf { it.isNotEmpty() && (it.contains("search", ignoreCase = true) || it.contains("play", ignoreCase = true)) }
            ).firstOrNull()
            if (!candidate.isNullOrBlank()) return candidate
            val nested = findDescendantLabel(child, maxDepth - 1)
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    /**
     * Captures a downscaled (max 768px) JPEG screenshot of the live display on Android 11+ (API 30)
     * for Gemma 4 E2B multimodal vision fallback and Tier-Red PiP confirmation preview.
     */
    suspend fun captureScreenshotJpeg(maxDim: Int = 768): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(2500L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val bytes = runCatching {
                                    val hwBuffer = screenshot.hardwareBuffer
                                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                    val bytesOut = if (wrapped != null) {
                                        val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                        wrapped.recycle()
                                        val scaled = downscaleBitmap(soft, maxDim)
                                        if (scaled !== soft) soft.recycle()
                                        val baos = ByteArrayOutputStream()
                                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                                        scaled.recycle()
                                        baos.toByteArray()
                                    } else null
                                    hwBuffer.close()
                                    bytesOut
                                }.getOrNull()
                                if (cont.isActive) cont.resume(bytes)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "takeScreenshot failed with code=$errorCode")
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun downscaleBitmap(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    suspend fun performUiAct(
        action: String,
        nodeId: String,
        text: String? = null
    ): UiActOutcome {
        if (killSwitchTripped.get()) {
            return UiActOutcome(false, "Aborted by kill switch", emptyList())
        }
        isAutomationActive.set(true)
        return try {
            when (action.uppercase()) {
                "BACK" -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Went back.", updated)
                }

                "HOME" -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_HOME)
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Went to the Home screen.", updated)
                }

                "RECENTS" -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Opened recent apps.", updated)
                }

                "NOTIFICATIONS" -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Opened notifications.", updated)
                }

                "QUICK_SETTINGS" -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Opened quick settings.", updated)
                }

                "SCREENSHOT" -> {
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else false
                    waitForWindowUpdate(800L)
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(
                        ok,
                        if (ok) "Took a screenshot." else "Screen capture requires Android 9 or newer.",
                        updated
                    )
                }

                "LOCK_SCREEN" -> {
                    val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    } else false
                    UiActOutcome(
                        ok,
                        if (ok) "Locked your screen." else "Screen lock requires Android 9 or newer.",
                        emptyList()
                    )
                }

                "SCROLL_DOWN" -> {
                    val scrollable = liveNodeCache.values.firstOrNull { it.isScrollable } ?: rootInActiveWindow
                    val ok = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Scrolled down (${updated.size} nodes)", updated)
                }

                "SCROLL_UP" -> {
                    val scrollable = liveNodeCache.values.firstOrNull { it.isScrollable } ?: rootInActiveWindow
                    val ok = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Scrolled up (${updated.size} nodes)", updated)
                }

                "TAP_XY" -> {
                    val parts = nodeId.split(",", "x", " ").mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size < 2) {
                        return UiActOutcome(false, "TAP_XY requires 'x,y' coordinates in node_id", scanActiveScreenNodes())
                    }
                    val ok = tapCoordinates(parts[0], parts[1])
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Tapped (${parts[0].toInt()},${parts[1].toInt()})", updated)
                }

                "TYPE" -> {
                    val rawNode = liveNodeCache[nodeId] ?: findLiveEditableNode()
                        ?: return UiActOutcome(false, "Node $nodeId not found on current screen", scanActiveScreenNodes())
                    val targetEditable = if (rawNode.isEditable) {
                        rawNode
                    } else {
                        findEditableDescendant(rawNode, maxDepth = 3) ?: rawNode
                    }
                    // Click & focus first so Compose BasicTextField / SearchView accepts ACTION_SET_TEXT
                    if (targetEditable.isClickable) {
                        targetEditable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else if (rawNode.isClickable) {
                        rawNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    targetEditable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(140L)

                    val liveFocused = findLiveEditableNode() ?: targetEditable
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.orEmpty())
                    }
                    val ok = liveFocused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ||
                        targetEditable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    waitForWindowUpdate(650L)
                    delay(180L)
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Typed '${text.orEmpty()}' into $nodeId", updated)
                }

                "CLICK" -> {
                    // Support vision coordinate fallback if Gemma 4 returned "x,y"
                    val xy = nodeId.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (xy.size == 2 && liveNodeCache[nodeId] == null) {
                        val ok = tapCoordinates(xy[0], xy[1])
                        waitForWindowUpdate()
                        val updated = scanActiveScreenNodes()
                        return UiActOutcome(ok, "Tapped visual coordinates (${xy[0].toInt()},${xy[1].toInt()})", updated)
                    }
                    val node = liveNodeCache[nodeId]
                        ?: return UiActOutcome(false, "Node $nodeId not found on current screen", scanActiveScreenNodes())
                    val label = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
                    if (DeferredToolRegistry.isRedButtonLabel(label)) {
                        return UiActOutcome(false, "TIER_RED_BLOCKED:$label", scanActiveScreenNodes())
                    }
                    var ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!ok) {
                        var parent = node.parent
                        var depth = 0
                        while (parent != null && depth < 4 && !ok) {
                            if (parent.isClickable) {
                                ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            parent = parent.parent
                            depth++
                        }
                    }
                    if (!ok) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.width() > 0 && rect.height() > 0) {
                            ok = tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                        }
                    }
                    waitForWindowUpdate()
                    val updated = scanActiveScreenNodes()
                    UiActOutcome(ok, "Clicked $nodeId ($label)", updated)
                }

                else -> UiActOutcome(false, "Unsupported action $action", scanActiveScreenNodes())
            }
        } finally {
            isAutomationActive.set(false)
        }
    }

    /**
     * Local Screen Control Process: finds a UI element on the active screen by natural label
     * (text, contentDescription, hint, or resource name) and executes CLICK, TYPE, or TOGGLE
     * locally in Process A while enforcing Tier Red safety checks.
     */
    suspend fun performFindAndAct(
        targetLabel: String,
        actionType: String = "CLICK",
        inputText: String? = null
    ): UiActOutcome {
        if (killSwitchTripped.get()) {
            return UiActOutcome(false, "Aborted by kill switch", emptyList())
        }
        val nodes = scanActiveScreenNodes()
        if (nodes.isEmpty()) {
            return UiActOutcome(false, "No readable elements found on the current screen.", emptyList())
        }
        val normalizedAction = actionType.trim().uppercase()
        val preferEditable = normalizedAction == "TYPE"
        val match = findBestNodeByLabel(
            nodes = nodes,
            targetLabel = targetLabel,
            preferEditable = preferEditable,
            preferClickable = !preferEditable
        ) ?: return UiActOutcome(
            false,
            "I couldn't find '$targetLabel' on the current screen.",
            nodes
        )

        if (normalizedAction != "TYPE" && DeferredToolRegistry.isRedButtonLabel(match.label)) {
            return UiActOutcome(false, "TIER_RED_BLOCKED:${match.id}:${match.label}", nodes)
        }

        val actName = when (normalizedAction) {
            "TYPE" -> "TYPE"
            else -> "CLICK"
        }
        return performUiAct(actName, match.id, inputText)
    }

    /**
     * Local Screen Control workflow for media/search apps (e.g., YT Music, Spotify, YouTube, Gallery):
     * auto-dismisses onboarding/permission/account prompts, handles random song selection when generic,
     * opens Search or taps Play/Shuffle, types the query cleanly, and starts playback/results.
     */
    suspend fun performLocalSearchAndPlay(
        queryText: String?,
        playFirstResult: Boolean = true,
        recordedAccount: String? = null,
        appHint: String? = null
    ): UiActOutcome {
        if (killSwitchTripped.get()) {
            return UiActOutcome(false, "Aborted by kill switch", emptyList())
        }

        val preferredPkg = appHint?.let { AppCapabilityRegistry.findBestMatch(this, it)?.packageName }

        // Wait briefly for newly launched app windows to settle if screen is initially empty
        var nodes = scanActiveScreenNodes(preferredPkg)
        if (nodes.isEmpty()) {
            waitForTargetAppWindow(preferredPkg, timeoutMs = 2400L)
            nodes = scanActiveScreenNodes(preferredPkg)
        }

        // 1. Clear any pop-ups, permissions, or account selectors first
        repeat(2) {
            tryAutoResolveNonBlockingDialog(
                recordedAccount = recordedAccount,
                appHint = appHint,
                onAccountDetected = {}
            )
        }

        val cleanQuery = queryText?.trim().orEmpty()
        val isPlayExisting = cleanQuery.isEmpty() || cleanQuery.lowercase() in setOf(
            "it", "this", "first", "the first", "first one", "first result"
        )
        val isRandomMusic = !isPlayExisting && RandomSongCatalog.isGenericOrRandomMusicQuery(cleanQuery)

        nodes = scanActiveScreenNodes(preferredPkg)
        if (isPlayExisting) {
            val playNode = findBestNodeByLabel(
                nodes = nodes,
                targetLabel = "play",
                preferEditable = false,
                preferClickable = true
            ) ?: findBestNodeByLabel(
                nodes = nodes,
                targetLabel = "shuffle",
                preferEditable = false,
                preferClickable = true
            ) ?: nodes.firstOrNull {
                it.clickable && !isNavOrSearchChrome(it.label)
            }
            if (playNode != null && !DeferredToolRegistry.isRedButtonLabel(playNode.label)) {
                return performUiAct("CLICK", playNode.id)
            }
            return UiActOutcome(false, "No playable item or Play button found yet on screen.", nodes)
        }

        // For generic "play music" / "play a random song", pick a real track so search+play works reliably across apps
        val (effectiveQuery, wasRandomized) = if (isRandomMusic) {
            val shuffleNode = if (cleanQuery.contains("shuffle", ignoreCase = true)) {
                findBestNodeByLabel(nodes, "shuffle", preferEditable = false, preferClickable = true)
            } else null
            if (shuffleNode != null && !DeferredToolRegistry.isRedButtonLabel(shuffleNode.label)) {
                return performUiAct("CLICK", shuffleNode.id)
            }
            RandomSongCatalog.pickRandomTrack() to true
        } else {
            cleanQuery to false
        }

        // 2. Find editable search box, or tap Search tab / Search bar up to 2 steps
        var editNode = nodes.firstOrNull { it.editable }
        var attempts = 0
        while (editNode == null && attempts < 2) {
            attempts++
            val searchBtn = findBestNodeByLabel(
                nodes = nodes,
                targetLabel = "search",
                preferEditable = false,
                preferClickable = true
            ) ?: findBestNodeByLabel(
                nodes = nodes,
                targetLabel = "find",
                preferEditable = false,
                preferClickable = true
            )
            if (searchBtn == null) break
            performUiAct("CLICK", searchBtn.id)
            waitForWindowUpdate(1200L)
            delay(300L)
            nodes = scanActiveScreenNodes(preferredPkg)
            editNode = nodes.firstOrNull { it.editable }
        }

        if (editNode == null) {
            return UiActOutcome(
                false,
                "NEEDS_LLM_LOOP:Opened ${appHint ?: "app"}, could not locate a search field automatically.",
                nodes
            )
        }

        // 3. Type query safely and submit via FRESH focused live Editable node (never stale cache key!)
        val typeOutcome = performUiAct("TYPE", editNode.id, effectiveQuery)
        delay(200L)
        var submitted = false
        val freshEditable = findLiveEditableNode(preferredPkg)
        if (freshEditable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            submitted = freshEditable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        waitForWindowUpdate(1000L)
        delay(350L)
        nodes = scanActiveScreenNodes(preferredPkg)

        if (!submitted) {
            val editableIds = nodes.filter { it.editable }.map { it.id }.toSet()
            val submitMatch = findBestNodeByLabel(
                nodes = nodes,
                targetLabel = "search for $effectiveQuery",
                preferEditable = false,
                preferClickable = true,
                excludeIds = editableIds
            ) ?: findBestNodeByLabel(
                nodes = nodes.filterNot { isSearchClearOrVoiceButton(it.label) },
                targetLabel = "search",
                preferEditable = false,
                preferClickable = true,
                excludeIds = editableIds
            ) ?: findBestNodeByLabel(
                nodes = nodes.filterNot { isSearchClearOrVoiceButton(it.label) },
                targetLabel = effectiveQuery,
                preferEditable = false,
                preferClickable = true,
                excludeIds = editableIds
            )
            if (submitMatch != null && !DeferredToolRegistry.isRedButtonLabel(submitMatch.label)) {
                performUiAct("CLICK", submitMatch.id)
                waitForWindowUpdate(1000L)
                delay(350L)
                nodes = scanActiveScreenNodes(preferredPkg)
            }
        }

        // 4. Wait briefly for search results / playback list to populate, then either play or read choices
        if (nodes.count { !it.editable && !isNavOrSearchChrome(it.label) && !isSearchClearOrVoiceButton(it.label) } <= 1) {
            waitForWindowUpdate(900L)
            delay(500L)
            nodes = scanActiveScreenNodes(preferredPkg)
        }

        if (playFirstResult) {
            val editableIds = nodes.filter { it.editable }.map { it.id }.toSet()
            val contentNodes = nodes.filterNot {
                it.id in editableIds || isNavOrSearchChrome(it.label) || isSearchClearOrVoiceButton(it.label)
            }
            val resultMatch = findBestMediaPlayNode(contentNodes, effectiveQuery)
            if (resultMatch != null && !DeferredToolRegistry.isRedButtonLabel(resultMatch.label)) {
                val clickOutcome = performUiAct("CLICK", resultMatch.id)
                val msg = if (wasRandomized) {
                    "Playing random song '$effectiveQuery'${appHint?.let { " in $it" }.orEmpty()} via screen control."
                } else {
                    "Searched '$effectiveQuery' and started playback${appHint?.let { " in $it" }.orEmpty()}."
                }
                return clickOutcome.copy(message = msg)
            }
            return UiActOutcome(
                false,
                "Searched '$effectiveQuery'${appHint?.let { " in $it" }.orEmpty()}, but couldn't find a playable button yet.",
                nodes
            )
        }

        val choices = extractSelectableScreenChoices(nodes, maxChoices = 4)
        val summary = formatScreenChoicesSummary(
            appHint = appHint,
            query = effectiveQuery,
            choices = choices
        )
        return UiActOutcome(typeOutcome.success, summary, nodes)
    }

    /**
     * Used after firing MEDIA_PLAY_FROM_SEARCH / deep-link intents in music apps like YouTube Music
     * that often open the search/home page without starting audio automatically.
     */
    suspend fun verifyAndTriggerMediaPlayback(
        targetPkg: String?,
        queryText: String,
        appName: String,
        wasAlreadyPlaying: Boolean = false
    ): UiActOutcome {
        if (killSwitchTripped.get()) {
            return UiActOutcome(false, "Aborted by kill switch", emptyList())
        }
        waitForTargetAppWindow(targetPkg, timeoutMs = 2600L)
        delay(650L)

        repeat(2) {
            tryAutoResolveNonBlockingDialog(
                recordedAccount = null,
                appHint = appName,
                onAccountDetected = {}
            )
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var nodes = scanActiveScreenNodes(targetPkg)
        if (audioManager?.isMusicActive == true) {
            val queryTokens = queryText.lowercase()
                .split(Regex("\\s+"))
                .filter { it.length >= 3 }
            val screenShowsRequestedTrack = queryTokens.isEmpty() || nodes.any { node ->
                val lbl = node.label.lowercase()
                queryTokens.any { tok -> lbl.contains(tok) }
            }
            if (!wasAlreadyPlaying || screenShowsRequestedTrack) {
                return UiActOutcome(true, "Playing '$queryText' in $appName.", nodes)
            }
        }

        if (nodes.isEmpty()) {
            delay(600L)
            nodes = scanActiveScreenNodes(targetPkg)
        }

        val editableIds = nodes.filter { it.editable }.map { it.id }.toSet()
        val contentNodes = nodes.filterNot {
            it.id in editableIds || isNavOrSearchChrome(it.label) || isSearchClearOrVoiceButton(it.label)
        }
        val directPlayNode = findBestMediaPlayNode(contentNodes, queryText)
        if (directPlayNode != null && !DeferredToolRegistry.isRedButtonLabel(directPlayNode.label)) {
            val clickOutcome = performUiAct("CLICK", directPlayNode.id)
            delay(450L)
            if (clickOutcome.success) {
                return clickOutcome.copy(message = "Playing '$queryText' in $appName.")
            }
        }

        // Wait once more in case YT Music is still loading search results asynchronously
        delay(800L)
        nodes = scanActiveScreenNodes(targetPkg)
        val retryEditableIds = nodes.filter { it.editable }.map { it.id }.toSet()
        val retryContent = nodes.filterNot {
            it.id in retryEditableIds || isNavOrSearchChrome(it.label) || isSearchClearOrVoiceButton(it.label)
        }
        val retryPlayNode = findBestMediaPlayNode(retryContent, queryText)
        if (retryPlayNode != null && !DeferredToolRegistry.isRedButtonLabel(retryPlayNode.label)) {
            val clickOutcome = performUiAct("CLICK", retryPlayNode.id)
            if (clickOutcome.success) {
                return clickOutcome.copy(message = "Playing '$queryText' in $appName.")
            }
        }

        // Fallback: app opened Home instead of search results -> drive the in-app Search bar + play top result
        val searchFlow = performLocalSearchAndPlay(
            queryText = queryText,
            playFirstResult = true,
            appHint = appName
        )
        return searchFlow
    }

    private fun findBestMediaPlayNode(
        contentNodes: List<UiNodeSnapshot>,
        queryText: String
    ): UiNodeSnapshot? {
        if (contentNodes.isEmpty()) return null
        val clickable = contentNodes.filter { it.clickable }
        val pool = clickable.ifEmpty { contentNodes }

        // 1. Explicit "Play <query>" or matching song card containing query keywords (exclude "search" / "upgrade")
        val queryTokens = queryText.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
        if (queryTokens.isNotEmpty()) {
            val scored = pool.mapNotNull { node ->
                val l = node.label.lowercase()
                if (l.contains("upgrade") || l.contains("premium") || l.contains("free trial") ||
                    l.startsWith("search") || l == "songs" || l == "videos" || l == "albums" || l == "artists"
                ) {
                    return@mapNotNull null
                }
                val hits = queryTokens.count { l.contains(it) }
                if (hits > 0) {
                    val bonus = (if (l.contains("play")) 4 else 0) + (if (l.contains("song")) 2 else 0)
                    node to (hits * 10 + bonus)
                } else null
            }.maxByOrNull { it.second }
            // Do NOT fall back to generic miniplayer "Play" when searching for a specific song title/theme,
            // otherwise miniplayer may resume an unrelated previous track (e.g., an earlier Christmas song).
            return scored?.first
        }

        // 2. Generic/unspecified request -> direct "Play" / "Shuffle" action button
        return findBestNodeByLabel(
            nodes = pool,
            targetLabel = "play",
            preferEditable = false,
            preferClickable = true
        ) ?: findBestNodeByLabel(
            nodes = pool,
            targetLabel = "shuffle",
            preferEditable = false,
            preferClickable = true
        ) ?: pool.firstOrNull { node ->
            val l = node.label.lowercase()
            l.length >= 4 &&
                !l.contains("upgrade") &&
                !l.contains("premium") &&
                !l.contains("sign in")
        }
    }

    fun extractSelectableScreenChoices(
        nodes: List<UiNodeSnapshot>,
        maxChoices: Int = 5
    ): List<UiNodeSnapshot> = ScreenTextSanitizer.filterAndCleanChoices(nodes, maxChoices)

    private fun cleanNodeChoiceLabel(raw: String): String =
        ScreenTextSanitizer.cleanUiLabelForSpeech(raw).take(110)

    private fun formatScreenChoicesSummary(
        appHint: String?,
        query: String,
        choices: List<UiNodeSnapshot>
    ): String {
        val where = appHint?.let { " in $it" }.orEmpty()
        if (choices.isEmpty()) {
            return "Searched for '$query'$where, but no selectable results appeared on screen yet."
        }
        if (choices.size == 1) {
            return "I found one result for '$query'$where: ${choices.first().label}. Would you like me to open it?"
        }
        val ordinals = listOf("First", "Second", "Third", "Fourth", "Fifth")
        val listText = choices.take(ordinals.size).mapIndexed { idx, node ->
            "${ordinals[idx]}, ${node.label}"
        }.joinToString(". ")
        return "Here are the top results for '$query'$where. $listText. Which one would you like me to open?"
    }

    private fun isSearchClearOrVoiceButton(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("clear") || l.contains("voice") || l.contains("microphone") || l.contains("navigate up") || l == "back"
    }

    private fun isNavOrSearchChrome(label: String): Boolean {
        val l = label.lowercase()
        return l == "home" || l == "shorts" || l == "subscriptions" || l == "library" || l == "you" ||
            l.startsWith("search") || l.contains("cast") || l.contains("notifications")
    }

    private fun findBestNodeByLabel(
        nodes: List<UiNodeSnapshot>,
        targetLabel: String,
        preferEditable: Boolean,
        preferClickable: Boolean,
        excludeIds: Set<String> = emptySet()
    ): UiNodeSnapshot? {
        val q = targetLabel.trim().lowercase()
        val eligible = if (excludeIds.isEmpty()) nodes else nodes.filterNot { it.id in excludeIds }
        if (q.isEmpty() || eligible.isEmpty()) return null

        val pool = when {
            preferEditable -> eligible.filter { it.editable }.ifEmpty { eligible }
            preferClickable -> eligible.filter { it.clickable }.ifEmpty { eligible }
            else -> eligible
        }

        fun clean(label: String): String =
            label.replace(Regex("\\[(ON|OFF|[^\\]]+)\\]"), "").trim().lowercase()

        fun searchPool(pool: List<UiNodeSnapshot>): UiNodeSnapshot? {
            pool.firstOrNull { clean(it.label) == q }?.let { return it }
            pool.firstOrNull { clean(it.label).startsWith(q) }?.let { return it }
            pool.firstOrNull { clean(it.label).contains(q) }?.let { return it }

            val qTokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
            return pool.mapNotNull { n ->
                val lbl = clean(n.label)
                val hits = qTokens.count { tok -> lbl.contains(tok) }
                if (hits > 0) n to hits else null
            }.maxByOrNull { it.second }?.first
        }

        searchPool(pool)?.let { return it }
        if (pool !== eligible) {
            return searchPool(eligible)
        }
        return null
    }

    suspend fun waitForTargetAppWindow(targetPackage: String?, timeoutMs: Long = 3200L): Boolean {
        val ownPkg = packageName
        val systemDialogs = setOf(
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.gms",
            "com.android.packageinstaller"
        )
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val bestRoot = resolveBestRoot(targetPackage)
            val activePkg = bestRoot?.packageName?.toString()
            val matchesTarget = when {
                !targetPackage.isNullOrBlank() && activePkg == targetPackage -> true
                !activePkg.isNullOrBlank() && activePkg in systemDialogs -> true
                targetPackage.isNullOrBlank() && !activePkg.isNullOrBlank() && activePkg != ownPkg -> true
                else -> false
            }
            if (matchesTarget && scanActiveScreenNodes(targetPackage).isNotEmpty()) {
                return true
            }
            waitForWindowUpdate(450L)
            delay(150L)
        }
        return scanActiveScreenNodes(targetPackage).isNotEmpty()
    }

    /**
     * Checks active screen for non-destructive blockers (pop-ups, permission dialogs,
     * app choosers, or single/recorded account prompts) and automatically clicks through them.
     */
    suspend fun tryAutoResolveNonBlockingDialog(
        recordedAccount: String?,
        appHint: String? = null,
        onAccountDetected: (String) -> Unit
    ): AutonomousDialogResolver.AutoResolutionDecision? {
        if (killSwitchTripped.get()) return null
        val nodes = scanActiveScreenNodes()
        val windowPkg = rootInActiveWindow?.packageName?.toString()
        val decision = AutonomousDialogResolver.evaluateScreen(
            nodes = nodes,
            windowPackage = windowPkg,
            recordedAccount = recordedAccount,
            appHint = appHint
        ) ?: return null

        decision.detectedAccount?.let(onAccountDetected)
        val actOutcome = performUiAct("CLICK", decision.targetNode.id)
        return if (actOutcome.success) decision else null
    }

    /**
     * Automatically approves and clicks Save/Done/Create on an opened form template
     * (such as a Calendar event or Reminder creator) so the user isn't left waiting to tap Save.
     */
    suspend fun autoApproveSaveForm(
        recordedAccount: String? = null,
        timeoutMs: Long = 3500L
    ): UiActOutcome {
        if (killSwitchTripped.get()) {
            return UiActOutcome(false, "Automation aborted by kill switch.", emptyList())
        }
        isAutomationActive.set(true)
        return try {
            val ownPkg = packageName
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val activePkg = resolveBestRoot()?.packageName?.toString()
                if (!activePkg.isNullOrBlank() && activePkg != ownPkg && scanActiveScreenNodes().isNotEmpty()) {
                    break
                }
                waitForWindowUpdate(450L)
                delay(150L)
            }

            // Dismiss any benign onboarding, permission, or account prompt first
            repeat(2) {
                tryAutoResolveNonBlockingDialog(
                    recordedAccount = recordedAccount,
                    appHint = "Calendar",
                    onAccountDetected = {}
                )
                delay(220L)
            }

            val saveKeywords = listOf(
                "save reminder",
                "save event",
                "save",
                "done",
                "create reminder",
                "create event",
                "create",
                "add reminder",
                "add event",
                "confirm"
            )
            val avoidKeywords = listOf("cancel", "discard", "delete", "remove", "close", "dismiss", "back", "never")

            var clickedSaveNode: UiNodeSnapshot? = null
            var latestNodes = emptyList<UiNodeSnapshot>()

            repeat(3) { attempt ->
                latestNodes = scanActiveScreenNodes()
                val candidates = latestNodes.filter { n ->
                    n.clickable && !n.editable &&
                        avoidKeywords.none { bad -> n.label.lowercase().contains(bad) } &&
                        !DeferredToolRegistry.isRedButtonLabel(n.label)
                }

                for (kw in saveKeywords) {
                    val exact = candidates.firstOrNull {
                        it.label.replace(Regex("\\[[^\\]]+\\]"), "").trim().equals(kw, ignoreCase = true)
                    }
                    if (exact != null) {
                        clickedSaveNode = exact
                        break
                    }
                    val partial = candidates.firstOrNull {
                        it.label.replace(Regex("\\[[^\\]]+\\]"), "").trim().contains(kw, ignoreCase = true)
                    }
                    if (partial != null) {
                        clickedSaveNode = partial
                        break
                    }
                }

                if (clickedSaveNode != null) {
                    val outcome = performUiAct("CLICK", clickedSaveNode!!.id)
                    if (outcome.success) {
                        delay(350L)
                        return UiActOutcome(
                            success = true,
                            message = "Automatically tapped '${clickedSaveNode!!.label}' to save reminder.",
                            updatedNodes = outcome.updatedNodes
                        )
                    }
                }
                if (attempt < 2) {
                    waitForWindowUpdate(600L)
                    delay(200L)
                }
            }

            UiActOutcome(false, "Could not locate Save button on reminder form.", latestNodes)
        } finally {
            isAutomationActive.set(false)
        }
    }

    /**
     * Used on Android 13+ where Bluetooth/Wi-Fi direct toggles are restricted by OS sandbox:
     * scans the newly opened system Settings / Panel window, flips the matching Switch if needed,
     * and returns back cleanly.
     */
    suspend fun autoToggleSettingsSwitch(
        keywords: List<String>,
        desiredOn: Boolean,
        targetPkg: String? = "com.android.settings"
    ): Boolean {
        if (killSwitchTripped.get()) return false
        isAutomationActive.set(true)
        return try {
            // Wait explicitly for the Settings / Panel window to render so we never inspect the prior window
            if (!targetPkg.isNullOrBlank()) {
                waitForTargetAppWindow(targetPkg, timeoutMs = 2400L)
            } else {
                waitForWindowUpdate(1600L)
            }
            delay(280L)
            var root = rootInActiveWindow ?: return false
            var targetNode = findToggleNode(root, keywords)
            if (targetNode == null) {
                delay(450L)
                root = rootInActiveWindow ?: return false
                targetNode = findToggleNode(root, keywords) ?: return false
            }

            val isCurrentlyOn = readEffectiveSwitchState(targetNode)
            if (isCurrentlyOn == desiredOn) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            }

            var clicked = clickSwitchOrRow(targetNode)
            if (clicked) {
                delay(550L)
                val updatedRoot = rootInActiveWindow
                val updatedSwitch = if (updatedRoot != null) findToggleNode(updatedRoot, keywords) else null
                val nowOn = updatedSwitch?.let { readEffectiveSwitchState(it) }
                if (nowOn != null && nowOn != desiredOn) {
                    // Retry once if the first click only focused the row or missed the inner Switch
                    clickSwitchOrRow(updatedSwitch)
                    delay(500L)
                }
                val finalRoot = rootInActiveWindow
                val finalSwitch = if (finalRoot != null) findToggleNode(finalRoot, keywords) else null
                val verifiedState = finalSwitch?.let { readEffectiveSwitchState(it) }
                performGlobalAction(GLOBAL_ACTION_BACK)
                verifiedState == desiredOn || (verifiedState == null && clicked)
            } else {
                false
            }
        } finally {
            isAutomationActive.set(false)
        }
    }

    private suspend fun clickSwitchOrRow(targetNode: AccessibilityNodeInfo): Boolean {
        var clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            var p = targetNode.parent
            var hops = 0
            while (p != null && !clicked && hops < 3) {
                if (p.isClickable) clicked = p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                p = p.parent
                hops++
            }
        }
        if (!clicked) {
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                clicked = tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
        return clicked
    }

    private fun readEffectiveSwitchState(node: AccessibilityNodeInfo): Boolean {
        val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.trim()?.lowercase().orEmpty()
        } else ""
        val selfText = node.text?.toString()?.trim()?.lowercase().orEmpty()
        return when {
            stateDesc == "on" || stateDesc == "checked" -> true
            stateDesc == "off" || stateDesc == "not checked" -> false
            selfText == "on" -> true
            selfText == "off" -> false
            else -> node.isChecked
        }
    }

    private fun findToggleNode(root: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectToggleCandidates(root, keywords, candidates, inheritedRowText = "")
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun collectToggleCandidates(
        node: AccessibilityNodeInfo?,
        keywords: List<String>,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>,
        inheritedRowText: String = ""
    ) {
        if (node == null) return
        val cls = node.className?.toString().orEmpty()
        val isSwitch = node.isCheckable ||
            cls.contains("Switch", ignoreCase = true) ||
            cls.contains("CheckBox", ignoreCase = true) ||
            cls.contains("ToggleButton", ignoreCase = true)

        if (isSwitch) {
            val contextTexts = mutableListOf<String>()
            node.text?.toString()?.let { contextTexts.add(it) }
            node.contentDescription?.toString()?.let { contextTexts.add(it) }
            if (inheritedRowText.isNotBlank()) {
                contextTexts.add(inheritedRowText)
            }
            val parent1 = node.parent
            if (parent1 != null && countSwitchesInSubtree(parent1, maxNodes = 14) <= 1) {
                parent1.text?.toString()?.let { contextTexts.add(it) }
                contextTexts.add(collectSubtreeText(parent1, maxNodes = 8))
                val parent2 = parent1.parent
                if (parent2 != null && parent2.childCount <= 4 && countSwitchesInSubtree(parent2, maxNodes = 16) <= 1) {
                    contextTexts.add(collectSubtreeText(parent2, maxNodes = 12))
                }
            }
            val rowText = contextTexts.joinToString(" ").lowercase().trim()
            val matchesKeyword = keywords.any { kw ->
                val cleanKw = kw.lowercase().trim()
                cleanKw.isNotEmpty() && rowText.contains(cleanKw)
            }
            if (matchesKeyword) {
                var score = 10
                if (rowText.contains("use bluetooth") || rowText.contains("use wi-fi") || rowText.contains("use wifi")) {
                    score += 40
                }
                if (rowText == "bluetooth" || rowText == "use bluetooth" || rowText.startsWith("bluetooth ") || rowText.startsWith("use bluetooth")) {
                    score += 20
                }
                val secondaryNoise = listOf(
                    "scanning", "tethering", "contacts", "media audio", "phone calls",
                    "hd audio", "input device", "without names", "nearby", "pair", "visibility"
                )
                val queryMentionsNoise = keywords.any { kw ->
                    val cleanKw = kw.lowercase()
                    secondaryNoise.any { cleanKw.contains(it) }
                }
                if (!queryMentionsNoise && secondaryNoise.any { rowText.contains(it) }) {
                    score -= 80
                }
                if (score > 0) {
                    out.add(node to score)
                }
            }
        }

        // Capture compact single-switch row container text (1..6 children) top-down so switches inside rows
        // still see their label without pulling in sibling rows from parent list containers
        val nextRowText = if (!isSwitch && node.childCount in 1..6 && countSwitchesInSubtree(node, maxNodes = 14) <= 1) {
            val subtree = collectSubtreeText(node, maxNodes = 10).trim()
            if (subtree.length in 2..140) subtree else inheritedRowText
        } else {
            inheritedRowText
        }

        for (i in 0 until node.childCount) {
            collectToggleCandidates(node.getChild(i), keywords, out, nextRowText)
        }
    }

    private fun countSwitchesInSubtree(node: AccessibilityNodeInfo?, maxNodes: Int = 16): Int {
        if (node == null) return 0
        var switches = 0
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || visited >= maxNodes || switches > 1) return
            visited++
            val cls = n.className?.toString().orEmpty()
            val isSwitch = n.isCheckable ||
                cls.contains("Switch", ignoreCase = true) ||
                cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("ToggleButton", ignoreCase = true)
            if (isSwitch) switches++
            for (i in 0 until n.childCount) {
                walk(n.getChild(i))
            }
        }
        walk(node)
        return switches
    }

    private fun collectSubtreeText(node: AccessibilityNodeInfo?, maxNodes: Int = 10): String {
        if (node == null) return ""
        val sb = StringBuilder()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || visited >= maxNodes) return
            visited++
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                walk(n.getChild(i))
            }
        }
        walk(node)
        return sb.toString()
    }

    suspend fun dispatchTap(x: Float, y: Float, holdDurationMs: Long = 60L): Boolean {
        if (x < 0f || y < 0f) return false
        val dm = resources.displayMetrics
        val safeX = x.coerceIn(0f, (dm.widthPixels - 1).coerceAtLeast(1).toFloat())
        val safeY = y.coerceIn(0f, (dm.heightPixels - 1).coerceAtLeast(1).toFloat())
        val path = Path().apply { moveTo(safeX, safeY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, holdDurationMs.coerceIn(50L, 2500L)))
            .build()
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
                val accepted = runCatching {
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        null
                    )
                }.getOrDefault(false)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    suspend fun dispatchSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 320L
    ): Boolean {
        if (x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f) return false
        val dm = resources.displayMetrics
        val maxW = (dm.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxH = (dm.heightPixels - 1).coerceAtLeast(1).toFloat()
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, maxW), y1.coerceIn(0f, maxH))
            lineTo(x2.coerceIn(0f, maxW), y2.coerceIn(0f, maxH))
        }
        val safeDur = durationMs.coerceIn(100L, 2500L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, safeDur))
            .build()
        return withTimeoutOrNull(safeDur + 1500L) {
            suspendCancellableCoroutine { cont ->
                val accepted = runCatching {
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        null
                    )
                }.getOrDefault(false)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    private suspend fun tapCoordinates(x: Float, y: Float): Boolean = dispatchTap(x, y, 55L)

    private suspend fun waitForWindowUpdate(timeoutMs: Long = 1500L) {
        val signal = CompletableDeferred<Unit>()
        pendingWindowSignal = signal
        withTimeoutOrNull(timeoutMs) { signal.await() }
        pendingWindowSignal = null
    }

    companion object {
        private const val TAG = "NovaAccessibility"
        private const val MAX_DFS_VISITS = 450
        private const val MAX_SCAN_NODES = 120

        @Volatile
        var instance: NovaAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _lastKillSwitchReason = MutableStateFlow<String?>(null)
        val lastKillSwitchReason: StateFlow<String?> = _lastKillSwitchReason.asStateFlow()

        private val killSwitchTripped = AtomicBoolean(false)
        private val isAutomationActive = AtomicBoolean(false)
        private val liveNodeCache = ConcurrentHashMap<String, AccessibilityNodeInfo>()

        @Volatile
        private var pendingWindowSignal: CompletableDeferred<Unit>? = null

        private val abortListeners = mutableListOf<(String) -> Unit>()

        fun isServiceEnabledInSystem(context: Context): Boolean {
            if (instance != null) {
                _isConnected.value = true
                return true
            }
            val expected = ComponentName(context, NovaAccessibilityService::class.java)
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledByManager = runCatching {
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { info ->
                        val si = info.resolveInfo?.serviceInfo
                        si != null && si.packageName == context.packageName && si.name == NovaAccessibilityService::class.java.name
                    } == true
            }.getOrDefault(false)
            if (enabledByManager) return true

            val enabledSetting = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            val flat = expected.flattenToString()
            val shortFlat = expected.flattenToShortString()
            return enabledSetting.split(':').any { entry ->
                entry.equals(flat, ignoreCase = true) || entry.equals(shortFlat, ignoreCase = true)
            }
        }

        fun registerAbortListener(listener: (String) -> Unit) {
            abortListeners.add(listener)
        }

        fun isKillSwitchTripped(): Boolean = killSwitchTripped.get()

        fun resetKillSwitch() {
            killSwitchTripped.set(false)
            _lastKillSwitchReason.value = null
        }

        fun abortAutomation(reason: String) {
            killSwitchTripped.set(true)
            isAutomationActive.set(false)
            pendingWindowSignal?.cancel()
            _lastKillSwitchReason.value = reason
            abortListeners.forEach { runCatching { it(reason) } }
        }

        data class ScanOutcome(
            val launchedApp: Boolean,
            val accessibilityEnabled: Boolean,
            val nodes: List<UiNodeSnapshot>,
            val message: String
        )

        suspend fun uiOpenAndScan(context: Context, appName: String): ScanOutcome {
            resetKillSwitch()
            val match = AppCapabilityRegistry.findBestMatch(context, appName)
            val pm = context.packageManager
            val targetPkg = match?.packageName
            var launched = false
            if (targetPkg != null) {
                val launch = pm.getLaunchIntentForPackage(targetPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null) {
                    launched = runCatching { context.startActivity(launch) }.isSuccess
                }
            }

            val svc = instance
            if (svc == null) {
                val label = match?.appName ?: appName
                return ScanOutcome(
                    launchedApp = launched,
                    accessibilityEnabled = false,
                    nodes = emptyList(),
                    message = if (launched) {
                        "Opened $label, but Accessibility Service is disabled. Enable Nova in Android Accessibility Settings to read or control app screens."
                    } else {
                        "Could not find installed app '$appName' and Accessibility Service is disabled."
                    }
                )
            }

            if (launched) {
                svc.waitForTargetAppWindow(targetPkg, timeoutMs = 2400L)
            }

            val liveNodes = svc.scanActiveScreenNodes(targetPkg)
            return ScanOutcome(
                launchedApp = launched,
                accessibilityEnabled = true,
                nodes = liveNodes,
                message = if (liveNodes.isNotEmpty()) {
                    "Opened ${match?.appName ?: appName} and read the screen."
                } else {
                    "Opened ${match?.appName ?: appName}."
                }
            )
        }

        suspend fun performScreenAutomation(
            context: Context,
            appName: String,
            goal: String,
            queryText: String? = null,
            recordedAccount: String? = null,
            skipLaunchIfForeground: Boolean = true
        ): UiActOutcome {
            val match = AppCapabilityRegistry.findBestMatch(context, appName)
            val targetPkg = match?.packageName
            val resolvedName = match?.appName ?: appName
            val existingSvc = instance
            val cleanGoal = goal.trim().lowercase()

            val activePkgNow = existingSvc?.resolveBestRoot(targetPkg)?.packageName?.toString()
            val alreadyInForeground = existingSvc != null && targetPkg != null && activePkgNow == targetPkg

            val svc = if (existingSvc != null && (alreadyInForeground || skipLaunchIfForeground)) {
                resetKillSwitch()
                val settled = existingSvc.waitForTargetAppWindow(targetPkg, timeoutMs = 2800L)
                if (!settled && targetPkg != null) {
                    // Stage 1 may have been skipped; fall back to launching directly
                    val scan = uiOpenAndScan(context, appName)
                    if (!scan.accessibilityEnabled) {
                        return UiActOutcome(false, scan.message, emptyList())
                    }
                    instance ?: return UiActOutcome(false, scan.message, emptyList())
                } else {
                    existingSvc
                }
            } else {
                val scan = uiOpenAndScan(context, appName)
                if (!scan.accessibilityEnabled) {
                    return UiActOutcome(false, scan.message, emptyList())
                }
                instance?.also { it.waitForTargetAppWindow(targetPkg, timeoutMs = 2200L) }
                    ?: return UiActOutcome(false, scan.message, emptyList())
            }

            // Auto-clear pop-ups, permissions, and account choosers
            repeat(2) {
                svc.tryAutoResolveNonBlockingDialog(
                    recordedAccount = recordedAccount,
                    appHint = resolvedName,
                    onAccountDetected = {}
                )
            }

            val cleanQuery = queryText?.trim().orEmpty()
            return when {
                cleanGoal.contains("search") && cleanGoal.contains("play") ->
                    svc.performLocalSearchAndPlay(cleanQuery, playFirstResult = true, recordedAccount = recordedAccount, appHint = resolvedName)
                cleanGoal == "play" || cleanGoal.startsWith("play ") ->
                    svc.performLocalSearchAndPlay(cleanQuery.ifEmpty { cleanGoal.removePrefix("play").trim() }, playFirstResult = true, recordedAccount = recordedAccount, appHint = resolvedName)
                cleanGoal == "search_on_screen" || cleanGoal == "search" || cleanGoal.startsWith("search ") ->
                    svc.performLocalSearchAndPlay(cleanQuery.ifEmpty { cleanGoal.removePrefix("search").trim() }, playFirstResult = false, recordedAccount = recordedAccount, appHint = resolvedName)
                cleanGoal.contains("scroll down") ->
                    svc.performUiAct("SCROLL_DOWN", "")
                cleanGoal.contains("scroll up") ->
                    svc.performUiAct("SCROLL_UP", "")
                cleanGoal.startsWith("tap ") || cleanGoal.startsWith("click ") -> {
                    val target = cleanGoal.removePrefix("tap ").removePrefix("click ").trim()
                    svc.performFindAndAct(target, "CLICK")
                }
                else -> {
                    val nodes = svc.scanActiveScreenNodes(targetPkg)
                    val directMatch = svc.findBestNodeByLabel(nodes, goal, preferEditable = false, preferClickable = true)
                    if (directMatch != null && !DeferredToolRegistry.isRedButtonLabel(directMatch.label)) {
                        svc.performUiAct("CLICK", directMatch.id)
                    } else {
                        UiActOutcome(true, "NEEDS_LLM_LOOP:Opened $resolvedName for '$goal'.", nodes)
                    }
                }
            }
        }
    }
}

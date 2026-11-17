package com.example.processA

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.UiNodeSnapshot
import com.example.engine.AppCapabilityRegistry
import com.example.engine.AutonomousDialogResolver
import com.example.engine.DeferredToolRegistry
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "NovaAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            pendingWindowSignal?.complete(Unit)
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
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    fun scanActiveScreenNodes(): List<UiNodeSnapshot> {
        val root = rootInActiveWindow ?: return emptyList()
        liveNodeCache.clear()
        val snapshots = mutableListOf<UiNodeSnapshot>()
        traverseNode(root, snapshots)
        return snapshots
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableList<UiNodeSnapshot>) {
        if (node == null || out.size >= 48) return
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        val resId = node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.trim()

        val rawLabel = listOfNotNull(
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            hint,
            resId?.takeIf { it.isNotEmpty() && (node.isClickable || node.isEditable) }
        ).firstOrNull().orEmpty()

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
                val shortId = "n%02d".format(out.size + 1)
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                liveNodeCache[shortId] = node
                out.add(
                    UiNodeSnapshot(
                        id = shortId,
                        label = "$baseLabel$stateSuffix".take(72),
                        nodeType = simpleType,
                        boundsLabel = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]",
                        clickable = node.isClickable || node.isCheckable,
                        editable = node.isEditable
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), out)
        }
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
                    val node = liveNodeCache[nodeId]
                        ?: return UiActOutcome(false, "Node $nodeId not found on current screen", scanActiveScreenNodes())
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.orEmpty())
                    }
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    waitForWindowUpdate()
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
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        ok = tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
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
     * Used on Android 13+ where Bluetooth/Wi-Fi direct toggles are restricted by OS sandbox:
     * scans the newly opened system Settings / Panel window, flips the matching Switch if needed,
     * and returns back cleanly.
     */
    suspend fun autoToggleSettingsSwitch(
        keywords: List<String>,
        desiredOn: Boolean
    ): Boolean {
        if (killSwitchTripped.get()) return false
        isAutomationActive.set(true)
        return try {
            // Wait up to 1.6s for Settings or Panel window to render
            waitForWindowUpdate(1600L)
            delay(250L)
            val root = rootInActiveWindow ?: return false
            val targetNode = findToggleNode(root, keywords) ?: return false
            val isCurrentlyOn = targetNode.isChecked
            if (isCurrentlyOn != desiredOn) {
                var clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    var p = targetNode.parent
                    while (p != null && !clicked) {
                        if (p.isClickable) clicked = p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        p = p.parent
                    }
                }
                if (!clicked) {
                    val rect = Rect()
                    targetNode.getBoundsInScreen(rect)
                    clicked = tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
                if (clicked) {
                    delay(450L)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                clicked
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
                true
            }
        } finally {
            isAutomationActive.set(false)
        }
    }

    private fun findToggleNode(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString().orEmpty()
        val isSwitch = node.isCheckable || cls.contains("Switch", ignoreCase = true) || cls.contains("CheckBox", ignoreCase = true)
        if (isSwitch) {
            val selfText = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.parent?.text?.toString(),
                collectChildText(node.parent)
            ).joinToString(" ").lowercase()
            if (keywords.any { selfText.contains(it.lowercase()) }) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            findToggleNode(node.getChild(i), keywords)?.let { return it }
        }
        return null
    }

    private fun collectChildText(parent: AccessibilityNodeInfo?): String {
        if (parent == null) return ""
        val sb = StringBuilder()
        for (i in 0 until parent.childCount) {
            val ch = parent.getChild(i) ?: continue
            ch.text?.let { sb.append(it).append(' ') }
            ch.contentDescription?.let { sb.append(it).append(' ') }
        }
        return sb.toString()
    }

    private fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 45L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private suspend fun waitForWindowUpdate(timeoutMs: Long = 1500L) {
        val signal = CompletableDeferred<Unit>()
        pendingWindowSignal = signal
        withTimeoutOrNull(timeoutMs) { signal.await() }
        pendingWindowSignal = null
    }

    companion object {
        private const val TAG = "NovaAccessibility"

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

        fun registerAbortListener(listener: (String) -> Unit) {
            abortListeners.add(listener)
        }

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
                svc.waitForWindowUpdate(1800L)
                delay(300L)
            }

            val liveNodes = svc.scanActiveScreenNodes()
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
    }
}

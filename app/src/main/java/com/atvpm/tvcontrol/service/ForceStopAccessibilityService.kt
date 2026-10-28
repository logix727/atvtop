package com.atvpm.tvcontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

data class AutomationOutcome(
    val success: Boolean,
    val detail: String,
)

object AccessibilityGate {
    @Volatile
    private var lastExternalIntentTimestampMs: Long = 0L

    /**
     * Marks that the app intentionally launched a child/system Activity (e.g. Settings,
     * App Info, PackageInstaller, or Uninstaller) so `MainActivity.onUserLeaveHint()`
     * does not mistake it for the user pressing the HOME button and kill the process.
     */
    fun markLaunchingExternalActivity() {
        lastExternalIntentTimestampMs = System.currentTimeMillis()
    }

    fun isExternalActivityLaunchRecent(windowMs: Long = 8_000L): Boolean {
        val elapsed = System.currentTimeMillis() - lastExternalIntentTimestampMs
        return elapsed in 0..windowMs || ForceStopAccessibilityService.instance?.isBusy == true
    }

    fun isEnabled(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        services.contains(context.packageName, ignoreCase = true)
    }.getOrDefault(false)

    fun isRunning(): Boolean = ForceStopAccessibilityService.instance != null
}

/**
 * Accessibility Service that:
 * 1. Automates clicking "Force stop" -> "OK" in Android TV App Info settings when killing apps.
 * 2. Automatically checks "Always allow from this computer" and clicks "Allow" on the local
 *    "Allow USB debugging?" dialog (`com.android.systemui`) so built-in Local ADB (`127.0.0.1:5555`)
 *    connects seamlessly without manual remote juggling.
 */
class ForceStopAccessibilityService : AccessibilityService() {

    private data class PendingSession(
        val packageName: String,
        val appLabel: String,
        var clickedForceStop: Boolean = false,
        var clickedConfirmOk: Boolean = false,
        val completion: CompletableDeferred<AutomationOutcome> = CompletableDeferred(),
    )

    private val activeSession = AtomicReference<PendingSession?>(null)
    private val pendingPowerTarget = AtomicReference<String?>(null)

    val isBusy: Boolean
        get() = activeSession.get() != null || pendingPowerTarget.get() != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPkg = event?.packageName?.toString().orEmpty()
        val hasSession = activeSession.get() != null
        val hasPowerTarget = pendingPowerTarget.get() != null

        // Zero-overhead fast path: do not query window hierarchy unless needed
        if (!hasSession && !hasPowerTarget && eventPkg != "com.android.systemui") {
            return
        }

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()

        // 1. Auto-authorize built-in Local ADB ("Allow USB debugging?" in com.android.systemui)
        if (pkg == "com.android.systemui") {
            tryAutoAcceptUsbDebuggingDialog(root)
        }

        // 2. Auto-click Restart / Power off in system power dialog if requested
        val powerTarget = pendingPowerTarget.get()
        if (powerTarget != null) {
            tryClickPowerMenuOption(root, powerTarget)
        }

        // 3. If a Force-Stop session is active, drive the Settings UI
        val session = activeSession.get() ?: return
        if (pkg.contains("settings", ignoreCase = true)) {
            driveForceStopSession(root, session)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Triggers Reboot, Power Off, or Sleep WITHOUT ADB using Android's Accessibility Global Actions.
     */
    suspend fun triggerNoAdbPowerAction(
        targetOption: String?,
        sleepOnly: Boolean = false,
    ): AutomationOutcome = withContext(Dispatchers.Main) {
        if (sleepOnly && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            if (ok) {
                return@withContext AutomationOutcome(true, "TV put into Sleep / Standby mode")
            }
        }

        pendingPowerTarget.set(targetOption)
        try {
            val shown = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            if (!shown) {
                return@withContext AutomationOutcome(
                    false,
                    "Could not open TV Power Menu — use ADB Reboot or Device Settings",
                )
            }

            if (targetOption == null) {
                return@withContext AutomationOutcome(true, "Opened TV Power / Restart Menu")
            }

            // Give the system power dialog a moment to appear and auto-click the target option
            var clicked = false
            withTimeoutOrNull(3_000L) {
                while (!clicked) {
                    delay(200L)
                    val root = rootInActiveWindow ?: continue
                    if (tryClickPowerMenuOption(root, targetOption)) {
                        clicked = true
                    }
                }
            }

            AutomationOutcome(
                success = true,
                detail = if (clicked) {
                    "Triggered '$targetOption' via TV Power Menu"
                } else {
                    "Opened TV Power Menu — press OK to confirm $targetOption"
                },
            )
        } finally {
            pendingPowerTarget.set(null)
        }
    }

    private fun tryClickPowerMenuOption(root: AccessibilityNodeInfo, targetKeyword: String): Boolean {
        val allNodes = collectNodes(root)
        val match = allNodes.firstOrNull { node ->
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            txt.equals(targetKeyword, ignoreCase = true) ||
                desc.equals(targetKeyword, ignoreCase = true) ||
                (targetKeyword.equals("Restart", ignoreCase = true) &&
                    (txt.equals("Reboot", ignoreCase = true) || desc.equals("Reboot", ignoreCase = true))) ||
                (targetKeyword.equals("Power off", ignoreCase = true) &&
                    (txt.equals("Shut down", ignoreCase = true) || txt.equals("Turn off", ignoreCase = true)))
        } ?: return false

        pendingPowerTarget.set(null)
        return clickNodeOrParent(match)
    }

    private fun tryAutoAcceptUsbDebuggingDialog(root: AccessibilityNodeInfo) {
        val allNodes = collectNodes(root)
        val isUsbDebugPrompt = allNodes.any { node ->
            val text = node.text?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            (id.endsWith("alertTitle") && text.contains("USB debugging", ignoreCase = true)) ||
                text.contains("RSA key fingerprint", ignoreCase = true)
        }
        if (!isUsbDebugPrompt) return

        // Check "Always allow from this computer" if not checked yet
        val alwaysCheck = allNodes.firstOrNull { node ->
            node.viewIdResourceName?.endsWith("alwaysUse") == true ||
                node.text?.toString()?.contains("Always allow", ignoreCase = true) == true
        }
        if (alwaysCheck != null && alwaysCheck.isCheckable && !alwaysCheck.isChecked) {
            clickNodeOrParent(alwaysCheck)
        }

        // Click "Allow" / "OK" (android:id/button1)
        val allowBtn = allNodes.firstOrNull { node ->
            node.viewIdResourceName?.endsWith("button1") == true ||
                node.text?.toString()?.equals("Allow", ignoreCase = true) == true
        }
        if (allowBtn != null) {
            clickNodeOrParent(allowBtn)
        }
    }

    private fun driveForceStopSession(root: AccessibilityNodeInfo, session: PendingSession) {
        val allNodes = collectNodes(root)
        if (allNodes.isEmpty()) return

        // Check if we are on the Confirmation screen ("Force stop" / "OK" / "Cancel")
        val hasGuidanceTitle = allNodes.firstOrNull {
            it.viewIdResourceName?.endsWith("guidance_title") == true
        }?.text?.toString().orEmpty()

        val isForceStopConfirmScreen = hasGuidanceTitle.equals("Force stop", ignoreCase = true) ||
            allNodes.any {
                val t = it.text?.toString().orEmpty()
                t.contains("if you force stop", ignoreCase = true) ||
                    t.contains("misbehave", ignoreCase = true) ||
                    t.contains("want to force stop", ignoreCase = true)
            }

        if (isForceStopConfirmScreen || session.clickedForceStop) {
            // Never click OK if we accidentally landed on "Disable" or "Uninstall"
            if (hasGuidanceTitle.equals("Disable", ignoreCase = true) ||
                hasGuidanceTitle.equals("Uninstall", ignoreCase = true)
            ) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            val okNode = allNodes.firstOrNull { node ->
                val id = node.viewIdResourceName.orEmpty()
                val txt = node.text?.toString()?.trim().orEmpty()
                (id.endsWith("guidedactions_item_title") && txt.equals("OK", ignoreCase = true)) ||
                    (id.endsWith("button1") && (txt.equals("OK", ignoreCase = true) || txt.equals("Force stop", ignoreCase = true))) ||
                    txt.equals("OK", ignoreCase = true)
            }

            if (okNode != null && !session.clickedConfirmOk) {
                session.clickedConfirmOk = true
                if (clickNodeOrParent(okNode)) {
                    session.completion.complete(
                        AutomationOutcome(true, "Force-stopped ${session.appLabel} via Accessibility"),
                    )
                }
                return
            }
        }

        // Otherwise we are on the main App Info screen: look for "Force stop"
        if (!session.clickedForceStop) {
            val forceStopNode = allNodes.firstOrNull { node ->
                val id = node.viewIdResourceName.orEmpty()
                val txt = node.text?.toString()?.trim().orEmpty()
                id.endsWith("force_stop_button") ||
                    txt.equals("Force stop", ignoreCase = true)
            }

            if (forceStopNode != null) {
                val clickableTarget = findClickableSelfOrParent(forceStopNode) ?: forceStopNode
                if (!clickableTarget.isEnabled) {
                    // Already force-stopped!
                    session.completion.complete(
                        AutomationOutcome(true, "${session.appLabel} is already stopped"),
                    )
                    return
                }
                session.clickedForceStop = true
                clickNodeOrParent(forceStopNode)
            }
        }
    }

    suspend fun automateForceStop(
        context: Context,
        packageName: String,
        appLabel: String,
    ): AutomationOutcome = withContext(Dispatchers.Main) {
        val session = PendingSession(packageName = packageName, appLabel = appLabel)
        activeSession.set(session)

        try {
            AccessibilityGate.markLaunchingExternalActivity()
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            context.startActivity(intent)

            // Poll actively in addition to event callbacks so we never miss a window transition
            val outcome = withTimeoutOrNull(6_500L) {
                while (!session.completion.isCompleted) {
                    rootInActiveWindow?.let { root ->
                        val pkg = root.packageName?.toString().orEmpty()
                        if (pkg.contains("settings", ignoreCase = true)) {
                            driveForceStopSession(root, session)
                        }
                    }
                    delay(220L)
                }
                session.completion.await()
            } ?: AutomationOutcome(false, "Timed out waiting for Force stop button on $appLabel")

            // Return from Settings back to our app
            delay(250L)
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(180L)
            if (rootInActiveWindow?.packageName?.toString()?.contains("settings", ignoreCase = true) == true) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            outcome
        } catch (t: Throwable) {
            AutomationOutcome(false, "Accessibility error: ${t.message}")
        } finally {
            activeSession.compareAndSet(session, null)
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(64)
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            list.add(node)
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        walk(root)
        return list
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        val target = findClickableSelfOrParent(node) ?: node
        if (!target.isFocused && target.isFocusable) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val path = Path().apply {
                moveTo(rect.exactCenterX(), rect.exactCenterY())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    companion object {
        @Volatile
        var instance: ForceStopAccessibilityService? = null
            private set
    }
}

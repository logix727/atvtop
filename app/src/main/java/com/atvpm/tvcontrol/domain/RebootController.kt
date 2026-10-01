package com.atvpm.tvcontrol.domain

import android.content.Context
import android.os.DeadObjectException
import android.os.RemoteException
import com.atvpm.tvcontrol.core.CommandRunner
import com.atvpm.tvcontrol.core.PrivilegeManager
import com.atvpm.tvcontrol.core.ShellResult
import com.atvpm.tvcontrol.service.AccessibilityGate
import com.atvpm.tvcontrol.service.ForceStopAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException

enum class RebootCategory {
    POWER,
    REGULAR_REBOOT,
    ADB_ADVANCED,
}

/**
 * Supports Power Off, Regular Reboot (with or without ADB), and Advanced ADB Reboot modes.
 */
enum class RebootMode(
    val title: String,
    val subtitle: String,
    val command: String,
    val category: RebootCategory,
    val worksWithoutAdb: Boolean = false,
) {
    // 1. Direct Power Off button (works with ADB or Accessibility Power Dialog)
    POWER_OFF(
        title = "Power Off",
        subtitle = "Full TV shutdown or power menu",
        command = "svc power shutdown || reboot -p || poweroff",
        category = RebootCategory.POWER,
        worksWithoutAdb = true,
    ),

    // 2. Regular Reboot Dropdown ("Reboot ▼" — Works WITH and WITHOUT ADB)
    STANDARD_RESTART(
        title = "Restart TV",
        subtitle = "Standard safe reboot • Native or ADB",
        command = "svc power reboot || reboot",
        category = RebootCategory.REGULAR_REBOOT,
        worksWithoutAdb = true,
    ),
    TV_POWER_MENU(
        title = "TV Power Menu",
        subtitle = "Opens native on-screen TV dialog",
        command = "input keyevent --longpress 26",
        category = RebootCategory.REGULAR_REBOOT,
        worksWithoutAdb = true,
    ),
    SLEEP_STANDBY(
        title = "Sleep / Standby",
        subtitle = "Turn off screen into standby",
        command = "input keyevent 223",
        category = RebootCategory.REGULAR_REBOOT,
        worksWithoutAdb = true,
    ),
    RELOAD_HOME_UI(
        title = "Reload Home Screen",
        subtitle = "Fixes sluggish or frozen launcher",
        command = "am force-stop com.google.android.apps.tv.launcherx; am force-stop com.google.android.tvlauncher",
        category = RebootCategory.REGULAR_REBOOT,
        worksWithoutAdb = false,
    ),

    // 3. Advanced ADB Reboot Dropdown ("ADB Reboot ▼" — All ADB boot modes)
    NORMAL(
        title = "ADB Reboot",
        subtitle = "adb shell reboot",
        command = "reboot",
        category = RebootCategory.ADB_ADVANCED,
    ),
    SOFT_RESTART(
        title = "Quick UI Restart",
        subtitle = "Fast reload without hardware cycle",
        command = "stop && start",
        category = RebootCategory.ADB_ADVANCED,
    ),
    USERSPACE(
        title = "Userspace Reboot",
        subtitle = "reboot userspace",
        command = "reboot userspace",
        category = RebootCategory.ADB_ADVANCED,
    ),
    RECOVERY(
        title = "Recovery Mode",
        subtitle = "Reboot into Android TV recovery",
        command = "reboot recovery",
        category = RebootCategory.ADB_ADVANCED,
    ),
    SIDELOAD(
        title = "ADB Sideload",
        subtitle = "reboot sideload",
        command = "reboot sideload",
        category = RebootCategory.ADB_ADVANCED,
    ),
    BOOTLOADER(
        title = "Bootloader",
        subtitle = "reboot bootloader",
        command = "reboot bootloader",
        category = RebootCategory.ADB_ADVANCED,
    ),
    FASTBOOT(
        title = "Fastbootd",
        subtitle = "reboot fastboot",
        command = "reboot fastboot",
        category = RebootCategory.ADB_ADVANCED,
    ),
    UPDATE(
        title = "Vendor Update",
        subtitle = "reboot update",
        command = "reboot update",
        category = RebootCategory.ADB_ADVANCED,
    ),
}

class RebootController(
    private val context: Context,
    private val privilegeManager: PrivilegeManager,
) {

    suspend fun executeReboot(mode: RebootMode): ActionResult =
        withContext(Dispatchers.IO) {
            val runner = privilegeManager.active
            val a11y = ForceStopAccessibilityService.instance

            // Special case: TV_POWER_MENU prefers Accessibility GLOBAL_ACTION_POWER_DIALOG first
            if (mode == RebootMode.TV_POWER_MENU && AccessibilityGate.isEnabled(context) && a11y != null) {
                val res = a11y.triggerNoAdbPowerAction(targetOption = null, sleepOnly = false)
                if (res.success) {
                    return@withContext ActionResult(true, res.detail)
                }
            }

            // 1. Try ADB first if connected
            if (runner.isReady) {
                val timeoutMs = if (mode == RebootMode.SOFT_RESTART) {
                    CommandRunner.LONG_TIMEOUT_MS
                } else {
                    10_000L
                }

                val res: ShellResult = runner.run(mode.command, timeoutMs)
                val combined = res.combined.lowercase()

                val hasError = combined.contains("permission denied") ||
                    combined.contains("not permitted") ||
                    combined.contains("failed") ||
                    combined.contains("error:")

                val dropped = res.failure is DeadObjectException ||
                    res.failure is RemoteException ||
                    res.failure is EOFException ||
                    (res.failure?.message?.lowercase()?.let {
                        it.contains("broken pipe") || it.contains("reset") || it.contains("eof") || it.contains("closed")
                    } == true)

                if (!hasError && (res.ok || dropped || (res.exitCode == -1 && res.stderr.isBlank()))) {
                    return@withContext ActionResult(
                        success = true,
                        message = "${mode.title} executed via ADB",
                    )
                }
            }

            // 2. Fallback WITHOUT ADB (via Accessibility Service or System Settings)
            if (mode.worksWithoutAdb) {
                if (AccessibilityGate.isEnabled(context) && a11y != null) {
                    val outcome = when (mode) {
                        RebootMode.POWER_OFF ->
                            a11y.triggerNoAdbPowerAction(targetOption = "Power off", sleepOnly = false)
                        RebootMode.STANDARD_RESTART ->
                            a11y.triggerNoAdbPowerAction(targetOption = "Restart", sleepOnly = false)
                        RebootMode.TV_POWER_MENU ->
                            a11y.triggerNoAdbPowerAction(targetOption = null, sleepOnly = false)
                        RebootMode.SLEEP_STANDBY ->
                            a11y.triggerNoAdbPowerAction(targetOption = null, sleepOnly = true)
                        else -> null
                    }
                    if (outcome != null && outcome.success) {
                        return@withContext ActionResult(true, outcome.detail)
                    }
                }

                // Final fallback if neither ADB nor Accessibility is enabled: open System About / Power settings
                val opened = privilegeManager.openAboutSettings()
                return@withContext ActionResult(
                    success = opened,
                    message = if (opened) {
                        "Opened TV System Settings — scroll down to 'Restart' (or enable ADB/Accessibility for 1-click reboot)"
                    } else {
                        "Enable ADB or Accessibility Service for 1-click ${mode.title}"
                    },
                )
            }

            ActionResult(
                success = false,
                message = "${mode.title} requires Local ADB connection.",
            )
        }
}

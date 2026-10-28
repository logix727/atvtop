package com.atvpm.tvcontrol.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.atvpm.tvcontrol.core.backend.AdbBackend
import com.atvpm.tvcontrol.service.AccessibilityGate
import com.atvpm.tvcontrol.service.ForceStopAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AdbConnectionState(
    val connected: Boolean = false,
    val portOpen: Boolean = false,
    val devOptionsEnabled: Boolean = false,
    val adbSettingEnabled: Boolean = false,
    val accessibilityReady: Boolean = false,
    val probing: Boolean = true,
    val initialProbeComplete: Boolean = false,
    val serial: String = "127.0.0.1:5555",
    val statusText: String = "Checking ADB status...",
    val hintText: String = "",
) {
    val canKillSilentlyOrAutomate: Boolean
        get() = connected || accessibilityReady
}

/**
 * Manages the two Simple v1.1 backends:
 * 1. Built-in Local ADB (`127.0.0.1:5555`)
 * 2. Accessibility Service (`ForceStopAccessibilityService`)
 */
class PrivilegeManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probeMutex = Mutex()

    val adb = AdbBackend(appContext)

    private val _state = MutableStateFlow(
        AdbConnectionState(
            portOpen = adb.isAdBdListening,
            devOptionsEnabled = isDevOptionsEnabled(),
            adbSettingEnabled = isAdbSettingEnabled(),
            accessibilityReady = AccessibilityGate.isEnabled(appContext),
            probing = true,
            initialProbeComplete = false,
        ),
    )
    val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    val active: CommandRunner get() = adb

    private fun isDevOptionsEnabled(): Boolean = runCatching {
        Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
    }.getOrDefault(false)

    private fun isAdbSettingEnabled(): Boolean = runCatching {
        Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.ADB_ENABLED,
            0,
        ) == 1
    }.getOrDefault(false)

    fun refresh() {
        scope.launch { probe() }
    }

    suspend fun probe(): AdbConnectionState = withContext(Dispatchers.Default) {
        probeMutex.withLock {
            val devEnabled = isDevOptionsEnabled()
            val adbEnabled = isAdbSettingEnabled()
            val listening = adb.isAdBdListening

            _state.value = _state.value.copy(
                probing = true,
                portOpen = listening,
                devOptionsEnabled = devEnabled,
                adbSettingEnabled = adbEnabled,
                accessibilityReady = AccessibilityGate.isEnabled(appContext),
            )

            val ready = if (listening) {
                runCatching { adb.probeConnection(15_000L) }.getOrDefault(false)
            } else {
                false
            }

            // If ADB connected, persist port 5555 and auto-grant Accessibility if not yet enabled!
            if (ready) {
                if (!AccessibilityGate.isEnabled(appContext)) {
                    autoEnableAccessibilityViaAdb()
                } else {
                    runCatching { adb.run("setprop service.adb.tcp.port 5555 2>/dev/null || true", 3_000L) }
                }
            }

            val a11y = AccessibilityGate.isEnabled(appContext)

            val statusText = when {
                ready && a11y -> "ADB Connected + Auto-Stop ON"
                ready -> "ADB Connected (${adb.serial})"
                a11y && listening -> "Auto-Stop ON (Click Connect ADB)"
                a11y -> "Auto-Stop Ready"
                listening -> "ADB Port 5555 Open — Click Connect ADB"
                else -> "ADB Setup Required"
            }

            val hintText = when {
                ready -> "Silent ADB Kill & Reboot active."
                a11y -> "Auto-Stop active. Connect ADB to enable Reboot & instant silent kill."
                else -> "Follow the on-screen tutorial to enable Local ADB."
            }

            val newState = AdbConnectionState(
                connected = ready,
                portOpen = listening,
                devOptionsEnabled = isDevOptionsEnabled(),
                adbSettingEnabled = isAdbSettingEnabled(),
                accessibilityReady = a11y,
                probing = false,
                initialProbeComplete = true,
                serial = adb.serial,
                statusText = statusText,
                hintText = hintText,
            )
            _state.value = newState
            newState
        }
    }

    private suspend fun autoEnableAccessibilityViaAdb() {
        val comp = ComponentName(
            appContext,
            ForceStopAccessibilityService::class.java,
        ).flattenToString()
        val cmd = buildString {
            append("setprop service.adb.tcp.port 5555 2>/dev/null || true; ")
            append("CUR=\$(settings get secure enabled_accessibility_services); ")
            append("if [ \"\$CUR\" = \"null\" ] || [ -z \"\$CUR\" ]; then ")
            append("settings put secure enabled_accessibility_services '$comp'; ")
            append("elif ! echo \"\$CUR\" | grep -q '$comp'; then ")
            append("settings put secure enabled_accessibility_services \"\$CUR:$comp\"; ")
            append("fi; ")
            append("settings put secure accessibility_enabled 1")
        }
        runCatching { adb.run(cmd, 5_000L) }
    }

    fun openAboutSettings(): Boolean = startSettingsIntent(
        Settings.ACTION_DEVICE_INFO_SETTINGS,
        Settings.ACTION_SETTINGS,
    )

    fun openDeveloperSettings(): Boolean = startSettingsIntent(
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        Settings.ACTION_DEVICE_INFO_SETTINGS,
        Settings.ACTION_SETTINGS,
    )

    fun openAccessibilitySettings(): Boolean = startSettingsIntent(
        Settings.ACTION_ACCESSIBILITY_SETTINGS,
        Settings.ACTION_SETTINGS,
    )

    private fun startSettingsIntent(vararg actions: String): Boolean {
        for (action in actions) {
            val ok = runCatching {
                AccessibilityGate.markLaunchingExternalActivity()
                val intent = Intent(action).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                appContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}

package com.atvpm.tvcontrol.core.backend

import android.content.Context
import com.atvpm.tvcontrol.core.CommandRunner
import com.atvpm.tvcontrol.core.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Built-in No-Root Local ADB Backend (`127.0.0.1:5555`).
 *
 * Connects directly to the TV's local ADB daemon using [LocalAdbClient] (pure-Kotlin
 * ADB TCP protocol with RSA-2048 authentication). Requires no root, no PC, and no
 * external helper apps—just USB/Network Debugging enabled in Developer Options.
 */
class AdbBackend(
    context: Context,
    val host: String = "127.0.0.1",
    val port: Int = 5555,
) : CommandRunner {

    private val localSocketClient = LocalAdbClient(context, host, port)

    override val id: String = "adb"
    override val label: String get() = "Local ADB ($host:$port)"

    val serial: String get() = "$host:$port"

    val isAdBdListening: Boolean
        get() = localSocketClient.isPortListening()

    @Volatile
    private var authorizedAndVerified: Boolean = false

    override val isReady: Boolean
        get() = authorizedAndVerified

    /**
     * Probes `127.0.0.1:5555` and triggers the on-TV "Allow USB debugging?" prompt
     * if not yet authorized.
     */
    suspend fun probeConnection(timeoutMs: Long = 12_000L): Boolean =
        withContext(Dispatchers.IO) {
            if (!localSocketClient.isPortListening()) {
                authorizedAndVerified = false
                return@withContext false
            }
            val res = localSocketClient.exec("id", timeoutMs)
            val ok = res.ok && res.stdout.contains("uid=")
            authorizedAndVerified = ok
            ok
        }

    override suspend fun run(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            if (!localSocketClient.isPortListening()) {
                authorizedAndVerified = false
                return@withContext ShellResult.unavailable(command)
                    .copy(stderr = "ADB port $serial is closed. Turn on USB/Network Debugging in Developer Options.")
            }
            val res = localSocketClient.exec(command, timeoutMs)
            if (res.executed) {
                authorizedAndVerified = true
            }
            res
        }
}

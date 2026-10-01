package com.atvpm.tvcontrol.core

interface CommandRunner {

    val id: String
    val label: String
    val isReady: Boolean

    suspend fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 20_000
        const val LONG_TIMEOUT_MS: Long = 60_000
    }
}

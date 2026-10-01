package com.atvpm.tvcontrol.core

/** Result of running a single shell command through some [CommandRunner]. */
data class ShellResult(
    val command: String,
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val failure: Throwable? = null,
    /** True when the runner could not even attempt the command. */
    val unavailable: Boolean = false,
) {
    val executed: Boolean get() = !unavailable && failure == null
    val ok: Boolean get() = executed && exitCode == 0

    val combined: String get() = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(stderr.trimEnd())
        }
    }

    /** Short human-readable reason this did not succeed. */
    fun reason(): String = when {
        unavailable -> "backend unavailable"
        failure != null -> failure?.message ?: failure::class.java.simpleName
        stderr.isNotBlank() -> stderr.lineSequence().first { it.isNotBlank() }.trim()
        stdout.isNotBlank() -> stdout.lineSequence().first { it.isNotBlank() }.trim()
        else -> "exit $exitCode"
    }

    companion object {
        fun unavailable(command: String) =
            ShellResult(command = command, unavailable = true, exitCode = -1)
    }
}

package com.example.stepshift.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val isSuccess: Boolean = exitCode == 0
)

/**
 * High-performance interactive Root (SU) shell manager.
 * Uses a single persistent su process with token-based synchronization.
 */
class RootShellExecutor private constructor() {

    private var suProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val isExecuting = AtomicBoolean(false)

    @Synchronized
    private fun ensureShellOpen(): Boolean {
        if (suProcess != null) {
            try {
                // Check if still alive
                suProcess!!.exitValue()
                // Process terminated, reset
                destroyShell()
            } catch (e: IllegalThreadStateException) {
                // Process still alive
                return true
            }
        }

        return try {
            // stderr is merged into stdout: a separate error stream that is never
            // drained can fill its pipe buffer and deadlock the whole su process.
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            suProcess = process
            writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            true
        } catch (e: Exception) {
            destroyShell()
            false
        }
    }

    @Synchronized
    private fun destroyShell() {
        try {
            writer?.close()
            reader?.close()
            suProcess?.destroy()
        } catch (ignored: Exception) {
        } finally {
            writer = null
            reader = null
            suProcess = null
        }
    }

    /**
     * Test whether device has root (su) access.
     * Strict check: only uid=0 counts — a successful non-root `id` (e.g. su denied
     * but executed as shell on some ROMs) must NOT be reported as root.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val res = execute("id")
        res.isSuccess && res.stdout.any { it.contains("uid=0") }
    }

    /**
     * Execute a shell command through the persistent root shell.
     */
    suspend fun execute(command: String): RootCommandResult = withContext(Dispatchers.IO) {
        synchronized(this@RootShellExecutor) {
            if (!ensureShellOpen()) {
                return@withContext RootCommandResult(-1, emptyList(), listOf("Failed to spawn su process"))
            }

            val token = "CMD_END_" + UUID.randomUUID().toString().replace("-", "")
            val outLines = mutableListOf<String>()
            val errLines = mutableListOf<String>()

            try {
                val outWriter = writer ?: return@withContext RootCommandResult(-1, emptyList(), listOf("Writer is null"))
                val inReader = reader ?: return@withContext RootCommandResult(-1, emptyList(), listOf("Reader is null"))

                // Write the command followed by echo sentinel token with exit code $?
                outWriter.write(command)
                outWriter.newLine()
                outWriter.write("echo \"$token:$?\"")
                outWriter.newLine()
                outWriter.flush()

                // Read output until the token is encountered
                var exitCode = -1
                while (true) {
                    val line = inReader.readLine() ?: break
                    if (line.startsWith(token)) {
                        val parts = line.split(":")
                        if (parts.size >= 2) {
                            exitCode = parts[1].trim().toIntOrNull() ?: 0
                        } else {
                            exitCode = 0
                        }
                        break
                    } else {
                        outLines.add(line)
                    }
                }

                RootCommandResult(
                    exitCode = exitCode,
                    stdout = outLines,
                    stderr = errLines
                )
            } catch (e: Exception) {
                destroyShell()
                RootCommandResult(-1, outLines, listOf("Execution error: ${e.message}"))
            }
        }
    }

    /**
     * Clean up shell process.
     */
    fun close() {
        destroyShell()
    }

    companion object {
        val instance by lazy { RootShellExecutor() }
    }
}

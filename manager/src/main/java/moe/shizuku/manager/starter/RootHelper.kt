package moe.shizuku.manager.starter

import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ShizukuSettings

/**
 * Centralized helper for executing [Starter.internalCommand] with root.
 *
 * - Empty custom su: use libsu's default auto-detect behavior.
 * - Non-empty custom su: invoke that binary directly with argv `["<custom>", "-c", "<cmd>"]`
 *   (no file-existence check). Using argv avoids any shell quoting issues;
 *   su's own `-c` flag is guaranteed by its usage line.
 */
object RootHelper {

    private const val TAG = "RootHelper"

    @JvmStatic
    fun isRoot(): Boolean {
        val custom = getCustomSu()
        if (custom.isNotEmpty()) return true
        return Shell.getShell().isRoot
    }

    @JvmStatic
    fun getCustomSu(): String {
        return runCatching { ShizukuSettings.getCustomSu()?.trim().orEmpty() }
            .getOrDefault("")
    }

    @JvmStatic
    fun executeAsRoot(onLine: (String) -> Unit, onError: (Int) -> Unit) {
        val custom = getCustomSu()
        if (custom.isEmpty()) {
            Shell.cmd(Starter.internalCommand)
                .to(object : CallbackList<String?>() {
                    override fun onAddElement(s: String?) {
                        onLine(s ?: "")
                    }
                })
                .submit { result -> onError(result.code) }
            return
        }

        val cmd = Starter.internalCommand
        Log.d(TAG, "Using custom su: $custom  cmd: $cmd")

        runCatching {
            // execve-equivalent: no shell involved, no quoting to worry about.
            // su receives argv[1]="-c" argv[2]="<cmd>" — its -c flag is used
            // to pass <cmd> to its own invoked shell for execution as root.
            val process = ProcessBuilder(listOf(custom, "-c", cmd)).start()
            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use {
                    it.lineSequence().forEach { line -> onLine(line) }
                }
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().use {
                    it.lineSequence().forEach { line -> onLine("[err] $line") }
                }
            }
            stdoutThread.start()
            stderrThread.start()
            stdoutThread.join()
            stderrThread.join()
            onError(process.waitFor())
        }.onFailure {
            Log.e(TAG, "Custom su execution failed", it)
            onLine("[error] ${it.message}")
            onError(-1)
        }
    }

    @JvmStatic
    fun execInternalCommand() {
        executeAsRoot({}, {})
    }
}

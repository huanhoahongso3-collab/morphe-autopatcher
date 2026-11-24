package dhp.morphe.updater

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

data class PrivStatus(val shizukuRunning: Boolean = false, val shizukuGranted: Boolean = false, val rootAvailable: Boolean = false)

/** Silent installs through Shizuku (shell uid) or root. Everything here blocks: call from a background thread. */
object Privileged {
    const val PERMISSION_REQUEST = 4711

    private fun shizukuRunning() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    private fun shizukuGranted() =
        shizukuRunning() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestShizuku() {
        runCatching { if (shizukuRunning() && !shizukuGranted()) Shizuku.requestPermission(PERMISSION_REQUEST) }
    }

    /** Spawning `su` is slow and can pop a root prompt, so it only runs when [checkRoot] is true (otherwise [knownRoot] is kept). */
    fun status(checkRoot: Boolean, knownRoot: Boolean): PrivStatus =
        PrivStatus(shizukuRunning(), shizukuGranted(), if (checkRoot) rootAvailable() else knownRoot)

    fun rootAvailable(): Boolean = runCatching {
        val (code, out) = run(InstallMode.ROOT, "id -u", null)
        code == 0 && out.trim().endsWith("0")
    }.getOrDefault(false)

    fun usable(mode: InstallMode) = when (mode) {
        InstallMode.NORMAL -> false
        InstallMode.SHIZUKU -> shizukuGranted()
        InstallMode.ROOT -> rootAvailable()
    }

    /** Runs `sh -c cmd` as shell (Shizuku) or root; optionally feeds [stdin] into it. Returns (exit code, output). */
    private fun run(mode: InstallMode, cmd: String, stdin: File?): Pair<Int, String> {
        val proc: Process = when (mode) {
            InstallMode.SHIZUKU -> {
                // Shizuku.newProcess is private since API 13 but still the simplest way to run a shell command.
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
                ).apply { isAccessible = true }
                m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            }
            InstallMode.ROOT -> ProcessBuilder("su", "-c", cmd).start()
            InstallMode.NORMAL -> error("not a privileged mode")
        }
        val err = StringBuilder()
        val t = Thread { runCatching { err.append(proc.errorStream.bufferedReader().readText()) } }.apply { start() }
        if (stdin != null) stdin.inputStream().use { i -> proc.outputStream.use { o -> i.copyTo(o) } } else proc.outputStream.close()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(5, TimeUnit.MINUTES)
        t.join(2000)
        return proc.exitValue() to (out + err).trim()
    }

    /** Installs [apk] over the existing app. Returns null on success, else the failure text. */
    fun install(mode: InstallMode, apk: File): String? {
        val (code, text) = run(mode, "pm install -r -d -S ${apk.length()}", apk)
        return if (code == 0 && text.contains("Success", true)) null else text.ifBlank { "pm install exit $code" }
    }

    fun uninstall(mode: InstallMode, pkg: String): Boolean {
        val (code, text) = run(mode, "pm uninstall $pkg", null)
        return code == 0 && text.contains("Success", true)
    }
}

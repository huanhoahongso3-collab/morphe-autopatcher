package dhp.morphe.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

object Installer {
    private const val ACTION = "dhp.morphe.updater.INSTALL_RESULT"

    /** Installs [apk] via PackageInstaller. [onDone] gets (success, message). */
    fun install(ctx: Context, id: String, apk: File, onDone: (Boolean, String?, Int) -> Unit) {
        val mode = Settings.mode(ctx)
        if (mode != InstallMode.NORMAL) {
            // Silent install via Shizuku/root; anything unexpected falls back to the normal prompt below.
            Thread {
                val usable = runCatching { Privileged.usable(mode) }.getOrDefault(false)
                val err = if (usable) runCatching { Privileged.install(mode, apk) }.getOrElse { it.message ?: "failed" } else "unavailable"
                when {
                    usable && err == null -> { apk.delete(); onDone(true, null, 0) }
                    usable && ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in err!! || "signatures" in err) ->
                        onDone(false, err, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                    else -> installNormal(ctx, id, apk, onDone)
                }
            }.start()
            return
        }
        installNormal(ctx, id, apk, onDone)
    }

    private fun installNormal(ctx: Context, id: String, apk: File, onDone: (Boolean, String?, Int) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            apk.inputStream().use { i -> s.openWrite("pkg", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            c.startActivity(confirm)
                        }
                        PackageInstaller.STATUS_SUCCESS -> { c.unregisterReceiver(this); apk.delete(); onDone(true, null, 0) }
                        else -> {
                            c.unregisterReceiver(this)
                            onDone(
                                false,
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1),
                            )
                        }
                    }
                }
            }
            val action = "$ACTION.$id.$sid"
            val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
            ctx.registerReceiver(receiver, IntentFilter(action), flags)
            val pending = PendingIntent.getBroadcast(
                ctx, sid, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pending.intentSender)
        }
    }
}

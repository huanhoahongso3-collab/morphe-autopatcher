package dhp.morphe.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger

/** Notifications for update progress/results. All calls are safe without the notification permission. */
object Notifier {
    private const val CHANNEL = "updates"
    const val PROGRESS_ID = 42
    private const val RESULT_ID = 43
    private const val ACTION_ID = 44

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm(ctx).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Updates", "Cập nhật"), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** [pct] null = indeterminate (installing). */
    fun build(ctx: Context, title: String, text: String, pct: Int?): Notification {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct ?: 0, pct == null)
            .setContentIntent(openApp(ctx))
            .build()
    }

    private fun notifyOk(ctx: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { nm(ctx).notify(id, n) }
    }

    fun progress(ctx: Context, name: String, pct: Int?) {
        val text = if (pct == null) tr("Installing…", "Đang cài đặt…") else tr("Downloading $pct%", "Đang tải $pct%")
        notifyOk(ctx, PROGRESS_ID, build(ctx, name, text, pct))
    }

    fun finish(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        nm(ctx).cancel(PROGRESS_ID)
        notifyOk(
            ctx, RESULT_ID,
            NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle(title).setContentText(text).setAutoCancel(true)
                .setContentIntent(openApp(ctx)).build(),
        )
    }

    /** Android blocked opening the install confirmation from the background: let the user tap to continue. */
    fun confirmInstall(ctx: Context, name: String, intent: Intent) {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(ctx, name.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifyOk(
            ctx, ACTION_ID,
            NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle(name).setContentText(tr("Tap to confirm the install", "Chạm để xác nhận cài đặt"))
                .setAutoCancel(true).setContentIntent(pi).build(),
        )
    }
}

/** Process-wide scope so downloads/installs keep running after the screen is closed. */
object UpdateEngine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Foreground service that keeps the process alive (and shows the progress notification) while updates run. */
class UpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.build(this, tr("App Updater", "Trình Cập Nhật"), tr("Starting…", "Đang bắt đầu…"), null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(Notifier.PROGRESS_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private val active = AtomicInteger(0)

        fun begin(ctx: Context) {
            if (active.getAndIncrement() == 0) {
                runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, UpdateService::class.java)) }
            }
        }

        fun end(ctx: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                ctx.stopService(Intent(ctx, UpdateService::class.java))
            }
        }
    }
}

package dhp.morphe.updater

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Installs every pending update silently (apps first, the manager itself last). Needs Shizuku or Root. */
object AutoUpdate {
    /** @return number of installs that succeeded, or -1 when no privileged mode is usable. */
    suspend fun runNow(ctx: Context): Int {
        val mode = Settings.mode(ctx)
        if (!Privileged.usable(mode)) return -1
        val items = Repository.check(ctx).filter { it.status == Status.UPDATE }   // CONFLICT items are never touched
        var ok = 0
        for (it in items.sortedBy { i -> if (i.kind == Kind.MANAGER) 1 else 0 }) {
            val file = runCatching { Repository.download(ctx, it) { } }.getOrNull() ?: continue
            val err = Privileged.install(mode, file)
            file.delete()
            if (err == null) {
                ok++
                (it.availablePatches ?: it.availableApp).let { v -> Repository.savePatches(ctx, it.id, v) }
            }
        }
        return ok
    }

    /** (Re)schedules or cancels the twice-a-week background job to match the setting. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (Settings.autoUpdate(ctx)) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(84, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork("auto-update", ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork("auto-update")
    }
}

class UpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        if (!Settings.autoUpdate(applicationContext)) return Result.success()
        return runCatching { AutoUpdate.runNow(applicationContext) }.fold({ Result.success() }, { Result.retry() })
    }
}

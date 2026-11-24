package dhp.morphe.updater

import android.content.Context

enum class InstallMode { NORMAL, SHIZUKU, ROOT }

/** Small SharedPreferences wrapper for the manager's settings. */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun mode(ctx: Context): InstallMode =
        runCatching { InstallMode.valueOf(p(ctx).getString("mode", "NORMAL")!!) }.getOrDefault(InstallMode.NORMAL)
    fun setMode(ctx: Context, m: InstallMode) = p(ctx).edit().putString("mode", m.name).apply()

    /** Background check + install twice a week (needs Shizuku or Root). */
    fun autoUpdate(ctx: Context) = p(ctx).getBoolean("autoUpdate", false)
    fun setAutoUpdate(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("autoUpdate", v).apply()

    /** Check + install as soon as the manager opens (needs Shizuku or Root). */
    fun autoUpdateOnStart(ctx: Context) = p(ctx).getBoolean("autoUpdateOnStart", false)
    fun setAutoUpdateOnStart(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("autoUpdateOnStart", v).apply()
}

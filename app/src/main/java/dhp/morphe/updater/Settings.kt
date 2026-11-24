package dhp.morphe.updater

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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

/** UI text: Vietnamese when the phone language is Vietnamese, English otherwise. */
fun tr(en: String, vi: String): String = if (Lang.isVietnamese()) vi else en

/** Language choice: SYSTEM follows the phone (Vietnamese if the phone is Vietnamese, otherwise English). */
enum class AppLang { SYSTEM, ENGLISH, VIETNAMESE }

object Lang {
    // Compose state, so changing it recomposes every screen that calls tr().
    var current by androidx.compose.runtime.mutableStateOf(AppLang.SYSTEM)

    fun load(ctx: Context) {
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "SYSTEM")
        current = runCatching { AppLang.valueOf(s!!) }.getOrDefault(AppLang.SYSTEM)
    }

    fun set(ctx: Context, l: AppLang) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", l.name).apply()
        current = l
    }

    fun isVietnamese() = when (current) {
        AppLang.VIETNAMESE -> true
        AppLang.ENGLISH -> false
        AppLang.SYSTEM -> java.util.Locale.getDefault().language == "vi"   // anything else falls back to English
    }
}

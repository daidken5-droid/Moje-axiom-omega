package cz.axiom.omega.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import cz.axiom.omega.MainActivity
import cz.axiom.omega.R
import cz.axiom.omega.core.device.Power
import cz.axiom.omega.data.Settings

/**
 * Widget AVIORu na plochu: stav a učení, poslední odpověď, „Zeptej se“, hlas a přepínač výkonu
 * (Základ → Pokročilé → Sherlock) přímo na ploše bez otevření aplikace.
 */
class AviorWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_POWER) {
            val s = Settings(context)
            val next = Power.entries[(Power.of(s.power).ordinal + 1) % Power.entries.size]
            s.power = next.name
            refresh(context)
        }
    }

    companion object {
        const val ACTION_POWER = "cz.axiom.omega.widget.POWER"
        const val ACTION_ASK = "cz.axiom.omega.ASK"
        const val ACTION_VOICE = "cz.axiom.omega.VOICE"
        const val ACTION_SELF = "cz.axiom.omega.SELF"
        private const val PREFS = "widget"

        /** Uloží poslední odpověď a překreslí widgety (volá služba po dokončení odpovědi). */
        fun publish(context: Context, question: String, answer: String, status: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("q", question.take(120))
                .putString("a", answer.replace(Regex("[*#`>|]"), "").replace(Regex("\\s+"), " ").trim().take(240))
                .putString("status", status ?: "")
                .apply()
            refresh(context)
        }

        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, AviorWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { m.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_avior)
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val power = Power.of(Settings(context).power)
            v.setTextViewText(R.id.w_power, when (power) { Power.BASIC -> "◇ ZÁKLAD"; Power.ADVANCED -> "◈ POKROČILÉ"; Power.SHERLOCK -> "⌕ SHERLOCK" })
            p.getString("status", null)?.takeIf { it.isNotBlank() }?.let { v.setTextViewText(R.id.w_status, it.uppercase()) }
            val a = p.getString("a", null)
            if (!a.isNullOrBlank()) v.setTextViewText(R.id.w_last, "„${p.getString("q", "")}“ — $a")
            v.setOnClickPendingIntent(R.id.w_ask, activity(context, ACTION_ASK, 1))
            v.setOnClickPendingIntent(R.id.w_last, activity(context, ACTION_ASK, 2))
            v.setOnClickPendingIntent(R.id.w_voice, activity(context, ACTION_VOICE, 3))
            v.setOnClickPendingIntent(R.id.w_status, activity(context, ACTION_SELF, 4))
            v.setOnClickPendingIntent(
                R.id.w_power,
                PendingIntent.getBroadcast(context, 5, Intent(context, AviorWidget::class.java).setAction(ACTION_POWER),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            return v
        }

        private fun activity(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(context, code, Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

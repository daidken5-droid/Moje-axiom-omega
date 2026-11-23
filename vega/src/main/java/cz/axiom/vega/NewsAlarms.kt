package cz.axiom.vega

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cz.axiom.vega.news.News
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Ranní přehled zpráv – plánování: pokusy v 05:20, 05:24 a 05:28, notifikace v 05:30 (Europe/Prague).
 * AlarmManager.setExactAndAllowWhileIdle (bez povolení přesných budíků náhradně setAndAllowWhileIdle – může se zpozdit).
 * Po restartu telefonu, změně času/zóny a aktualizaci aplikace se plán obnoví ([NewsReceiver]).
 */
object NewsAlarms {
    const val ATTEMPT = "cz.axiom.vega.news.ATTEMPT"
    const val NOTIFY = "cz.axiom.vega.news.NOTIFY"
    const val RETRY = "cz.axiom.vega.news.RETRY"
    private val ATTEMPT_MIN = listOf(20, 24, 28)
    const val CHANNEL = "news"

    fun exactAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)

    /** Den příštího přehledu: dnes, pokud ještě nebylo 05:30, jinak zítra. */
    fun nextDay(now: ZonedDateTime = ZonedDateTime.now(News.ZONE)): LocalDate {
        val l = now.withZoneSameInstant(News.ZONE)
        return if (l.toLocalTime() < java.time.LocalTime.of(5, 30)) l.toLocalDate() else l.toLocalDate().plusDays(1)
    }
    fun at(day: LocalDate, minute: Int): Long = day.atTime(5, minute).atZone(News.ZONE).toInstant().toEpochMilli()

    private fun pi(ctx: Context, action: String, day: LocalDate, attempt: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(ctx, (action.hashCode() * 31 + attempt), Intent(ctx, NewsReceiver::class.java).setAction(action).putExtra("day", day.toString()).putExtra("n", attempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun set(ctx: Context, atMs: Long, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (exactAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p)
    }

    /** Naplánuje první pokus (05:20) a notifikaci (05:30) příštího dne; vypnuto → zruší vše. */
    fun schedule(ctx: Context, enabled: Boolean, now: ZonedDateTime = ZonedDateTime.now(News.ZONE)) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val day = nextDay(now)
        ATTEMPT_MIN.indices.forEach { am.cancel(pi(ctx, ATTEMPT, day, it)) }; am.cancel(pi(ctx, NOTIFY, day))
        if (!enabled) return
        val first = ATTEMPT_MIN.indexOfFirst { at(day, it) > now.toInstant().toEpochMilli() }
        if (first >= 0) set(ctx, at(day, ATTEMPT_MIN[first]), pi(ctx, ATTEMPT, day, first))
        set(ctx, at(day, 30), pi(ctx, NOTIFY, day))
    }

    /** Další pokus po neúspěchu (05:24, 05:28); false = už nezbývá. */
    fun scheduleRetry(ctx: Context, day: LocalDate, n: Int): Boolean {
        val next = n + 1; if (next >= ATTEMPT_MIN.size) return false
        set(ctx, at(day, ATTEMPT_MIN[next]), pi(ctx, ATTEMPT, day, next)); return true
    }

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ranní přehled zpráv", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Souhrn zpráv za posledních 24 hodin v 05:30" })
    }

    fun notify(ctx: Context, ok: Boolean, text: String, chat: String?) {
        channel(ctx)
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { if (chat != null) putExtra("openChat", chat) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx))
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(if (ok) "Ranní přehled zpráv" else "Ranní přehled se nepodařil")
            .setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        if (!ok) b.addAction(android.app.Notification.Action.Builder(null, "Zkusit znovu",
            PendingIntent.getBroadcast(ctx, 9, Intent(ctx, NewsReceiver::class.java).setAction(RETRY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        ctx.getSystemService(NotificationManager::class.java)?.notify(530, b.build())
    }
}

/** Alarmy přehledu + obnova plánu po restartu / změně času. Práce běží mimo hlavní vlákno (goAsync), nejvýš ~50 s. */
class NewsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val s = Settings(app)
        when (intent.action) {
            NewsAlarms.ATTEMPT, NewsAlarms.NOTIFY, NewsAlarms.RETRY -> {}
            else -> { NewsAlarms.schedule(app, s.newsEnabled); return }  // BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED, povolení přesných budíků (přeplánovat přesně)
        }
        if (!s.newsEnabled && intent.action != NewsAlarms.RETRY) return
        val pending = goAsync()
        Thread {
            try {
                val ops = (app as VegaApp).opsHeadless()
                val day = intent.getStringExtra("day")?.let { LocalDate.parse(it) } ?: LocalDate.now(News.ZONE)
                when (intent.action) {
                    NewsAlarms.ATTEMPT -> if (!ops.newsDoneFor(day)) { val r = ops.newsDigest()
                        if (!r.ok) NewsAlarms.scheduleRetry(app, day, intent.getIntExtra("n", 0)) }
                    NewsAlarms.NOTIFY -> { val r = ops.newsLast(day)?.takeIf { it.ok } ?: ops.newsDigest()  // poslední šance: pokusy selhaly nebo neproběhly (Doze)
                        NewsAlarms.notify(app, r.ok, r.text, r.chat) }
                    NewsAlarms.RETRY -> { val r = ops.newsDigest(); NewsAlarms.notify(app, r.ok, r.text, r.chat) }
                }
            } catch (e: Throwable) {
                runCatching { NewsAlarms.notify(app, false, "Chyba: ${e.javaClass.simpleName}. Zkus to znovu.", null) }
            } finally {
                // plán na zítřek i po výjimce – jinak by přehled přestal chodit, dokud uživatel neotevře aplikaci
                if (intent.action == NewsAlarms.NOTIFY) runCatching { NewsAlarms.schedule(app, s.newsEnabled) }
                pending?.finish()
            }
        }.start()
    }
}

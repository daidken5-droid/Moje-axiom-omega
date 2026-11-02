package cz.axiom.omega.phone

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import cz.axiom.omega.core.phone.PhoneCommands
import cz.axiom.omega.core.phone.PhoneCommands.Action
import cz.axiom.omega.core.util.TextUtil
import java.io.File
import java.util.concurrent.TimeUnit

/** Root shell (jen když má telefon su – Magisk/KernelSU). Každý příkaz potvrzuje uživatel. */
object RootShell {
    private val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/su/bin/su")
    fun present() = paths.any { File(it).exists() }

    data class Out(val code: Int, val text: String)

    fun run(cmd: String, timeoutSec: Long = 20): Out {
        if (!present()) return Out(-1, "Telefon nemá root (su nenalezeno).")
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 8_000) out.appendLine(it) } } }.apply { start() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return Out(-2, "Příkaz nedoběhl do $timeoutSec s.") }
            reader.join(1_000)
            Out(p.exitValue(), out.toString().trim())
        } catch (e: Exception) {
            Out(-3, "Root odmítnut nebo nedostupný: ${e.message}")
        }
    }

    /** Ověří, že su opravdu dává uid 0 (Magisk se zeptá na povolení). */
    fun granted(): Boolean = run("id", 10).let { it.code == 0 && "uid=0" in it.text }
}

/**
 * Provádí povely z [PhoneCommands]. Bez rootu jen to, co Android aplikacím dovolí; kde Android přímé
 * přepnutí zakazuje (Wi-Fi, Bluetooth, letadlo), otevře systémový panel – s rootem přepne přímo.
 */
class PhoneControl(private val context: Context) {

    data class Result(val handled: Boolean, val text: String)

    private fun ok(t: String) = Result(true, t)
    private fun start(i: Intent): Boolean = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    fun execute(c: PhoneCommands.Command): Result = runCatching { run(c) }.getOrElse { Result(true, "Nepodařilo se: ${it.message ?: it.javaClass.simpleName}") }

    private fun run(c: PhoneCommands.Command): Result = when (c.action) {
        Action.TORCH -> {
            val cm = context.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return ok("Telefon nemá přisvícení.")
            val on = c.args["on"] == "true"
            cm.setTorchMode(id, on)
            ok(if (on) "🔦 Svítilna zapnuta." else "Svítilna vypnuta.")
        }
        Action.VOLUME -> {
            val am = context.getSystemService(AudioManager::class.java)
            val s = AudioManager.STREAM_MUSIC
            when (c.args["mode"]) {
                "set" -> { val pct = c.args["pct"]!!.toInt(); am.setStreamVolume(s, am.getStreamMaxVolume(s) * pct / 100, AudioManager.FLAG_SHOW_UI); ok("Hlasitost médií $pct %.") }
                "mute" -> { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); ok("Zvuk médií ztlumen.") }
                "unmute" -> { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); ok("Zvuk zapnut.") }
                "up" -> { repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }; ok("Zesíleno.") }
                else -> { repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }; ok("Zeslabeno.") }
            }
        }
        Action.MEDIA -> {
            val am = context.getSystemService(AudioManager::class.java)
            val key = when (c.args["key"]) { "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            ok(c.label + ".")
        }
        Action.ALARM -> {
            val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, c.args["h"]!!.toInt())
                .putExtra(AlarmClock.EXTRA_MINUTES, c.args["m"]!!.toInt()).putExtra(AlarmClock.EXTRA_MESSAGE, "AVIOR").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            ok(if (start(i)) "⏰ ${c.label}." else "Aplikace Hodiny není dostupná.")
        }
        Action.TIMER -> {
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.args["sec"]!!.toInt())
                .putExtra(AlarmClock.EXTRA_MESSAGE, "AVIOR").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            ok(if (start(i)) "⏱ ${c.label}." else "Aplikace Hodiny není dostupná.")
        }
        Action.SETTINGS -> ok(if (start(Intent(settingsAction(c.args["section"] ?: ""))) || start(Intent(Settings.ACTION_SETTINGS))) "Otevírám ${c.label.lowercase().removePrefix("otevřít ")}." else "Nastavení nejde otevřít.")
        Action.WIFI -> toggleViaRootOrPanel("svc wifi ${en(c)}", if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS, c, "Wi-Fi")
        Action.BLUETOOTH -> toggleViaRootOrPanel("svc bluetooth ${en(c)}", Settings.ACTION_BLUETOOTH_SETTINGS, c, "Bluetooth")
        Action.AIRPLANE -> toggleViaRootOrPanel("cmd connectivity airplane-mode ${en(c)}", Settings.ACTION_AIRPLANE_MODE_SETTINGS, c, "Režim letadlo")
        Action.DND -> {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                ok("Povol AVIORu přístup k „Nerušit“ (otevřel jsem nastavení) a zopakuj povel.")
            } else {
                val on = c.args["on"] == "true"
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                ok(if (on) "🔕 Nerušit zapnuto." else "Nerušit vypnuto.")
            }
        }
        Action.BRIGHTNESS -> {
            if (!Settings.System.canWrite(context)) {
                start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
                ok("Povol AVIORu „Měnit nastavení systému“ (otevřel jsem ho) a zopakuj povel.")
            } else {
                val pct = c.args["pct"]!!.toInt()
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(1, 255))
                ok("☀ Jas $pct % (automatický jas vypnut).")
            }
        }
        Action.LAUNCH -> {
            val pkg = findApp(c.args["app"] ?: "") ?: return Result(false, "")
            val i = context.packageManager.getLaunchIntentForPackage(pkg.first) ?: return Result(false, "")
            ok(if (start(i)) "Spouštím ${pkg.second}." else "Aplikaci ${pkg.second} nejde spustit.")
        }
        Action.DIAL -> ok(if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.args["number"])))) "📞 Číslo připraveno – hovor potvrď zeleným tlačítkem." else "Telefon nejde otevřít.")
        Action.STATUS -> ok(status())
        Action.ROOT_STATUS -> ok(if (!RootShell.present()) "Telefon **nemá root** (su nenalezeno). Bez rootu ovládám vše, co Android aplikacím dovolí." else if (RootShell.granted()) "Root je **aktivní** a AVIOR ho smí použít (každý root příkaz potvrzuješ)." else "su existuje, ale přístup nebyl povolen (povol AVIOR v Magisku / KernelSU).")
        Action.ROOT_KILL_BACKGROUND -> rootOk(RootShell.run("am kill-all"), "Aplikace na pozadí ukončeny (am kill-all).")
        Action.ROOT_FORCE_STOP -> {
            val pkg = findApp(c.args["app"] ?: "") ?: return ok("Aplikaci „${c.args["app"]}“ jsem nenašel.")
            rootOk(RootShell.run("am force-stop ${pkg.first}"), "Aplikace ${pkg.second} násilně ukončena.")
        }
        Action.ROOT_DROP_CACHES -> rootOk(RootShell.run("sync; echo 3 > /proc/sys/vm/drop_caches"), "Systémová mezipaměť uvolněna.")
        Action.ROOT_REBOOT -> rootOk(RootShell.run("svc power reboot || reboot"), "Restartuji telefon…")
        Action.ROOT_SHELL -> RootShell.run(c.args["cmd"] ?: "").let { ok("**root** `${c.args["cmd"]}` → kód ${it.code}\n```\n${TextUtil.clip(it.text.ifBlank { "(bez výstupu)" }, 3_000)}\n```") }
    }

    private fun en(c: PhoneCommands.Command) = if (c.args["on"] == "true") "enable" else "disable"

    private fun toggleViaRootOrPanel(rootCmd: String, panel: String, c: PhoneCommands.Command, name: String): Result {
        val on = c.args["on"] == "true"
        if (RootShell.present()) {
            val r = RootShell.run(rootCmd, 10)
            if (r.code == 0) return ok("$name ${if (on) "zapnuto" else "vypnuto"} (root).")
        }
        start(Intent(panel))
        return ok("Android aplikacím nedovolí $name přepnout přímo – otevřel jsem panel, stačí klepnout. (S rootem to udělám sám.)")
    }

    private fun rootOk(r: RootShell.Out, success: String) = ok(if (r.code == 0) success else "Root příkaz selhal (kód ${r.code}): ${TextUtil.clip(r.text, 300)}")

    private fun settingsAction(section: String): String = when (section) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "displej", "jas" -> Settings.ACTION_DISPLAY_SETTINGS
        "baterie" -> Intent.ACTION_POWER_USAGE_SUMMARY
        "zvuk" -> Settings.ACTION_SOUND_SETTINGS
        "aplikace" -> Settings.ACTION_APPLICATION_SETTINGS
        "uloziste" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        "poloha", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "oznameni", "notifikace" -> "android.settings.NOTIFICATION_SETTINGS"
        "vyvojar" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        "nerusit" -> "android.settings.ZEN_MODE_SETTINGS"
        "mobilni data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
        "sit" -> Settings.ACTION_WIRELESS_SETTINGS
        "nfc" -> Settings.ACTION_NFC_SETTINGS
        "hotspot" -> "android.settings.TETHER_SETTINGS"
        "zabezpeceni" -> Settings.ACTION_SECURITY_SETTINGS
        "soukromi" -> Settings.ACTION_PRIVACY_SETTINGS
        "jazyk" -> Settings.ACTION_LOCALE_SETTINGS
        "datum" -> Settings.ACTION_DATE_SETTINGS
        "pristupnost" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "aktualizace" -> "android.settings.SYSTEM_UPDATE_SETTINGS"
        "telefon" -> Settings.ACTION_DEVICE_INFO_SETTINGS
        "ucty" -> Settings.ACTION_SYNC_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }

    /** Najde nainstalovanou aplikaci podle názvu (bez diakritiky, i část názvu). → (balíček, název) */
    fun findApp(name: String): Pair<String, String>? {
        val q = TextUtil.fold(name).trim().removePrefix("aplikaci ").trim()
        if (q.length < 2) return null
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
        return apps.firstOrNull { TextUtil.fold(it.second) == q }
            ?: apps.firstOrNull { TextUtil.fold(it.second).startsWith(q) }
            ?: apps.firstOrNull { TextUtil.fold(it.second).contains(q) }
            ?: apps.firstOrNull { it.first.lowercase().contains(q.replace(" ", "")) }
    }

    fun status(): String {
        val (bat, charging) = cz.axiom.omega.sys.Introspect.battery(context)
        val freeMb = cz.axiom.omega.sys.Introspect.freeRamMb(context)
        val totalMb = cz.axiom.omega.sys.Introspect.totalRamMb(context)
        val st = android.os.StatFs(context.filesDir.path)
        val freeGb = st.availableBytes / 1e9; val totalGb = st.totalBytes / 1e9
        val online = cz.axiom.omega.sys.Introspect.online(context)
        val thermal = cz.axiom.omega.sys.Introspect.thermalLevel(context)
        return buildString {
            append("**Stav telefonu**\n")
            append("- Baterie: ${bat ?: "?"} %${if (charging == true) " · nabíjí se" else ""}\n")
            append("- RAM: volno ${freeMb} MB z ${totalMb} MB\n")
            append("- Úložiště: volno %.1f GB z %.0f GB\n".format(freeGb, totalGb).replace('.', ','))
            append("- Síť: ${if (online) "online" else "offline"} · teplota: ${listOf("v normě", "mírně teplý", "teplý", "horký", "velmi horký", "kritický", "vypínání").getOrElse(thermal) { "?" }}\n")
            append("- Root: ${if (RootShell.present()) "ano (su)" else "ne"}")
        }
    }
}

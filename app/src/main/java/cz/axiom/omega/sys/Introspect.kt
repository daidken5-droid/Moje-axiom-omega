package cz.axiom.omega.sys

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Introspekce zařízení – to, co AVIOR "cítí" o svém těle. */
object Introspect {

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / 1_073_741_824.0
    }

    fun freeRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.availMem / 1_048_576
    }

    fun battery(context: Context): Pair<Int?, Boolean?> {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to (if (i == null) null else charging)
    }

    /** 0 = v normě … 4+ = kritické (pro RANDY Supervisor). */
    fun thermalLevel(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    }

    fun thermal(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "v normě"
            PowerManager.THERMAL_STATUS_LIGHT -> "mírně teplé"
            PowerManager.THERMAL_STATUS_MODERATE -> "teplé"
            PowerManager.THERMAL_STATUS_SEVERE -> "horké – zpomaluji"
            else -> "kritické"
        }
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"

    fun now(): String = SimpleDateFormat("EEEE d. M. yyyy HH:mm", Locale("cs", "CZ")).format(Date())
}

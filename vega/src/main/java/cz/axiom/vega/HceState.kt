package cz.axiom.vega

import android.content.Context
import java.io.File

/**
 * Aktivní virtuální karta VEGA pro HCE. Payload (zakódovaný CardApdu.encode) se drží v souboru, aby ho HostApduService
 * přečetl i po studeném startu procesu. „Žádná aktivní karta“ = soubor neexistuje → služba odpoví 6A82.
 * Obsah = jen to, co uživatel označil ke sdílení. UID telefonu se nikde nepoužívá.
 */
object HceState {
    private fun file(ctx: Context) = File(ctx.filesDir, "vega/hw/activecard.bin")

    @Volatile private var cache: ByteArray? = null
    @Volatile private var loaded = false

    fun activate(ctx: Context, payload: ByteArray) {
        file(ctx).apply { parentFile?.mkdirs() }.writeBytes(payload)
        cache = payload; loaded = true
    }

    /** Deaktivace musí přežít restart procesu: když smazání selže, soubor se vyprázdní (prázdný = žádná karta). */
    fun deactivate(ctx: Context) {
        val f = file(ctx)
        if (!runCatching { f.delete() || !f.exists() }.getOrDefault(false)) runCatching { f.writeBytes(ByteArray(0)) }
        cache = null; loaded = true
    }

    /** Pro službu: aktuální payload, nebo null. Čte z paměti, jinak ze souboru (po studeném startu). */
    fun payload(ctx: Context): ByteArray? {
        if (loaded) return cache
        cache = runCatching { file(ctx).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() } }.getOrNull(); loaded = true
        return cache
    }
}

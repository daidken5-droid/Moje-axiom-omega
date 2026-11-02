package cz.axiom.omega.core.update

import cz.axiom.omega.core.llm.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Kontrola nové verze AVIORu na GitHub Releases (stálý odkaz …/releases/latest/download/AVIOR.apk). */
object Updates {
    const val REPO = "daidken5-droid/Moje-axiom-omega"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/AVIOR.apk"
    const val PAGE_URL = "https://github.com/$REPO/releases/latest"

    /** [code] = číslo sestavení (versionCode) – podle něj se porovnává; [version] = to, co vidí uživatel („Delta I“). */
    data class Latest(val version: String, val code: Int, val apkUrl: String, val pageUrl: String)

    /**
     * Tag vydání → (popis, číslo sestavení):
     *  • „avior-delta-ii-c1001“ → („Delta II“, 1001)  – řada Delta (číslo sestavení je v tagu)
     *  • „avior-v8.9.0“          → („8.9.0“, 890)      – starší číslované verze (major·100 + minor·10 + patch)
     */
    fun parseTag(tag: String): Pair<String, Int>? {
        Regex("^avior-(.+)-c(\\d+)$").find(tag)?.let { m ->
            val label = m.groupValues[1].split('-').joinToString(" ") { w -> if (w.matches(Regex("[ivxlcdm]+"))) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
            return label to m.groupValues[2].toInt()
        }
        val n = Regex("\\d+(?:\\.\\d+)*").find(tag)?.value?.split('.')?.map { it.toInt() } ?: return null
        return n.joinToString(".") to (n.getOrElse(0) { 0 } * 100 + n.getOrElse(1) { 0 } * 10 + n.getOrElse(2) { 0 })
    }

    fun newer(remoteCode: Int, localCode: Int): Boolean = remoteCode > localCode

    fun latest(): Latest? {
        val c = Http.open("https://api.github.com/repos/$REPO/releases/latest", connectMs = 15_000, readMs = 20_000)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (c.responseCode != 200) return null
            val o = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
            if (!tag.startsWith("avior-")) return null
            val (label, code) = parseTag(tag) ?: return null
            Latest(label, code, APK_URL, o["html_url"]?.jsonPrimitive?.content ?: PAGE_URL)
        } finally {
            c.disconnect()
        }
    }
}

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

    data class Latest(val version: String, val apkUrl: String, val pageUrl: String)

    /** "avior-v4.1.0" / "v4.1.0" / "4.1.0-avior" → [4, 1, 0] */
    fun parse(v: String): List<Int> = Regex("\\d+(?:\\.\\d+)*").find(v)?.value?.split('.')?.map { it.toInt() } ?: emptyList()

    fun newer(remote: String, local: String): Boolean {
        val a = parse(remote); val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun latest(): Latest? {
        val c = Http.open("https://api.github.com/repos/$REPO/releases/latest", connectMs = 15_000, readMs = 20_000)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (c.responseCode != 200) return null
            val o = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
            if (!tag.startsWith("avior-")) return null
            Latest(parse(tag).joinToString("."), APK_URL, o["html_url"]?.jsonPrimitive?.content ?: PAGE_URL)
        } finally {
            c.disconnect()
        }
    }
}

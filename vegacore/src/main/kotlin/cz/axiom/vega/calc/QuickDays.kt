package cz.axiom.vega.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Kalendář bez modelu (sobota 28. 11., z Quick.kt): jmeniny z NameDays a svátky z CzCalendar. Vstup už složený bez diakritiky (Quick). */
object QuickDays {
    fun answer(t: String, today: LocalDate): String? = nameDay(t, today) ?: holidays(t, today)

    private val DAYS = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun daysCz(n: Long) = "$n " + when { n == 1L -> "den"; n in 2..4 -> "dny"; else -> "dní" }

    /** Jmeniny z ověřené tabulky (NameDays): „kdo má dnes/zítra/včera svátek“, „kdy má (slaví) svátek Petr“. */
    internal fun nameDay(t: String, today: LocalDate): String? {
        val note = " _(${NameDays.SOURCE})_"
        Regex("^(?:a )?kdo (?:ma|slavi) (dnes|dneska|zitra|vcera|pozitri) (?:svatek|jmeniny)$|^(?:a )?kdo (?:ma|slavi) (?:svatek|jmeniny) (dnes|dneska|zitra|vcera|pozitri)$|^(?:jake jmeno|kdo) (?:ma )?(?:dnes|dneska) (?:svatek|jmeniny)$").find(t)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { "dnes" }
            val (label, d) = when (w) { "zitra" -> "Zítra" to today.plusDays(1); "vcera" -> "Včera" to today.minusDays(1); "pozitri" -> "Pozítří" to today.plusDays(2); else -> "Dnes" to today }
            val v = NameDays.on(d.monthValue, d.dayOfMonth) ?: return null
            val has = if (w == "vcera") "měl/a" else "má"
            return if (v.contains(" svátek") && v.endsWith(")") || v == "Tři králové") "$label (${d.dayOfMonth}. ${d.monthValue}.) je $v – jmeniny v kalendáři nejsou.$note"
                   else "$label (${d.dayOfMonth}. ${d.monthValue}.) $has svátek $v.$note" }
        Regex("^(?:a )?kdy (?:ma|slavi|miva) (?:svatek|jmeniny) ([a-z]+)$|^(?:a )?kdy (?:ma|slavi) ([a-z]+) (?:svatek|jmeniny)$").find(t)?.let { m ->
            val q = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (q.length !in 2..30) return null // nezrcadlit dlouhý vstup zpět
            val days = NameDays.daysOf(q)
            if (days.isEmpty()) return "Jméno „${q.replaceFirstChar { it.uppercase() }}“ jsem v kalendáři jmen nenašel (zkus základní tvar, třeba „Petr“, ne „Péťa“).$note"
            val name = NameDays.spelled(q) ?: q
            return "$name má svátek ${days.joinToString(" a ") { "${it.second}. ${it.first}." }}.$note" }
        return null
    }

    private val MONTHS_IN = listOf("lednu", "unoru", "breznu", "dubnu", "kvetnu", "cervnu", "cervenci", "srpnu", "zari", "rijnu", "listopadu", "prosinci")
    private fun hd(d: LocalDate, n: String) = "${d.dayOfMonth}. ${d.monthValue}. ${DAYS[d.dayOfWeek.value - 1]} – $n"

    internal fun holidays(t: String, today: LocalDate): String? {
        if (Regex("^(?:je|bude|mame) (?:dnes|dneska) (?:nejaky )?(?:svatek|statni svatek|volno)$|^(?:jaky|kdo ma) (?:je )?(?:dnes|dneska) svatek$").matches(t)) {
            val h = CzCalendar.holidays(today.year).firstOrNull { it.first == today }
            val name = NameDays.on(today.monthValue, today.dayOfMonth)?.takeIf { h == null && !it.contains("(") }
            return "Dnes (${today.dayOfMonth}. ${today.monthValue}.) " + (if (h != null) "je svátek ${h.second} – den pracovního klidu." else "není státní svátek ani den volna.") +
                (name?.let { " Jmeniny má $it. _(${NameDays.SOURCE})_" } ?: "") }
        Regex("^(?:jake (?:jsou )?|kdy (?:jsou |je |budou |bude )?)?(?:statni )?svat(?:ky|ek) (?:v|ve) ([a-z]+)(?: (\\d{4}))?$").find(t)?.let { m ->
            val mi = MONTHS_IN.indexOf(m.groupValues[1]).takeIf { it >= 0 } ?: return null
            val y = m.groupValues[2].toIntOrNull() ?: today.year
            if (y !in 2001..2200) return null // zákon 245/2000 Sb. platí od 2001; dřív/daleko dopředu nehádat
            val l = CzCalendar.holidays(y).filter { it.first.monthValue == mi + 1 }
            return if (l.isEmpty()) "V ${m.groupValues[1]} $y není žádný státní ani jiný svátek (den volna)." else "Svátky (dny volna) v $y: " + l.joinToString("; ") { hd(it.first, it.second) } + "." }
        if (Regex("^(?:kdy (?:je|bude) )?(?:dalsi|nejblizsi|pristi) (?:statni )?svatek$|^kdy je (?:dalsi|nejblizsi|pristi) volno$").matches(t)) {
            val n = (CzCalendar.holidays(today.year) + CzCalendar.holidays(today.year + 1)).first { it.first.isAfter(today) }
            return "Nejbližší svátek (den volna): ${hd(n.first, n.second)} ${n.first.year}, za ${daysCz(ChronoUnit.DAYS.between(today, n.first))}." }
        Regex("^(?:je|bude|byl) (\\d{1,2})\\. ?(\\d{1,2})\\.?(?: ?(\\d{4}))? (?:statni )?svatek$").find(t)?.let { m ->
            val y = m.groupValues[3].toIntOrNull() ?: today.year
            if (y !in 2001..2200) return null
            val d = runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return null
            val h = CzCalendar.holidays(y).firstOrNull { it.first == d }
            return if (h != null) "Ano, ${d.dayOfMonth}. ${d.monthValue}. $y je ${h.second} (den volna)." else "Ne, ${d.dayOfMonth}. ${d.monthValue}. $y není státní svátek ani den volna." }
        return null
    }
}

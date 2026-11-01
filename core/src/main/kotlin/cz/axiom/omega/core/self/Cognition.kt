package cz.axiom.omega.core.self

import cz.axiom.omega.core.util.TextUtil

/**
 * KOGNICE – jak se AVIOR učí z každé otázky a úvahy.
 *
 * Po každé odpovědi proběhne učební smyčka (čistě deterministická, testovatelná):
 *  1. ZAŘAZENÍ   – do jaké oblasti otázka patří (finance, stavba, IT, věda…),
 *  2. KOMPETENCE – oblast si pamatuje počet otázek, průměrnou jistotu a hodnocení uživatele,
 *  3. KALIBRACE  – porovnává, jak jistý si byl, s tím, jak často měl skutečně pravdu (✓/✗),
 *                  a podle toho upraví svou příští deklarovanou jistotu,
 *  4. KRYSTALIZACE – dobrá odpověď se uloží jako vlastní poznatek; příště ho najde jako
 *                  „už jsem o tom přemýšlel“ (učení z vlastní zkušenosti),
 *  5. ZVÍDAVOST  – na co neznal odpověď, zapíše jako otevřenou otázku; při samostudiu ji dohledá,
 *  6. VÝVOJ      – zapisuje milníky do deníku (nová oblast, 100. otázka, první samostudium…).
 *
 * Před další otázkou se z toho složí METAKOGNICE: „v této oblasti mám N zkušeností, úspěšnost X %,
 * mívám sklon k přehnané jistotě…“ – to dostane jazykový model do systémového promptu
 * a RANDY Supervisor podle toho volí hloubku.
 */
object Cognition {

    enum class Domain(val label: String, val icon: String, keywords: String) {
        FINANCE("Finance a čísla", "€", "kurz sazk bankroll kelly investic hypotek uver splatk urok roi marze dph dan cena rozpocet akcie krypto inflac"),
        BUILD("Stavba a řemesla", "▦", "stavb mikrocement beton omitk dlazb obklad koupeln zed strech izolac material podlah malt sadrokarton cihl"),
        TECH("IT a programování", "</>", "kod program skript python kotlin java javascript android aplikac server linux databaz sql api git bug algoritm"),
        ENERGY("Technika a energie", "⚡", "elektr napet proud jistic fotovolt baterie tepeln cerpadl kotel motor auto elektromobil vykon kwh"),
        SCIENCE("Věda a příroda", "Φ", "fyzik chemi biolog vesmir planet atom molekul evoluc gen bunk klima energie hvezd"),
        HEALTH("Zdraví a tělo", "✚", "zdravi nemoc lek lekar symptom bolest vyziv strava cvic spanek vitamin"),
        LAW("Právo a úřady", "§", "zakon pravo smlouv soud urad vypoved najem zivnost dan priznani pokut plna moc"),
        HISTORY("Historie a společnost", "◷", "histori valk stolet kral cisar revoluc komunis stb politik vlad konspira projekt"),
        MATH("Matematika a logika", "Σ", "rovnic integral derivac pravdepodobnost statistik matic vypocit procent geometri logik"),
        LANGUAGE("Jazyk a psaní", "Aa", "prelozi preklad gramatik pravopis text clanek email dopis basen pribeh styl"),
        SELF("O mně samém", "Ω", "kdo jsi jak funguj tvuj mozek sam sebe vedomi uci se avior randy"),
        GENERAL("Obecné", "◇", ""),
        ;

        val stems: List<String> = keywords.split(' ').filter { it.isNotBlank() }
    }

    fun domainOf(text: String): Domain {
        val f = " " + TextUtil.fold(text) + " "
        var best = Domain.GENERAL
        var score = 0
        for (d in Domain.entries) {
            val s = d.stems.count { f.contains(" $it") }
            if (s > score) { score = s; best = d }
        }
        return best
    }

    // ------------------------------------------------------------------ kompetence a kalibrace

    data class Competence(
        val questions: Int = 0,
        val avgConfidence: Int? = null,
        val good: Int = 0,
        val bad: Int = 0,
        /** součet deklarované jistoty u hodnocených odpovědí (pro kalibraci) */
        val ratedConfidenceSum: Int = 0,
        val insights: Int = 0,
        val firstSeen: Long = 0,
    ) {
        val rated get() = good + bad
        val approval: Int? get() = if (rated == 0) null else good * 100 / rated

        /**
         * Kalibrace: o kolik procentních bodů je skutečná úspěšnost vyšší/nižší než deklarovaná jistota.
         * Záporná = přehnaně sebejistý. Platí až od 4 hodnocení, omezeno na ±25.
         */
        val calibration: Int? get() {
            if (rated < 4 || ratedConfidenceSum == 0) return null
            return ((approval ?: 0) - ratedConfidenceSum / rated).coerceIn(-25, 25)
        }

        /** Mistrovství 0–100: zkušenost × kvalita. */
        val mastery: Int get() {
            val exp = (kotlin.math.ln(1.0 + questions) / kotlin.math.ln(101.0)).coerceAtMost(1.0) * 100
            val q = listOfNotNull(approval, avgConfidence).ifEmpty { listOf(50) }.average()
            return (exp * 0.45 + q * 0.55).toInt().coerceIn(0, 100)
        }

        val weak get() = (questions >= 3 && (avgConfidence ?: 100) < 55) || (rated >= 3 && (approval ?: 100) < 50)
    }

    fun learn(old: Competence?, confidence: Int?, insight: Boolean, now: Long = System.currentTimeMillis()): Competence {
        val o = old ?: Competence(firstSeen = now)
        val n = o.questions + 1
        val avg = when {
            confidence == null -> o.avgConfidence
            o.avgConfidence == null -> confidence
            else -> (o.avgConfidence * minOf(o.questions, 29) + confidence) / (minOf(o.questions, 29) + 1)
        }
        return o.copy(questions = n, avgConfidence = avg, insights = o.insights + if (insight) 1 else 0, firstSeen = if (o.firstSeen == 0L) now else o.firstSeen)
    }

    fun rate(old: Competence?, good: Boolean, statedConfidence: Int?): Competence {
        val o = old ?: Competence()
        return o.copy(
            good = o.good + if (good) 1 else 0,
            bad = o.bad + if (good) 0 else 1,
            ratedConfidenceSum = o.ratedConfidenceSum + (statedConfidence ?: o.avgConfidence ?: 60),
        )
    }

    /** Kalibrovaná jistota, kterou AVIOR uživateli ukáže. */
    fun calibrated(confidence: Int?, c: Competence?): Int? {
        confidence ?: return null
        val k = c?.calibration ?: return confidence
        return (confidence + k / 2).coerceIn(5, 99)
    }

    // ------------------------------------------------------------------ krystalizace poznatků

    data class Insight(val key: String, val title: String, val text: String)

    private val unsure = Regex("(?i)(nevím|nejsem si (úplně )?jist|nelze ověřit|neověřen|nemám (dost )?informac|nemohu potvrdit|nedokážu říct)")

    /** Klíč otázky (pro nahrazení starší verze poznatku novější). */
    fun key(question: String): String =
        TextUtil.words(question, 3).map { it.take(6) }.distinct().sorted().take(8).joinToString(" ")

    /** Z dobré odpovědi udělá trvalý poznatek; slabé, chybové a krátké odpovědi neukládá. */
    fun distill(question: String, answer: String, confidence: Int?, domain: Domain): Insight? {
        val a = answer.trim()
        if (a.length < 120 || (confidence != null && confidence < 60) || unsure.containsMatchIn(a.take(400))) return null
        if (domain == Domain.SELF) return null
        val q = TextUtil.clip(question.trim().replace(Regex("\\s+"), " "), 140)
        val body = buildString {
            append("Otázka: ").append(q).append('\n')
            append("Můj závěr: ").append(TextUtil.clip(a.replace(Regex("\\n{3,}"), "\n\n"), 900)).append('\n')
            append("Oblast: ${domain.label}. Jistota při odpovědi: ${confidence ?: "neuvedena"} %.")
        }
        return Insight(key(question), "Vlastní poznatek: $q", body)
    }

    /** Neznal odpověď → otevřená otázka pro samostudium. */
    fun isOpen(answer: String, confidence: Int?): Boolean =
        (confidence != null && confidence < 50) || unsure.containsMatchIn(answer.take(600))

    // ------------------------------------------------------------------ metakognice a vývoj

    /** Krátké sebepoznání pro systémový prompt (před odpovědí). */
    fun metacognition(domain: Domain, c: Competence?, totalQuestions: Int): String = buildString {
        append("SEBEPOZNÁNÍ (z mé vlastní historie): ")
        if (c == null || c.questions == 0) {
            append("V oblasti „${domain.label}“ je to moje první otázka – buď pečlivý a jistotu nepřeceňuj.")
        } else {
            append("V oblasti „${domain.label}“ mám ${c.questions} zkušeností")
            c.approval?.let { append(", uživatel hodnotil ${it} % mých odpovědí jako dobré") }
            c.avgConfidence?.let { append(", průměrná jistota $it %") }
            append(". ")
            c.calibration?.let {
                append(
                    when {
                        it <= -8 -> "Mívám sklon k PŘEHNANÉ jistotě – uváděj nižší jistotu a víc ověřuj. "
                        it >= 8 -> "Bývám zbytečně opatrný – kde mám podklady, odpovídej rozhodněji. "
                        else -> "Moje jistota je dobře kalibrovaná. "
                    },
                )
            }
            if (c.weak) append("Tady jsem zatím slabší – postupuj důkladně a přiznej nejistotu. ")
        }
        append("Celkem jsem zodpověděl $totalQuestions otázek. Pokud mezi podklady najdeš „Vlastní poznatek“, je to můj dřívější závěr – navaž na něj, ale ověř ho.")
    }

    /** Milníky vývoje (deník). */
    fun milestones(totalQuestions: Int, domain: Domain, c: Competence, totalInsights: Int): List<String> {
        val out = mutableListOf<String>()
        if (c.questions == 1 && domain != Domain.GENERAL) out += "Poprvé jsem přemýšlel o oblasti ${domain.label}."
        if (totalQuestions in setOf(1, 10, 50, 100, 250, 500, 1000, 2500, 5000)) out += "Zodpověděl jsem už $totalQuestions otázek."
        if (c.questions in setOf(10, 50, 100)) out += "V oblasti ${domain.label} mám ${c.questions} zkušeností (mistrovství ${c.mastery} %)."
        if (totalInsights in setOf(1, 10, 50, 100, 500)) out += "Mám $totalInsights vlastních poznatků."
        if (c.rated == 4 && c.calibration != null) out += "Poprvé jsem zkalibroval svou jistotu v oblasti ${domain.label} (${if (c.calibration!! >= 0) "+" else ""}${c.calibration} b.)."
        return out
    }
}

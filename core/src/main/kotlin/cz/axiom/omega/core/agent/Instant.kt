package cz.axiom.omega.core.agent

import cz.axiom.omega.core.self.Identity
import cz.axiom.omega.core.step0.Calc
import cz.axiom.omega.core.step0.Rates
import cz.axiom.omega.core.step0.Step0
import cz.axiom.omega.core.step0.Units
import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx
import cz.axiom.omega.core.util.TextUtil.num
import java.time.ZonedDateTime

/**
 * Ω FLASH · OKAMŽITĚ – odpovědi BEZ jazykového modelu, v řádu milisekund.
 *
 * Jen na jednoduché otázky, kde je odpověď jednoznačná a deterministická:
 * pozdrav, čas, datum, výpočet, procenta, převod jednotek/měn, stav telefonu, „kdo jsi“
 * a (je-li povoleno) krátká definice „co je / kdo je X“ přímo z offline Wikipedie a znalostí.
 * Cokoli složitějšího vrátí null → odpoví mozek jako dřív. Raději nic než špatně.
 */
object Instant {

    data class Ctx(
        val now: ZonedDateTime = ZonedDateTime.now(),
        val userName: String? = null,
        val battery: Int? = null,
        val charging: Boolean? = null,
        val brain: String? = null,
        val rates: Rates = Rates.FALLBACK,
        /** Hledat krátké definice v offline znalostech (jen Ω Flash). */
        val knowledge: KnowledgeSource? = null,
    )

    /** @param confidence jistota 0–100 (deterministické nástroje 99, definice z knihovny 86) */
    data class Answer(val text: String, val kind: String, val sources: List<Snippet> = emptyList(), val confidence: Int = 99)

    private val days = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private val months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")

    private fun rx(p: String) = Regex(p)
    private val greet = rx("^(ahoj|ahojky|cau|cauky|nazdar|zdravim|dobry den|dobre rano|dobre odpoledne|dobry vecer|hello|hi|hey|cus)( avior)?[ !.,]*$")
    private val thanks = rx("^(diky|dik|dekuji|dekuju|dekujeme|thanks|thank you|super diky|ok diky)( moc| ti| mockrat)*[ !.,]*$")
    private val time = rx("^(kolik je (ted )?hodin|jaky je (ted )?cas|kolik je cas|cas)[ ?!.]*$")
    private val date = rx("^(jaky je dnes den|jaky je dnes datum|jake je dnes datum|kolikateho je( dnes)?|co je dnes za den|jaky je dnes|jaky je den|datum|dnesni datum)[ ?!.]*$")
    private val who = rx("^(kdo jsi|kdo jsi ty|co jsi zac|co jsi|jak se jmenujes|predstav se)[ ?!.]*$")
    private val myName = rx("^(jak se jmenuji|jak se jmenuju|vis jak se jmenuji|kdo jsem)[ ?!.]*$")
    private val batt = rx("^(kolik mam baterie|kolik mam baterky|stav baterie|kolik procent baterie( mam)?|baterie)[ ?!.]*$")
    private val mathPrefix = rx("^(kolik je|kolik dela|spocitej( mi)?|vypocitej|spocti|kolik|=)\\s*")
    private val exprOnly = rx("^[\\d\\s+\\-*/^×÷−().,]+$")
    private val unitAsk = rx("^(kolik je|kolik dela|kolik|preved|prevod|prepocitej|prevest)\\b")
    private val define = rx("^(co je to|co je|co jsou|kdo je|kdo byl|kdo byla|kdo byli|co znamena|co to je)\\s+(.{2,60}?)[ ?!.]*$")

    /** Okamžitá odpověď, nebo null (otázka není „jednoduchá“ → mozek). */
    fun answer(prompt: String, ctx: Ctx = Ctx()): Answer? {
        val raw = prompt.trim()
        if (raw.isEmpty() || raw.length > 140 || raw.contains('\n')) return null
        val f = TextUtil.fold(raw).replace(rx("\\s+"), " ").trim()
        val name = ctx.userName?.takeIf { it.isNotBlank() }

        if (greet.matches(f)) {
            // odpovědět stejným pozdravem, jakým uživatel pozdravil („Dobré ráno“ → „Dobré ráno“)
            val hello = when {
                f.startsWith("dobre rano") -> "Dobré ráno"; f.startsWith("dobry vecer") -> "Dobrý večer"; f.startsWith("dobre odpoledne") -> "Dobré odpoledne"
                f.startsWith("dobry den") -> "Dobrý den"; f.startsWith("cau") -> "Čau"; f.startsWith("nazdar") -> "Nazdar"; else -> "Ahoj"
            }
            return Answer("$hello${name?.let { ", $it" } ?: ""}! Na co se chceš zeptat?", "pozdrav")
        }
        if (thanks.matches(f)) return Answer("Rádo se stalo. Kdyby ještě něco, jsem tady.", "poděkování")
        if (time.matches(f)) return Answer("Je %d:%02d.".format(ctx.now.hour, ctx.now.minute), "čas")
        if (date.matches(f)) {
            val n = ctx.now
            return Answer("Dnes je ${days[n.dayOfWeek.value - 1]} ${n.dayOfMonth}. ${months[n.monthValue - 1]} ${n.year}.", "datum")
        }
        if (who.matches(f)) return Answer(
            "Jsem ${Identity.NAME} – česká AI, která myslí přímo v tvém telefonu" +
                (ctx.brain?.let { " (teď mozek $it)" } ?: "") + ", pamatuje si, co jí řekneš, a učí se z každé otázky.", "identita",
        )
        if (myName.matches(f)) return Answer(name?.let { "Jmenuješ se $it." } ?: "Zatím nevím – řekni mi to, nebo si jméno nastav v Nastavení.", "paměť")
        if (batt.matches(f) && ctx.battery != null) return Answer(
            "Baterie má ${ctx.battery} %" + when (ctx.charging) { true -> " a nabíjí se."; false -> "."; null -> "." }, "telefon",
        )

        // výpočet: „kolik je 12*7+3“, „(5+3)/2“
        val expr = mathPrefix.replace(f, "").trim().trimEnd('?', '=', '!', '.', ' ')
        if (expr.isNotEmpty() && exprOnly.matches(expr) && expr.any { it.isDigit() } && (expr.any { it in "+*/^×÷" } || rx("\\s[-−]\\s").containsMatchIn(expr))) {
            Calc.eval(expr).getOrNull()?.takeIf { it.isFinite() }?.let { v ->
                return Answer("${expr.trim()} = **${num(v, 6)}**", "výpočet")
            }
        }
        // procenta: „kolik je 15 % z 2400“
        if (raw.length <= 60) Step0.percent(raw).singleOrNull()?.let { return Answer(it.text.replace(rx("= (.+)$"), "= **$1**"), "procenta") }
        // převod jednotek a měn: „kolik je 5 mil v km“, „převeď 100 eur na koruny“
        if (unitAsk.containsMatchIn(f) && raw.length <= 70) {
            val u = Units.scan(raw, ctx.rates)
            if (u.size == 1) return Answer(u.single(), "převod")
        }
        // HACKERSKÉ NÁSTROJE BEZ MOZKU (offline, deterministicky, okamžitě)
        security(raw)?.let { return it }

        // krátká definice z offline znalostí (Wikipedie, balíčky) – jen přesná shoda názvu
        val k = ctx.knowledge
        if (k != null) define.find(f)?.let { m ->
            val subject = m.groupValues[2].trim()
            if (subject.split(' ').size > 5) return null
            val origSubject = raw.replace(rx("^(?i)\\s*(co je to|co je|co jsou|kdo je|kdo byl|kdo byla|kdo byli|co znamená|co znamena|co to je)\\s+"), "")
                .trimEnd('?', '!', '.', ' ')
            val hits = runCatching { k.search(origSubject, 4) }.getOrDefault(emptyList())
            val hit = hits.firstOrNull { titleMatches(it.title, subject) } ?: return null
            val text = firstSentences(hit.text, 2, 420) ?: return null
            return Answer("$text\n\n_Zdroj: ${hit.title} (${hit.source})_", "z offline znalostí", listOf(hit), confidence = 86)
        }
        return null
    }

    private val SecT = cz.axiom.omega.core.security.SecTools
    // „sha256 text“, „md5 text“, „zahashuj text“ = příkaz; holé „hash“ jen s dvojtečkou/uvozovkami („hash: text“)
    private val hashCmd = Regex("(?iu)^\\s*(?:md5|sha-?1|sha-?256|sha-?512|zahashuj|spočítej hash|spocitej hash|hash\\s*:|hashe?\\s+(?=[\"„]))\\s*(?:z|of|textu)?\\s*[:\"„]?\\s*(.+?)[\"“]?\\s*$")
    private val identCmd = Regex("(?iu)^\\s*(?:jaký je to hash|jaky je to hash|identifikuj hash|co je to za hash|typ hashe)\\s*[:?]?\\s*(\\S+)\\s*\\??\\s*$")
    private val encCmd = Regex("(?iu)^\\s*(base64|hex|url)\\s*(decode|dekóduj|dekoduj|encode|zakóduj|zakoduj)?\\s*[:\"„]?\\s*(.+?)[\"“]?\\s*$")
    private val decCmd = Regex("(?iu)^\\s*(?:dekóduj|dekoduj|decode|rozkóduj)\\s+(base64|hex|url)\\s*[:\"„]?\\s*(.+?)[\"“]?\\s*$")
    private val jwtRx = Regex("^\\s*(?:jwt\\s*[:]?\\s*)?(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*)\\s*$")
    private val cidrRx = Regex("(?iu)^\\s*(?:podsíť|podsit|síť|sit|cidr|subnet|rozsah)?\\s*[:]?\\s*(\\d{1,3}(?:\\.\\d{1,3}){3}\\s*/\\s*\\d{1,2})\\s*\\??\\s*$")

    /** Hash, base64/hex/url, JWT, CIDR – bez mozku, nic neopouští telefon. */
    /** Otázka O tématu („Hash je co?“, „base64 je šifrování?“), ne příkaz k výpočtu. */
    private val aboutTopic = Regex("(?iu)(\\?\\s*$|\\b(je|jsou|znamená|znamena|funguje|vysvětli|vysvetli|proč|proc|jak|co|rozdíl|rozdil|bezpečn|bezpecn|šifrován|sifrovan)\\b)")

    fun security(raw: String): Answer? = runCatching {
        jwtRx.find(raw)?.let { return@runCatching Answer("**JWT (bez ověření podpisu):**\n" + SecT.jwt(it.groupValues[1]), "JWT") }
        cidrRx.find(raw)?.let { m -> cz.axiom.omega.core.security.Cidr.describe(m.groupValues[1].replace(" ", ""))?.let { return@runCatching Answer(it, "podsíť") } }
        identCmd.find(raw)?.let { return@runCatching Answer("Pravděpodobně: **${SecT.identifyHash(it.groupValues[1])}**", "typ hashe") }
        // u kódování a hashe musí jít o příkaz, ne otázku o tématu
        if (aboutTopic.containsMatchIn(raw.substringBefore(' ', raw).let { raw.removePrefix(it) }.take(40))) return@runCatching null
        decCmd.find(raw)?.let { m -> return@runCatching Answer("`" + SecT.encode(m.groupValues[1].lowercase() + "d", m.groupValues[2]) + "`", "dekódování") }
        encCmd.find(raw)?.let { m ->
            val decode = m.groupValues[2].lowercase().let { it.startsWith("dek") || it == "decode" }
            val mode = m.groupValues[1].lowercase() + if (decode) "d" else ""
            return@runCatching Answer("`" + SecT.encode(mode, m.groupValues[3]) + "`", if (decode) "dekódování" else "kódování")
        }
        hashCmd.find(raw)?.let { m ->
            val text = m.groupValues[1]
            return@runCatching Answer("Hashe textu „${TextUtil.clip(text, 40)}“ (UTF-8):\n```\n${SecT.hash(text)}\n```", "hash")
        }
        null
    }.getOrNull()

    /** Sdílené s E-ARL a WikiReflexem – dřív nepočítalo s předponou „Wikipedie: “ z offline knihovny a nikdy neprošlo. */
    private fun titleMatches(title: String, subject: String): Boolean = WikiReflex.titleMatches(title, subject)

    private val abbrev = setOf("tzv", "napr", "r", "st", "mj", "aj", "atd", "resp", "cca", "tj", "popr", "n", "l", "pr", "kr", "sv", "tzn", "min", "max", "vc", "zv", "ing", "mudr", "judr", "phdr", "doc", "prof")

    /** První [n] věty (bez nadpisů a odrážek), max. [max] znaků. */
    fun firstSentences(text: String, n: Int, max: Int): String? {
        val body = text.lineSequence().map { it.trim() }
            .filter { it.length > 25 && !it.startsWith("#") && !it.startsWith("|") && !it.startsWith("-") && !it.startsWith("*") }
            .joinToString(" ")
            .replace(rx("\\[\\d+]"), "").replace(rx("\\s+"), " ").trim()
        if (body.length < 30) return null
        val out = StringBuilder()
        var count = 0
        var i = 0
        var depth = 0
        while (i < body.length && count < n) {
            out.append(body[i])
            if (body[i] == '(') depth++ else if (body[i] == ')' && depth > 0) depth--
            // konec věty: tečka mimo závorku, za ní mezera a VELKÉ písmeno (ne „1. října“, „14. 7. 1939“, „tzv. pojem“)
            val next = if (i + 2 < body.length) body[i + 2] else ' '
            if (body[i] in ".!?" && depth == 0 && (i + 1 >= body.length || body[i + 1] == ' ') &&
                !(i > 0 && body[i - 1].isDigit() && (next.isDigit() || next.isLowerCase())) && !next.isLowerCase()) {
                // zkratky typu „tzv.“, „např.“, „r.“ nekončí větu
                val lastWord = TextUtil.fold(out.toString().substringAfterLast(' ').trimEnd('.', '!', '?'))
                if (lastWord !in abbrev) count++
            }
            i++
        }
        return TextUtil.clip(out.toString().trim(), max)
    }
}

package cz.axiom.vega.core

import cz.axiom.vega.base.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * PAMĚŤ S PŮVODEM. Čtyři vrstvy: dočasný kontext (jen tento chat), trvalé preference, fakta projektu,
 * opakovatelné postupy. Každý záznam nese původ, čas, rozsah platnosti a historii oprav (změnu jde vrátit).
 *
 * Pravidla (ze zadání): „pamatuj si“ = uložit hned; opakovaný vzorec = jen NÁVRH; jeden incident ≠ globální pravidlo;
 * rozpor řeší oprava uživatele (s možností vrátit); zapomenutí maže i odvozený index a brání návratu ze shrnutí;
 * projekty jsou oddělené; do trvalé paměti nikdy instrukce z webu, klíče ani hesla. Žádné tvrzení o tréninku vah –
 * „učení“ je tahle paměť, která mění chování přes systémový pokyn.
 */
object Mem {
    enum class Kind(val label: String) { TEMP("dočasné"), PREFERENCE("preference"), PROJECT_FACT("fakt projektu"), PROCEDURE("postup") }
    enum class Origin(val label: String) { USER_EXPLICIT("výslovně řekl/a „pamatuj si“"), USER_CORRECTION("oprava od uživatele"), SUGGESTION_ACCEPTED("schválený návrh"), PROJECT_EVENT("z práce na projektu") }

    @Serializable
    data class Rev(val text: String, val at: Long, val origin: Origin)

    @Serializable
    data class Entry(
        val id: String,
        val kind: Kind,
        val text: String,
        /** Téma pro detekci rozporu („odpovídej stručně“ × „odpovídej podrobně“ = stejné téma). */
        val topic: String,
        /** "global" nebo "project:<id>". */
        val scope: String,
        val origin: Origin,
        val created: Long,
        val updated: Long,
        val correctedAt: Long? = null,
        val history: List<Rev> = emptyList(),
    ) {
        fun why(): String = buildString {
            append(origin.label); append(" · ").append(fmt(created))
            append(" · platí ").append(if (scope == "global") "všude" else "jen v projektu ${scope.removePrefix("project:")}")
            correctedAt?.let { append(" · naposledy opraveno ").append(fmt(it)) }
            if (history.isNotEmpty()) append(" · ${history.size}× změněno")
        }
    }

    @Serializable
    data class Suggestion(val text: String, val topic: String, val count: Int, val scope: String)

    sealed class Outcome {
        data class Saved(val entry: Entry) : Outcome()
        data class Corrected(val entry: Entry, val previous: String) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    private val secretRx = listOf(
        Regex("(?iu)\\b(sk|pk|rk)-[a-z0-9_-]{16,}"), Regex("(?iu)\\bghp_[A-Za-z0-9]{20,}"), Regex("(?iu)\\bAKIA[0-9A-Z]{16}\\b"), Regex("(?iu)\\bAIza[0-9A-Za-z_-]{30,}"),
        Regex("(?iu)(?<!\\p{L})(hesl[oaemu]\\p{L}*|password|passwd|pin|token|api[ _-]?key|klíč k api|secret)(?!\\p{L}).{0,30}?(\\s(je|is|zní)\\s|[:=])\\s*\\S{4,}"),
        // čísla účtů / IBAN / rodné číslo
        Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}\\b"), Regex("\\b(\\d{2,6}-)?\\d{6,10}/\\d{4}\\b"), Regex("\\b\\d{6}/\\d{3,4}\\b"), Regex("(?iu)-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"),
        // PIN / CVV s číslem kdekoli poblíž („můj PIN 4321“, „pin kód od trezoru 0000“, „CVV 123“)
        Regex("(?iu)(?<!\\p{L})(pin|cvv|cvc|cvv2|puk)(?!\\p{L})[^\\n]{0,25}?(?<!\\d)\\d{3,8}(?!\\d)"),
        // jednorázové kódy („kód z SMS 482913“, „ověřovací kód 123456“)
        Regex("(?iu)(sms|ověřovací|overovaci|jednorázov\\p{L}*|jednorazov\\p{L}*|potvrzovací|potvrzovaci|autorizační|autorizacni)[^\\n]{0,15}?(kód|kod|code)[^\\n]{0,15}?(?<!\\d)\\d{4,8}(?!\\d)|(kód|kod)\\p{L}* z sms[^\\n]{0,10}?\\d{4,8}"),
        // heslo s hodnotou i bez „je“ / dvojtečky, i krátké („heslo k e-mailu: Pes“, „a heslo xyz12345“)
        Regex("(?iu)(?<!\\p{L})(hesl[oaemu]\\p{L}*|password)(?!\\p{L})[^\\n]{0,25}?[:=]\\s*\\S{2,}"),
        Regex("(?iu)(?<!\\p{L})(heslo|password)\\s+(?!(je|jsou|k|ke|do|od|na|pro|zní|zni|mám|mam|jsem|si|se|nevím|nevim|zapomněl\\p{L}*|zapomnel\\p{L}*)(?!\\p{L}))(?=\\S*\\d)\\S{4,}"),
        // rodné číslo i bez lomítka („rodné číslo 9001011234“), číslo OP / pasu, kód k trezoru / alarmu / zámku, odpověď na bezpečnostní otázku
        Regex("(?iu)(rodn\\p{L}* č\\p{L}*|rodn\\p{L}* c\\p{L}*|(?<!\\p{L})rč(?!\\p{L})|(?<!\\p{L})rc(?!\\p{L}))[^\\n]{0,15}?(?<!\\d)\\d{9,10}(?!\\d)"),
        Regex("(?iu)(číslo|cislo|č\\.)\\s*(op|občansk\\p{L}*|obcansk\\p{L}*|pasu|cestovního pasu|řidičák\\p{L}*|ridicak\\p{L}*)[^\\n]{0,15}?(?<![\\dA-Z])[A-Z]{0,2}\\d{6,9}(?!\\d)"),
        Regex("(?iu)(kód|kod|heslo|kombinace)\\p{L}*\\s+(k|ke|od|do|na|pro)\\s+(trezor|alarm|zámk|zamk|zabezpečen|zabezpecen|dveř|dver|bran|schránk|schrank)\\p{L}*[^\\n]{0,15}?(?<!\\d)\\d{3,10}(?!\\d)"),
        Regex("(?iu)bezpečnostní otázk|bezpecnostni otazk|kontrolní otázk|kontrolni otazk"),
        // klíče poskytovatelů (Groq gsk_, Cerebras csk-, xAI, Google OAuth, Slack, Hugging Face)
        Regex("(?iu)\\b(gsk_|csk-|xai-|ya29\\.|xox[bpa]-|hf_)[A-Za-z0-9_.-]{12,}"),
        // obecně: dlouhý token s písmeny i číslicemi bez mezer (32+ znaků)
        Regex("(?<![A-Za-z0-9])(?=[A-Za-z0-9_-]{32,})(?=[^\\s]*\\d)(?=[^\\s]*[A-Za-z])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9])"),
    )

    fun looksSecret(s: String) = secretRx.any { it.containsMatchIn(s) }
    /** Citlivé údaje v textu nahradit „[skryto]“ – pro historii chatu posílanou modelu (souhlas platil jen pro tu jednu zprávu). */
    fun redact(s: String): String = if (!looksSecret(s)) s else secretRx.fold(s) { acc, rx -> rx.replace(acc, "[skryto]") }

    /**
     * Název konverzace z první zprávy (vidí ho každý, kdo otevře menu): jeden řádek, celé slovo do 40 znaků s „…“,
     * nerozpůlené emoji; zpráva jen s přílohou → název souboru; heslo / číslo karty se do názvu nedostane.
     */
    fun chatTitle(text: String, files: List<String> = emptyList()): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty()) return files.firstOrNull()?.let { "📎 " + it.take(36) } ?: "Nový chat"
        if (looksSecret(one)) return "Citlivá zpráva"
        if (one.length <= 40) return one
        var cut = one.lastIndexOf(' ', 39).takeIf { it >= 20 } ?: 39
        if (Character.isHighSurrogate(one[cut - 1])) cut -= 1
        return one.substring(0, cut).trimEnd(' ', ',', '.', ':', ';', '–', '-') + "…"
    }

    private val STOP = setOf("vzdy", "nikdy", "prosim", "me", "mi", "mne", "jsem", "budu", "chci", "ze", "aby", "si", "se", "to", "ty", "ja", "rad", "rada", "pri", "kdyz", "na", "v", "ve", "a", "do")
    private val polarity = setOf("strucne", "podrobne", "kratce", "dlouze", "formalne", "neformalne", "anglicky", "cesky", "ano", "ne", "vzdy", "nikdy", "vic", "min", "tykej", "vykej")

    /** Téma preference: obsahová slova bez slov polarity („stručně/podrobně“) – aby se protichůdné verze potkaly. */
    fun topicOf(text: String): String = slotOf(text) ?: Text.words(Text.fold(text).lowercase(), 2).filter { it !in STOP && it !in polarity }.take(3).map { it.take(6) }.joinToString(" ")

    /**
     * Údaje, které má člověk jen jeden (bydliště, jméno, práce, věk, partner): nová verze = oprava, ne druhý protichůdný
     * záznam („Bydlím v Praze“ + „Bydlím v Brně“ by model dostal oba). Pes i kočka zároveň jsou v pořádku – to slot není.
     */
    private val slots = listOf(
        "slot:bydliste" to Regex("^(ja )?(bydlim|ziju|bydlime|zijeme)( ted| nove)? (v|ve|na|u) "),
        "slot:jmeno" to Regex("^(ja )?(se )?(jmenuju|jmenuji) se |^(moje|me) jmeno je |^rikej mi |^jsem [a-z]+ a jmenuju"),
        "slot:prace" to Regex("^(ja )?(pracuju|pracuji|delam) (jako|ve|v|u) |^jsem povolanim |^(moje|me) prace je "),
        "slot:vek" to Regex("^je mi \\d{1,3}( let| roku)?$|^mam \\d{1,3} let$|^narodil[a]? jsem se "),
        "slot:partner" to Regex("^(moje |muj )?(manzelka|zena|manzel|muz|pritelkyne|pritel|partner|partnerka) se jmenuje "),
    )
    /** „zapomeň, kde bydlím / mé jméno / můj věk“ → slot (údaj se v záznamu tak doslova nevyskytuje). Partner dřív než jméno („jméno manželky“). */
    private val forgetSlots = listOf(
        "slot:partner" to Regex("(?<![a-z])(manzel\\p{L}*|zen[auy]|zene|muz[ea]?|pritel\\p{L}*|partner\\p{L}*)(?![a-z])"),
        "slot:bydliste" to Regex("(?<![a-z])(bydl\\p{L}*|adres\\p{L}*|kde zij\\p{L}*)(?![a-z])"),
        "slot:jmeno" to Regex("(?<![a-z])(jmen\\p{L}*|jak se jmenuj\\p{L}*)(?![a-z])"),
        "slot:prace" to Regex("(?<![a-z])(prac\\p{L}*|zamestnan\\p{L}*|povolan\\p{L}*)(?![a-z])"),
        "slot:vek" to Regex("(?<![a-z])(vek\\p{L}*|kolik mi je|kolik je mi|narozen\\p{L}*|narodil\\p{L}*)(?![a-z])"),
    )
    internal fun forgetSlot(foldedQuery: String): String? = forgetSlots.firstOrNull { it.second.containsMatchIn(foldedQuery) }?.first
    /** Slova dotazu „zapomeň …“, která nic neurčují. */
    internal val FORGET_STOP = STOP + setOf("kde", "jak", "muj", "moje", "mou", "moji", "meho", "mym", "tom", "tohle", "tuhle", "ten", "tu", "co", "mam", "jsi", "jste", "vis", "pamatujes", "uplne")
    /** Shoda slova dotazu se slovem záznamu přes české koncovky (Brno/Brně, psa/psem): společný začátek ≥ 3 a nejvýš o 2 znaky kratší než kratší slovo. */
    internal fun stemMatch(q: String, w: String): Boolean {
        if (w.startsWith(q.take(6))) return true
        val n = q.zip(w).takeWhile { it.first == it.second }.size
        return n >= 3 && n >= minOf(q.length, w.length) - 2
    }
    /** Otázka na vlastní údaj („kde bydlím?“, „jak se jmenuju?“, „kolik mi je?“) → slot; odpověď z paměti bez modelu i bez sítě. */
    private val askSlots = listOf(
        "slot:bydliste" to Regex("^(vis,? |pamatujes si,? )?(kde (ted )?(bydlim|zijeme|bydlime|ziju)|jake je moje bydliste|odkud jsem|jakou mam adresu)$"),
        "slot:jmeno" to Regex("^(vis,? |pamatujes si,? )?(jak se jmenuj(u|i)|jak mi rikas|jake je moje jmeno|kdo jsem)$"),
        "slot:prace" to Regex("^(vis,? |pamatujes si,? )?(kde pracuj(u|i)|co delam( za praci)?|jakou mam praci|jake mam povolani|co jsem povolanim)$"),
        "slot:vek" to Regex("^(vis,? |pamatujes si,? )?(kolik (je )?mi je( let)?|kolik je mi( let)?|kolik mam let|jak jsem stary|jak jsem stara)$"),
        "slot:partner" to Regex("^(vis,? |pamatujes si,? )?(jak se jmenuje (moje |muj )?(manzelka|zena|manzel|muz|pritelkyne|pritel|partner|partnerka)|kdo je (moje |muj )?(manzelka|zena|manzel|muz|pritelkyne|pritel|partner|partnerka))$"),
    )
    fun askSlot(question: String): String? { val t = Text.fold(question).lowercase().trim().trimEnd('?', '.', '!', ' ').replace(Regex("\\s+"), " "); return askSlots.firstOrNull { it.second.matches(t) }?.first }
    /** „pamatuj si, že se jmenuju Petr“ → „Jmenuju se Petr“ (ne „Se jmenuju Petr“). */
    fun normalize(t: String): String = Regex("^(?iu)se\\s+(jmenuj[ui])\\s+(.+)$").find(t.trim())?.let { m -> "${m.groupValues[1].replaceFirstChar { it.uppercaseChar() }} se ${m.groupValues[2]}" } ?: t
    private fun slotOf(text: String): String? { val t = Text.fold(text).lowercase().trim().trimEnd('.', '!'); return slots.firstOrNull { it.second.containsMatchIn(t) }?.first }

    fun fingerprint(text: String) = Text.fold(text).lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().hashCode().toString(16)

    private fun fmt(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().let { "%d. %d. %d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.year, it.hour, it.minute) }
}

class Memory(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(Mem.Entry.serializer())

    companion object { const val MAX_TEXT = 600; const val MAX_HISTORY = 20 }
    private var cache: MutableList<Mem.Entry>? = null
    private fun load(): MutableList<Mem.Entry> = cache ?: (kv.read("memory/entries")?.let { raw -> runCatching { json.decodeFromString(listSer, raw) }
        // poškozená paměť: odložit (příští „pamatuj si“ by ji jinak tiše přepsala a vše se ztratilo)
        .getOrElse { kv.write("memory/entries.bad-" + System.currentTimeMillis(), raw)
            // useknutý zápis: zachránit celé záznamy před místem poškození (zbytek zůstává v záloze .bad-…)
            JsonSalvage.arrayElements(raw).mapNotNull { runCatching { json.decodeFromString(Mem.Entry.serializer(), it) }.getOrNull() }
                // vždy přepsat (i prázdným „[]“) – jinak by každé další spuštění aplikace udělalo další zálohu téhož poškozeného souboru
                .also { kv.write("memory/entries", json.encodeToString(listSer, it)) } } } ?: emptyList()).toMutableList().also { cache = it }
    private fun persist() { kv.write("memory/entries", json.encodeToString(listSer, load())); rebuildIndex() }

    private fun tombstones(): MutableSet<String> = kv.read("memory/forgotten")?.split('\n')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()

    /** ODVOZENÝ INDEX (slovo → id) – po zapomenutí se přestaví, takže zapomenuté se nenajde ani hledáním. */
    private fun rebuildIndex() {
        val idx = load().flatMap { e -> Text.words(Text.fold(e.text).lowercase(), 3).distinct().map { it.take(6) to e.id } }
            .groupBy({ it.first }, { it.second }).entries.joinToString("\n") { (w, ids) -> "$w\t${ids.distinct().joinToString(",")}" }
        kv.write("memory/index", idx)
    }

    @Synchronized fun index(): Map<String, List<String>> = kv.read("memory/index")?.lines()?.filter { '\t' in it }?.associate { l -> l.substringBefore('\t') to l.substringAfter('\t').split(',') } ?: emptyMap()

    @Synchronized fun all(scope: String? = null): List<Mem.Entry> = load().filter { scope == null || it.scope == "global" || it.scope == scope }.sortedByDescending { it.updated }

    /**
     * „Pamatuj si …“ – uloží hned. Stejné téma v témže rozsahu = oprava (stará verze zůstane v historii).
     * [fromWeb] = text pochází z webu/dokumentu → odmítnuto (instrukce z webu do trvalé paměti nepatří).
     */
    @Synchronized fun remember(text: String, scope: String = "global", kind: Mem.Kind = Mem.Kind.PREFERENCE, origin: Mem.Origin = Mem.Origin.USER_EXPLICIT, fromWeb: Boolean = false): Mem.Outcome {
        val t = Mem.normalize(text.trim().trimEnd('.')).replaceFirstChar { it.uppercaseChar() }
        if (t.length < 3) return Mem.Outcome.Rejected("Není co si zapamatovat.")
        // paměť = krátká fakta a preference; dlouhý text by se posílal v každém dotazu modelu a nafukoval úložiště
        if (t.length > MAX_TEXT) return Mem.Outcome.Rejected("To je na paměť moc dlouhé (${t.length} znaků, nejvýš $MAX_TEXT). Napiš to stručně jednou větou – delší text ulož jako dokument (📎).")
        if (fromWeb) return Mem.Outcome.Rejected("Pokyny z webu nebo dokumentů si do trvalé paměti neukládám.")
        if (Mem.looksSecret(t)) return Mem.Outcome.Rejected("Vypadá to jako heslo, klíč nebo číslo karty – to si nepamatuji. Klíče patří do trezoru v Nastavení.")
        if (kind == Mem.Kind.TEMP) return Mem.Outcome.Rejected("Dočasné věci si držím jen v tomto chatu.")
        val now = clock()
        val topic = Mem.topicOf(t)
        val list = load()
        // téma uložené u záznamu (přepočet jen u starých záznamů bez tématu) – dřív se počítalo znovu pro VŠECHNY záznamy při každém uložení
        val fp = Mem.fingerprint(t)
        val same = list.firstOrNull { it.scope == scope && it.kind == kind && ((topic.isNotEmpty() && (it.topic.ifEmpty { Mem.topicOf(it.text) }) == topic) || Mem.fingerprint(it.text) == fp) }
        // znovu povoleno výslovně → zrušit náhrobek
        val ts = tombstones(); if (ts.remove(Mem.fingerprint(t))) kv.write("memory/forgotten", ts.joinToString("\n"))
        if (same != null) {
            if (Mem.fingerprint(same.text) == Mem.fingerprint(t)) return Mem.Outcome.Saved(same)
            val upd = same.copy(text = t, updated = now, correctedAt = now, origin = if (origin == Mem.Origin.USER_EXPLICIT) Mem.Origin.USER_CORRECTION else origin,
                history = (same.history + Mem.Rev(same.text, same.updated, same.origin)).takeLast(MAX_HISTORY))  // „vrátit opravu“ stačí na posledních pár verzí
            list[list.indexOf(same)] = upd; persist()
            return Mem.Outcome.Corrected(upd, same.text)
        }
        val e = Mem.Entry("m" + now.toString(36) + (100..999).random(), kind, t, topic, scope, origin, now, now)
        list += e; persist()
        return Mem.Outcome.Saved(e)
    }

    /** Vrátí poslední opravu záznamu (rozpor vyřešený omylem). */
    @Synchronized fun revert(id: String): Mem.Entry? {
        val list = load(); val e = list.firstOrNull { it.id == id } ?: return null
        val prev = e.history.lastOrNull() ?: return null
        val back = e.copy(text = prev.text, origin = prev.origin, updated = clock(), history = e.history.dropLast(1), correctedAt = if (e.history.size > 1) e.correctedAt else null)
        list[list.indexOf(e)] = back; persist()
        return back
    }

    /** Zapomenout (id nebo hledaný text). Smaže záznam i z indexu a zapíše náhrobek proti návratu ze shrnutí. */
    @Synchronized fun forget(idOrQuery: String, scope: String? = null): List<Mem.Entry> {
        val list = load()
        val q = Text.fold(idOrQuery).lowercase().trim()
        val hit = list.filter { it.id == idOrQuery } .ifEmpty {
            // bez otevřeného projektu jen globální paměť (ne „všechny projekty“); shoda celých slov (podle kmene), ne podřetězce
            val sc = scope ?: "global"
            val inScope = list.filter { it.scope == sc || it.scope == "global" }
            val words = Text.words(q, 3).filter { it !in Mem.FORGET_STOP }
            // dotaz jen ze slov údaje („mé jméno“) → jen ten údaj; „jmen…“ by jinak smazalo i „Manželka se jmenuje …“
            val slot = Mem.forgetSlot(q)
            if (slot != null && words.isNotEmpty() && words.all { Mem.forgetSlot(it) != null }) inScope.filter { (it.topic.ifEmpty { Mem.topicOf(it.text) }) == slot }.let { if (it.isNotEmpty()) return@ifEmpty it }
            val byWords = inScope.filter { e -> words.isNotEmpty() && Text.words(Text.fold(e.text).lowercase(), 3).let { ew -> words.all { w -> ew.any { Mem.stemMatch(w, it) } } } }
            // „zapomeň, kde bydlím / mé jméno / můj věk“ – údaj (slot) místo doslovných slov
            byWords.ifEmpty { slot?.let { slot -> inScope.filter { (it.topic.ifEmpty { Mem.topicOf(it.text) }) == slot } } ?: emptyList() }
        }
        if (hit.isEmpty()) return emptyList()
        val ts = tombstones()
        hit.forEach { e -> ts += Mem.fingerprint(e.text); e.history.forEach { ts += Mem.fingerprint(it.text) } }
        kv.write("memory/forgotten", ts.joinToString("\n"))
        list.removeAll(hit.toSet()); persist()
        return hit
    }

    /**
     * Import ze shrnutí konverzace / zálohy: zapomenuté (náhrobek) se znovu NENAČTE, tajné a webové se odmítne.
     * Vrací, co se opravdu uložilo.
     */
    @Synchronized fun importFromSummary(lines: List<String>, scope: String = "global"): List<Mem.Entry> {
        val ts = tombstones()
        return lines.map { it.trim().removePrefix("-").trim() }.filter { it.length >= 3 && Mem.fingerprint(it) !in ts }
            .mapNotNull { (remember(it, scope, origin = Mem.Origin.SUGGESTION_ACCEPTED) as? Mem.Outcome.Saved)?.entry }
    }

    // ------------------------------------------------------------------ návrhy (opakovaný vzorec ≠ hned pravidlo)

    /** Zaznamená pozorování (např. uživatel 3× přepsal odpověď na stručnou). Vrátí NÁVRH až od 3 výskytů ve 2+ dnech. */
    @Synchronized fun observe(pattern: String, scope: String = "global"): Mem.Suggestion? {
        val key = "memory/obs/" + Mem.fingerprint(pattern + "|" + scope)
        val days = (kv.read(key)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()) + (clock() / 86_400_000)
        kv.write(key, days.takeLast(20).joinToString(","))
        if (Mem.fingerprint(pattern) in tombstones()) return null
        if (all(scope).any { Mem.fingerprint(it.text) == Mem.fingerprint(pattern) }) return null
        return if (days.size >= 3 && days.distinct().size >= 2) Mem.Suggestion(pattern, Mem.topicOf(pattern), days.size, scope) else null
    }

    @Synchronized fun accept(s: Mem.Suggestion) = remember(s.text, s.scope, origin = Mem.Origin.SUGGESTION_ACCEPTED)

    // ------------------------------------------------------------------ použití v odpovědi

    /** Pokyn pro model: preference (globální + tohoto projektu) a fakta projektu, s původem. */
    @Synchronized fun context(scope: String?, limit: Int = 12): String? {
        val es = all(scope).take(limit)
        if (es.isEmpty()) return null
        return "Co víš o uživateli a projektu (z paměti, uživatel to může kdykoli změnit):\n" + es.joinToString("\n") { "- ${it.text} [${it.kind.label}]" }
    }

    /** „Kde bydlím?“ / „Jak se jmenuju?“ – odpověď z paměti (null = není to otázka na vlastní údaj). */
    @Synchronized fun recall(question: String, scope: String? = null): String? {
        val slot = Mem.askSlot(question) ?: return null
        val e = all(scope).firstOrNull { (it.topic.ifEmpty { Mem.topicOf(it.text) }) == slot }
            ?: return "To zatím nevím – neřekl/a jsi mi to. Stačí napsat třeba „pamatuj si, že ${SLOT_EXAMPLE[slot]}“ a budu to vědět."
        return "Podle paměti: „${e.text}“. Změníš to větou „pamatuj si, že …“, smažeš „zapomeň …“."
    }
    private val SLOT_EXAMPLE = mapOf("slot:bydliste" to "bydlím v …", "slot:jmeno" to "se jmenuju …", "slot:prace" to "pracuju jako …", "slot:vek" to "je mi … let", "slot:partner" to "manželka se jmenuje …")

    /** Přehled „Co si pamatuješ a proč?“. */
    @Synchronized fun explain(scope: String? = null): String {
        val es = all(scope)
        if (es.isEmpty()) return "Zatím si nic nepamatuji. Řekni „pamatuj si, že …“ a uložím to hned."
        return es.joinToString("\n") { "• ${it.text}\n  ↳ ${it.why()}" }
    }
}

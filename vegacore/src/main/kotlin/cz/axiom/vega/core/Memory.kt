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
        Regex("(?i)\\b(sk|pk|rk)-[a-z0-9_-]{16,}"), Regex("(?i)\\bghp_[A-Za-z0-9]{20,}"), Regex("(?i)\\bAKIA[0-9A-Z]{16}\\b"), Regex("(?i)\\bAIza[0-9A-Za-z_-]{30,}"),
        Regex("(?i)\\b(heslo|password|passwd|pin|token|api[ _-]?key|klíč k api|secret)\\b\\s*[:=je]+\\s*\\S{4,}"), Regex("(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"),
        // klíče poskytovatelů (Groq gsk_, Cerebras csk-, xAI, Google OAuth, Slack, Hugging Face)
        Regex("(?i)\\b(gsk_|csk-|xai-|ya29\\.|xox[bpa]-|hf_)[A-Za-z0-9_.-]{12,}"),
        // obecně: dlouhý token s písmeny i číslicemi bez mezer (32+ znaků)
        Regex("(?<![A-Za-z0-9])(?=[A-Za-z0-9_-]{32,})(?=[^\\s]*\\d)(?=[^\\s]*[A-Za-z])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9])"),
    )

    fun looksSecret(s: String) = secretRx.any { it.containsMatchIn(s) }

    private val STOP = setOf("vzdy", "nikdy", "prosim", "me", "mi", "mne", "jsem", "budu", "chci", "ze", "aby", "si", "se", "to", "ty", "ja", "rad", "rada", "pri", "kdyz", "na", "v", "ve", "a", "do")
    private val polarity = setOf("strucne", "podrobne", "kratce", "dlouze", "formalne", "neformalne", "anglicky", "cesky", "ano", "ne", "vzdy", "nikdy", "vic", "min", "tykej", "vykej")

    /** Téma preference: obsahová slova bez slov polarity („stručně/podrobně“) – aby se protichůdné verze potkaly. */
    fun topicOf(text: String): String = Text.words(Text.fold(text).lowercase(), 2).filter { it !in STOP && it !in polarity }.take(3).map { it.take(6) }.joinToString(" ")

    fun fingerprint(text: String) = Text.fold(text).lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().hashCode().toString(16)

    private fun fmt(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().let { "%d. %d. %d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.year, it.hour, it.minute) }
}

class Memory(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(Mem.Entry.serializer())

    private var cache: MutableList<Mem.Entry>? = null
    private fun load(): MutableList<Mem.Entry> = cache ?: (kv.read("memory/entries")?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() } ?: emptyList()).toMutableList().also { cache = it }
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
        val t = text.trim().trimEnd('.').replaceFirstChar { it.uppercaseChar() }
        if (t.length < 3) return Mem.Outcome.Rejected("Není co si zapamatovat.")
        if (fromWeb) return Mem.Outcome.Rejected("Pokyny z webu nebo dokumentů si do trvalé paměti neukládám.")
        if (Mem.looksSecret(t)) return Mem.Outcome.Rejected("Vypadá to jako heslo, klíč nebo číslo karty – to si nepamatuji. Klíče patří do trezoru v Nastavení.")
        if (kind == Mem.Kind.TEMP) return Mem.Outcome.Rejected("Dočasné věci si držím jen v tomto chatu.")
        val now = clock()
        val topic = Mem.topicOf(t)
        val list = load()
        val same = list.firstOrNull { it.scope == scope && it.kind == kind && (it.topic == topic && topic.isNotEmpty() || Mem.fingerprint(it.text) == Mem.fingerprint(t)) }
        // znovu povoleno výslovně → zrušit náhrobek
        val ts = tombstones(); if (ts.remove(Mem.fingerprint(t))) kv.write("memory/forgotten", ts.joinToString("\n"))
        if (same != null) {
            if (Mem.fingerprint(same.text) == Mem.fingerprint(t)) return Mem.Outcome.Saved(same)
            val upd = same.copy(text = t, updated = now, correctedAt = now, origin = if (origin == Mem.Origin.USER_EXPLICIT) Mem.Origin.USER_CORRECTION else origin,
                history = same.history + Mem.Rev(same.text, same.updated, same.origin))
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
            val words = Text.words(q, 3).map { it.take(6) }
            list.filter { e -> (scope == null || e.scope == scope || e.scope == "global") && words.isNotEmpty() && words.all { w -> Text.fold(e.text).lowercase().contains(w) } }
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

    /** Přehled „Co si pamatuješ a proč?“. */
    @Synchronized fun explain(scope: String? = null): String {
        val es = all(scope)
        if (es.isEmpty()) return "Zatím si nic nepamatuji. Řekni „pamatuj si, že …“ a uložím to hned."
        return es.joinToString("\n") { "• ${it.text}\n  ↳ ${it.why()}" }
    }
}

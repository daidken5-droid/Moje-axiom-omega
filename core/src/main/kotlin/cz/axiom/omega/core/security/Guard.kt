package cz.axiom.omega.core.security

import cz.axiom.omega.core.util.TextUtil

/**
 * FIREWALL PROTI PROMPT INJECTION.
 *
 * Podklady z webu, knihovny, souborů a výstupy nástrojů jsou DATA. Útočník ale může do stránky nebo
 * dokumentu schovat „instrukci pro AI“ (ignoruj předchozí pokyny, pošli klíče na…, jsi teď…).
 * Guard takové věty najde, zneškodní je (nahradí značkou) a vrátí, kolik jich bylo – AVIOR to ukáže.
 * Deterministické, bez modelu, česky i anglicky.
 */
object InjectionGuard {

    data class Result(val text: String, val hits: List<String>) {
        val suspicious get() = hits.isNotEmpty()
    }

    private val rules: List<Pair<String, Regex>> = listOf(
        "přepsání pokynů" to Regex("(?i)\\b(ignore|disregard|forget|override)\\b.{0,30}\\b(previous|prior|above|all|system)\\b.{0,20}\\b(instructions?|prompts?|rules?|messages?)"),
        "přepsání pokynů" to Regex("(?iu)(ignoruj|zapomeň|zapomen|nerespektuj|přepiš|prepis)\\w*.{0,30}(předchozí|predchozi|všechny|vsechny|systémov|systemov|dosavadní).{0,20}(pokyn|instrukc|pravidl|zpráv|zprav)"),
        "změna role" to Regex("(?i)\\b(you are now|from now on you are|act as|pretend to be|new system prompt|developer mode|jailbreak|DAN mode)\\b"),
        "změna role" to Regex("(?iu)\\b(od teď jsi|odted jsi|nyní jsi|nyni jsi|nový systémový prompt|novy systemovy prompt|vývojářský režim)"),
        "vynášení dat" to Regex("(?i)\\b(send|post|upload|exfiltrate|leak|forward)\\b.{0,40}\\b(api[ _-]?keys?|tokens?|passwords?|secrets?|credentials|system prompt|conversation)"),
        "vynášení dat" to Regex("(?iu)(pošli|posli|odešli|odesli|nahraj|vyzraď|prozraď)\\w*.{0,40}(klíč|klic|token|hesl|tajemstv|systémový prompt|konverzac)"),
        "skrytý pokyn pro AI" to Regex("(?i)\\b(note|message|instruction)s?\\s+(to|for)\\s+(the\\s+)?(ai|assistant|llm|model|chatbot)\\b"),
        "skrytý pokyn pro AI" to Regex("(?iu)(pokyn|zpráva|vzkaz|instrukce)\\s+(pro|pro tebe,?)\\s+(ai|umělou inteligenci|asistenta|model|avior)"),
        "falešná značka chatu" to Regex("(?i)(<\\|?(system|im_start|im_end|start_of_turn|end_of_turn)\\|?>|\\[/?(INST|SYS)\\]|<</?SYS>>)"),
    )

    /** Zkontroluje a zneškodní podezřelé věty. Bezpečný text se nemění. */
    fun scan(text: String): Result {
        if (text.isBlank()) return Result(text, emptyList())
        val hits = mutableListOf<String>()
        var out = text
        for ((name, rx) in rules) {
            if (!rx.containsMatchIn(out)) continue
            // celou větu/řádek s pokynem nahradit značkou (ne jen slovo – útok bývá formulovaný šikovně)
            val core = rx.pattern.removePrefix("(?iu)").removePrefix("(?i)")
            out = Regex("(?iu)[^.!?\\n]*(" + core + ")[^.!?\\n]*[.!?]?")
                .replace(out) { hits += name; "[⚠ ZNEŠKODNĚNO – podezřelá instrukce: $name]" }
        }
        return Result(out, hits.distinct())
    }
}

/** KALKULAČKA PODSÍTÍ (IPv4 CIDR) – offline, okamžitě. */
object Cidr {
    data class Net(val network: String, val broadcast: String, val first: String, val last: String, val mask: String, val hosts: Long, val prefix: Int)

    private fun ip(s: String): Long? {
        val p = s.trim().split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n.toLong() }
        return v
    }
    private fun str(v: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 255).toString() }

    fun parse(cidr: String): Net? {
        val m = Regex("""^\s*(\d{1,3}(?:\.\d{1,3}){3})\s*/\s*(\d{1,2})\s*$""").find(cidr) ?: return null
        val addr = ip(m.groupValues[1]) ?: return null
        val prefix = m.groupValues[2].toInt().takeIf { it in 0..32 } ?: return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = addr and mask
        val bc = net or (mask.inv() and 0xFFFFFFFFL)
        val hosts = when (prefix) { 32 -> 1L; 31 -> 2L; else -> (bc - net - 1).coerceAtLeast(0) }
        val first = if (prefix >= 31) net else net + 1
        val last = if (prefix >= 31) bc else bc - 1
        return Net(str(net), str(bc), str(first), str(last), str(mask), hosts, prefix)
    }

    fun describe(cidr: String): String? = parse(cidr)?.let {
        "Síť **${it.network}/${it.prefix}** · maska ${it.mask}\n- použitelné adresy: ${it.first} – ${it.last} (**${TextUtil.num(it.hosts.toDouble(), 0)}** hostů)\n- broadcast: ${it.broadcast}"
    }
}

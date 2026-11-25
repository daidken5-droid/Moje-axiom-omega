package cz.axiom.vega.astraea

import cz.axiom.vega.core.Evidence
import cz.axiom.vega.core.Grounding

/**
 * Adaptéry mezi ASTRAEA jádrem a STÁVAJÍCÍMI částmi VEGY (nic se nepřepisuje):
 *  - tvrzení = Grounding.claims (atomizace a citace [n], kterou VEGA už používá),
 *  - posuzovač = Grounding.Checker (ONNX ověřovač; bez balíčku Missing → ready=false → UNEVALUATED),
 *  - původ zdroje = Evidence (dva přepisy jedné zprávy = jeden původ).
 */
object VegaAdapters {
    /** Původ: pokud text přebírá jinou zprávu („podle zprávy ČTK…“, odkaz v textu), je původem ta; jinak vlastní adresa. */
    fun originOf(title: String, text: String, url: String): String =
        // stejné pořadí přednosti pro web i místní dokumenty (ne „první nalezený“ – ten závisel na tom, co stojí v textu dřív):
        // výslovně převzatá agentura > textový odkaz na zprávu > adresa uvedená v textu
        runCatching { Evidence.parse(Evidence.Doc(title, text)).origins.minWithOrNull(compareBy({ o -> listOf("agentura:", "txt:", "url:").indexOfFirst { o.startsWith(it) }.let { if (it < 0) 9 else it } }, { it })) }.getOrNull()
            ?: ("url:" + Evidence.normUrl(url.ifBlank { title }))

    /** Atomická tvrzení s citacemi [n] → porty jádra; ID zdroje = pořadí zdroje (1, 2, …) – nevytváří je model. */
    fun claimsOf(text: String): List<Claim> =
        Grounding.claims(text).mapIndexed { i, c -> Claim("c${i + 1}", c.text, c.cites.map { it.toString() }) }

    fun verdictOf(label: Grounding.Label): Verdict = when (label) {
        Grounding.Label.PODPORUJE -> Verdict.SUPPORTS
        Grounding.Label.ODPORUJE -> Verdict.CONTRADICTS
        Grounding.Label.NEDOSTACUJE -> Verdict.INSUFFICIENT
        Grounding.Label.NEVYHODNOCENO -> Verdict.UNEVALUATED
    }

    fun judgeOf(checker: Grounding.Checker): EvidenceJudge = object : EvidenceJudge {
        override val ready get() = checker.ready
        override fun judge(statement: String, excerpt: String) = verdictOf(checker.check(statement, excerpt).label)
    }

    /** Ověřovač s pamětí: jádro i podrobné karty citací se ptají na stejné dvojice → model počítá každou jen jednou. */
    class MemoChecker(private val inner: Grounding.Checker) : Grounding.Checker {
        private val memo = HashMap<Pair<String, String>, Grounding.Verdict>()
        var computed = 0; private set
        override val ready get() = inner.ready
        override val status get() = inner.status
        override fun check(claim: String, passage: String) = synchronized(memo) {
            memo.getOrPut(claim to passage) { computed++; inner.check(claim, passage) }
        }
    }
}

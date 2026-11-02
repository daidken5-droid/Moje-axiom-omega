package cz.axiom.omega.core.randy

import cz.axiom.omega.core.agent.AgentRole
import cz.axiom.omega.core.device.Power
import cz.axiom.omega.core.agent.Router
import cz.axiom.omega.core.agent.Variant
import cz.axiom.omega.core.util.TextUtil

/**
 * RANDY SUPERVISOR – nadřazený řídicí modul AVIOR.
 *
 * Z protokolu hloubky (L0–L6) se RANDY stává „ředitelem“ celého systému:
 *  1. PLÁNUJE – před každým dotazem rozhodne, které podfunkce aktivovat: Ω variantu, režim (odpověď/agent),
 *     roli agenta, hloubku RANDY, počet průchodů, přemýšlení, limit kroků a časový rozpočet.
 *  2. ŘÍDÍ ZDROJE – podle baterie, teploty, volné RAM a sítě škrtí nebo uvolňuje výkon (efficiency).
 *  3. DOHLÍŽÍ – hlídá běžícího agenta (smyčky, opakovaná volání, selhávající nástroje, překročený čas)
 *     a může zablokovat nástroj nebo vynutit závěr.
 *  4. ESKALUJE – když je výsledek nejistý, sám spustí silnější variantu (effectiveness).
 *  5. UČÍ SE – měří čas, jistotu a hodnocení každé varianty a příště volí efektivněji.
 *
 * Pravomoci určuje uživatel: PORADNÍ (jen doporučuje), ŘÍDÍCÍ (výchozí – plánuje, řídí zdroje, dohlíží),
 * PLNÁ (navíc eskaluje a smí zkrátit i ručně zvolený režim, když hrozí přehřátí nebo vybitá baterie).
 * Výslovnou volbu varianty/role/hloubky uživatelem RANDY nikdy nemění – jen jí může ubrat zdroje (PLNÁ).
 */
object Supervisor {

    enum class Authority(val label: String, val description: String) {
        ADVISORY("Poradní", "RANDY jen doporučuje, nic nemění."),
        GOVERN("Řídící", "Plánuje podfunkce, řídí zdroje a dohlíží na agenty."),
        FULL("Plná", "Navíc sám eskaluje na silnější variantu a šetří zdroje i u ručních voleb."),
        ;
        companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: GOVERN }
    }

    data class Device(
        val batteryPct: Int? = null,
        val charging: Boolean? = null,
        /** 0 = v normě … 4 = kritické (PowerManager.THERMAL_STATUS_*) */
        val thermal: Int = 0,
        val freeRamMb: Long? = null,
        val online: Boolean = true,
        /** myslí se v telefonu (ne v cloudu) – zdroje telefonu jsou pak rozhodující */
        val local: Boolean = true,
    ) {
        val lowPower get() = batteryPct != null && batteryPct <= 20 && charging != true
        val hot get() = thermal >= 3
        fun hotFor(caution: Int) = thermal >= caution
        val tightRam get() = freeRamMb != null && freeRamMb in 1..1_500
    }

    /** Statistika jedné varianty (učení z výsledků). */
    data class VariantStat(val runs: Int = 0, val avgMillis: Long = 0, val avgConfidence: Int? = null, val good: Int = 0, val bad: Int = 0, val escalations: Int = 0) {
        val approval: Int? get() = if (good + bad == 0) null else good * 100 / (good + bad)
        /** Efektivita 0–100: kvalita (jistota, hodnocení) vůči času. */
        val efficiency: Int? get() {
            if (runs == 0) return null
            val quality = listOfNotNull(avgConfidence, approval).ifEmpty { listOf(60) }.average()
            val speed = (100 - (avgMillis / 1_000.0).coerceAtMost(180.0) / 1.8)
            return (quality * 0.7 + speed * 0.3).toInt().coerceIn(0, 100)
        }
    }

    data class Request(
        val prompt: String,
        val variant: Variant = Variant.AUTO,
        val kind: Router.Mode? = null,
        val role: AgentRole? = null,
        val level: RandyLevel? = null,
        val toolsOnline: Boolean = true,
        /** Oblast, ve které je AVIOR podle vlastní historie slabší (Cognition) → RANDY přidá hloubku. */
        val weakDomain: String? = null,
        /** Režim výkonu zvolený uživatelem: Základ / Pokročilé / Sherlock. */
        val power: cz.axiom.omega.core.device.Power = cz.axiom.omega.core.device.Power.ADVANCED,
        /** od jaké teploty (THERMAL_STATUS) šetřit – podle profilu zařízení */
        val thermalCaution: Int = 3,
        /** Myslí mozek v telefonu → RANDY šetří čas (každý průchod i krok agenta jsou na CPU drahé). */
        val localBrain: Boolean = false,
    )

    data class Plan(
        val variant: Variant,
        val mode: Router.Mode?,
        val role: AgentRole?,
        val level: RandyLevel?,
        val passes: Int?,
        val thinking: Boolean?,
        val steps: Int?,
        /** časový rozpočet úlohy – po překročení RANDY vynutí závěr */
        val budgetMillis: Long,
        /** smí RANDY po odpovědi eskalovat na silnější variantu */
        val mayEscalate: Boolean,
        val decisions: List<String>,
        val chosenByRandy: Boolean,
        val authority: Authority,
        /** Řetězec podmodelů (2–3 fáze), když úloha potřebuje víc dovedností za sebou. Prázdný = jedna varianta. */
        val pipeline: List<Stage> = emptyList(),
        val power: cz.axiom.omega.core.device.Power = cz.axiom.omega.core.device.Power.ADVANCED,
        /** styl režimu výkonu (Sherlock: dedukce) – přidá se k stylu varianty */
        val extraStyle: String? = null,
    ) {
        val summary get() = if (pipeline.size >= 2) "RANDY → " + pipeline.joinToString(" → ") { it.variant.title }
        else "RANDY → ${variant.title}" + (role?.let { " · ${it.title}" } ?: "") + (level?.let { " · ${it.code}" } ?: "")
    }

    /** Jedna fáze řetězce: varianta + co má v řetězci udělat. */
    data class Stage(val variant: Variant, val purpose: String)

    // ------------------------------------------------------------------ 1) plán

    private val creative = Regex("(?i)\\b(basen|basnick|pribeh|povidk|vymysli|slogan|napady|napad na|text pisne|rym|pohadk|scenar)")
    private val teach = Regex("(?i)\\b(nauc me|vysvetli mi|vysvetli krok|jak se uci|pro zacatecnik|polopate|doucovani)")
    private val verify = Regex("(?i)\\b(je pravda|je to pravda|over\\w*|konspira\\w*|hoax|fake|dezinforma\\w*|fakt nebo)")
    private val research = Regex("(?i)\\b(resers\\w*|prozkoumej|zmapuj|porovnej (nabidky|ceny|modely|produkty)|najdi (vsechny|zdroje|studie)|prehled trhu|report o)")
    private val deep = Regex("(?i)\\b(proc|analyzuj|zdovodni|dokaz|porovnej|navrhni|strategi\\w*|vyhody a nevyhody|optimaliz\\w*|rozhodni|zvaz)")
    private val quant = Regex("(?i)\\b(kurz|sazk\\w*|bankroll|kelly|ev\\b|roi|hypotek\\w*|uver\\w*|splatk\\w*|uro[kc]\\w*|investic\\w*|marze|rozpocet|kalkulac\\w*)")
    private val code = Regex("(?i)\\b(kod|skript\\w*|program\\w*|python|kotlin|javascript|funkc\\w* v|regex|sql|bug)")
    private val app = Regex("(?i)\\b(apk|aplikac\\w*|appk\\w*)")
    private val office = Regex("(?i)\\b(docx|word\\w*|pdf|smlouv\\w*|dopis\\w*|faktur\\w*|zadost\\w*|vypoved\\w*|plnou moc|zapis z)")
    private val critique = Regex("(?i)\\b(kritik\\w*|slabin\\w*|rizik\\w*|red.?team|oponentur\\w*|co muze selhat|najdi chyby|zkontroluj|over spravnost)")
    private val decision = Regex("(?i)\\b(vyplati|mam (si )?(koupit|vsadit|investovat|vzit)|rozhodni|doporuc\\w*|stoji (to )?za to)")
    private val make = Regex("(?i)\\b(vytvor|vygeneruj|udelej|naprogramuj|napis|uloz|exportuj|priprav|sestav|vyrob)")

    fun plan(req: Request, device: Device, stats: Map<Variant, VariantStat>, authority: Authority): Plan {
        val why = mutableListOf<String>()
        val f = " " + TextUtil.fold(req.prompt) + " "
        val explicitVariant = req.variant != Variant.AUTO
        val explicitAny = explicitVariant || req.kind != null || req.role != null || req.level != null

        // a) Která podfunkce? (jen když uživatel nic nevybral a RANDY smí řídit)
        var v = req.variant
        var chosen = false
        if (!explicitAny && authority != Authority.ADVISORY) {
            val words = TextUtil.words(req.prompt, 1).size
            val makes = make.containsMatchIn(f)
            v = when {
                makes && app.containsMatchIn(f) -> Variant.STUDIO.also { why += "zadání = vyrobit aplikaci → Ω Studio" }
                makes && office.containsMatchIn(f) -> Variant.OFFICE.also { why += "zadání = dokument → Ω Office" }
                makes && code.containsMatchIn(f) -> Variant.CODE.also { why += "zadání = kód → Ω Code" }
                verify.containsMatchIn(f) -> Variant.VERITAS.also { why += "ověřování tvrzení → Ω Veritas" }
                research.containsMatchIn(f) -> if (req.toolsOnline) Variant.RESEARCH.also { why += "rešerše z více zdrojů → Ω Deep Research" }
                else Variant.THINK.also { why += "rešerše offline → Ω Think s offline knihovnou" }
                quant.containsMatchIn(f) && Regex("\\d").containsMatchIn(f) -> Variant.QUANT.also { why += "čísla a peníze → Ω Quant" }
                creative.containsMatchIn(f) -> Variant.MUSE.also { why += "tvůrčí zadání → Ω Muse" }
                teach.containsMatchIn(f) -> Variant.MENTOR.also { why += "výuka → Ω Mentor" }
                deep.containsMatchIn(f) || words > 60 -> Variant.THINK.also { why += "složitá úvaha → Ω Think" }
                words <= 7 && !Regex("^ (jak|proc|vysvetli|popis|co je|kdo byl)").containsMatchIn(f) ->
                    Variant.FLASH.also { why += "krátký jednoduchý dotaz → Ω Flash (úspora času)" }
                else -> Variant.AUTO.also { why += "běžný dotaz → standardní RANDY" }
            }
            // Sebepoznání: ve slabé oblasti nešetřit – důkladnější varianta
            if (req.weakDomain != null && (v == Variant.FLASH || v == Variant.AUTO)) {
                v = Variant.THINK; why += "v oblasti ${req.weakDomain} jsem zatím slabší → Ω Think"
            }
            chosen = v != Variant.AUTO
            // Učení: varianta, kterou uživatel opakovaně hodnotí špatně, se nahradí bezpečnější
            stats[v]?.let { st ->
                val ap = st.approval
                if (ap != null && st.good + st.bad >= 5 && ap < 35 && v == Variant.FLASH) {
                    v = Variant.AUTO; why += "Ω Flash má nízké hodnocení (${ap} %) → plná odpověď"
                }
            }
        } else if (explicitAny) {
            why += "respektuji tvou volbu"
        }

        var mode = req.kind ?: v.mode
        val role = req.role ?: v.role
        val level = req.level ?: v.level
        var passes = v.passes
        var thinking = v.thinking
        var steps = v.steps
        var budget = if (mode == Router.Mode.AGENT) 6 * 60_000L else 3 * 60_000L

        // a2) REŽIM VÝKONU (Základ / Pokročilé / Sherlock)
        var extraStyle: String? = null
        when (req.power) {
            Power.BASIC -> {
                if (!explicitAny && v == Variant.THINK) { v = Variant.AUTO; mode = Variant.AUTO.mode }
                passes = 1; thinking = false; steps = minOf(steps ?: 6, 6); budget = minOf(budget, 2 * 60_000L)
                why += "režim Základ → rychle a úsporně"
            }
            Power.SHERLOCK -> {
                if (!explicitAny && v in setOf(Variant.AUTO, Variant.FLASH, Variant.MENTOR)) { v = Variant.THINK; mode = Variant.THINK.mode }
                passes = maxOf(passes ?: 3, 3); thinking = true
                steps = maxOf(steps ?: 15, 15); budget = minOf(budget * 2, 15 * 60_000L)
                extraStyle = SHERLOCK_STYLE
                why += "režim Sherlock → dedukce, maximum průchodů a ověřování"
            }
            Power.ADVANCED -> Unit
        }

        // b) Offline: rešerše bez webu nedává smysl
        if (!req.toolsOnline && v == Variant.RESEARCH && !explicitVariant) {
            v = Variant.THINK; mode = Router.Mode.CHAT; why += "offline → místo webové rešerše Ω Think s offline knihovnou"
        }

        // c) Řízení zdrojů telefonu (efficiency)
        val governs = authority != Authority.ADVISORY && device.local
        val mayCutExplicit = authority == Authority.FULL
        if (governs && (device.lowPower || device.hotFor(req.thermalCaution))) {
            if (!explicitAny || mayCutExplicit) {
                passes = 1; thinking = false; steps = minOf(steps ?: 6, 6); budget = minOf(budget, 2 * 60_000L)
                why += if (device.hotFor(req.thermalCaution)) "telefon je horký → úsporný režim (1 průchod, bez přemýšlení)" else "baterie ${device.batteryPct} % → úsporný režim"
            } else why += "baterie/teplota je kritická – zvaž úspornější variantu"
        } else if (governs && device.tightRam) {
            if (!explicitAny || mayCutExplicit) { passes = minOf(passes ?: 2, 2); why += "málo volné RAM → max. 2 průchody" }
        } else if (governs && device.charging == true && v == Variant.THINK) {
            budget = 5 * 60_000L; why += "nabíjí se → plný výkon"
        }

        // d) Učení z času: pomalá varianta v telefonu → kratší
        stats[v]?.let { st ->
            if (governs && st.runs >= 3 && st.avgMillis > 150_000 && device.charging != true && (!explicitAny || mayCutExplicit) && (passes ?: 3) > 2) {
                passes = 2; why += "${v.title} tu trvá v průměru ${st.avgMillis / 1000} s → 2 průchody"
            }
        }

        // e) KOMBINACE PODMODELŮ: úloha potřebuje víc dovedností za sebou → řetězec
        var pipeline = emptyList<Stage>()
        val saving = device.local && (device.lowPower || device.hotFor(req.thermalCaution))
        if (!explicitAny && authority != Authority.ADVISORY && req.power != Power.BASIC) {
            pipeline = pipelineFor(f, req.toolsOnline, if (req.power == Power.SHERLOCK) Authority.FULL else authority, saving)
            // Sherlock: každý netriviální závěr projde oponenturou
            if (req.power == Power.SHERLOCK && !saving && Variant.NEMESIS !in pipeline.map { it.variant }) {
                pipeline = when {
                    pipeline.size >= 2 -> (if (pipeline.size >= 3) pipeline.take(2) else pipeline) + Stage(Variant.NEMESIS, "oponentura: najdi chyby a slabá místa v závěru")
                    (mode ?: Variant.AUTO.mode) != Router.Mode.AGENT && v != Variant.MUSE ->
                        listOf(Stage(v, "dedukce a odpověď"), Stage(Variant.NEMESIS, "oponentura: najdi chyby, oprav je a dej finální závěr"))
                    else -> pipeline
                }
            }
            if (pipeline.size >= 2) {
                why += "úloha potřebuje víc dovedností → řetězec " + pipeline.joinToString(" → ") { it.variant.title }
                v = pipeline.last().variant
                budget = (budget * pipeline.size).coerceAtMost(12 * 60_000L)
            }
        }

        // f) RYCHLOST V TELEFONU: mimo Sherlock jeden průchod, bez skrytého přemýšlení, bez opakování,
        //    ověřování a rešerše offline jako přímá odpověď z offline znalostí (ne vícekrokový agent).
        val fast = req.localBrain && req.power != Power.SHERLOCK
        if (fast) {
            if (passes == null || passes > 1) passes = 1
            if (!explicitAny || thinking == true) thinking = false
            if (!explicitAny && mode == Router.Mode.AGENT && v in setOf(Variant.VERITAS, Variant.RESEARCH) && pipeline.size < 2) {
                mode = Router.Mode.CHAT
                extraStyle = listOfNotNull(extraStyle, if (v == Variant.VERITAS) VERIFY_STYLE else null).joinToString("\n").ifBlank { null }
                why += "v telefonu ověřím přímo z offline znalostí (rychleji než agent)"
            }
            pipeline = pipeline.filter { it.variant != Variant.THINK || pipeline.size <= 2 }.let { if (it.size >= 2) it else emptyList() }
            extraStyle = listOfNotNull(extraStyle, FAST_STYLE).joinToString("\n")
            why += "mozek v telefonu → stručně a v jednom průchodu"
        }
        val mayEscalate = !fast && pipeline.isEmpty() && req.power != Power.BASIC && authority == Authority.FULL && !explicitAny && mode != Router.Mode.AGENT && !saving
        if (authority == Authority.ADVISORY) {
            // poradní režim: nic nemění, jen radí
            return Plan(req.variant, req.kind ?: req.variant.mode, req.role ?: req.variant.role, req.level ?: req.variant.level, req.variant.passes,
                req.variant.thinking, req.variant.steps, budget, false, why.ifEmpty { listOf("poradní režim – bez zásahu") }, false, authority)
        }
        return Plan(v, if (pipeline.size >= 2) v.mode else mode, if (pipeline.size >= 2) v.role else role, if (pipeline.size >= 2) v.level else level,
            passes, thinking, steps, budget, mayEscalate, why, chosen || pipeline.size >= 2, authority, pipeline, req.power, extraStyle)
    }

    /**
     * Rozpozná, že úloha potřebuje víc podmodelů, a seřadí je logicky:
     * sběr (rešerše, ověření) → analýza (výpočet, úvaha) → tvorba (text, kód, aplikace, dokument) → kritika.
     */
    fun pipelineFor(f: String, online: Boolean, authority: Authority, saving: Boolean): List<Stage> {
        val makes = make.containsMatchIn(f)
        val found = LinkedHashMap<Variant, String>()
        if (research.containsMatchIn(f)) found[if (online) Variant.RESEARCH else Variant.THINK] = if (online) "sběr a ověření podkladů z více zdrojů" else "promyšlení z offline knihovny"
        if (verify.containsMatchIn(f)) found[Variant.VERITAS] = "ověření tvrzení a důkazů"
        if (quant.containsMatchIn(f) && Regex("\\d").containsMatchIn(f)) found[Variant.QUANT] = "přesný výpočet a čísla"
        if (deep.containsMatchIn(f) && (makes || found.isNotEmpty())) found.putIfAbsent(Variant.THINK, "návrh a promyšlení řešení")
        if (creative.containsMatchIn(f)) found[Variant.MUSE] = "tvůrčí text"
        if (makes && code.containsMatchIn(f) && !app.containsMatchIn(f)) found[Variant.CODE] = "napsání a uložení kódu"
        if (makes && app.containsMatchIn(f)) found[Variant.STUDIO] = "výroba aplikace (APK)"
        if (makes && office.containsMatchIn(f)) found[Variant.OFFICE] = "zpracování do dokumentu"
        if (critique.containsMatchIn(f) && found.isNotEmpty()) found[Variant.NEMESIS] = "kritická kontrola a rizika"
        // vysoké sázky: výpočet + rozhodnutí → s plnou pravomocí přidej oponenturu
        if (authority == Authority.FULL && Variant.QUANT in found && decision.containsMatchIn(f) && Variant.NEMESIS !in found) {
            found[Variant.NEMESIS] = "oponentura rozhodnutí (vysoké sázky)"
        }
        if (found.size < 2) return emptyList()
        val order = listOf(Variant.RESEARCH, Variant.VERITAS, Variant.QUANT, Variant.THINK, Variant.MUSE, Variant.CODE, Variant.STUDIO, Variant.OFFICE, Variant.NEMESIS)
        val stages = order.filter { it in found }.map { Stage(it, found.getValue(it)) }
        val max = if (saving) 2 else 3
        // drž poslední (výstupní) fázi: když je moc fází, vynech prostřední
        return if (stages.size <= max) stages else stages.take(max - 1) + stages.last()
    }

    const val FAST_STYLE = "Odpovídej stručně a k věci (zhruba do 150 slov), pokud uživatel výslovně nechce podrobnosti. Žádné zbytečné úvody ani opakování otázky."
    const val VERIFY_STYLE = "Ověř tvrzení: rozděl ho na dílčí výroky a u každého uveď PRAVDA / ZAVÁDĚJÍCÍ / NEPODLOŽENO / NEPRAVDA s krátkým důvodem a zdrojem z podkladů."

    const val SHERLOCK_STYLE = "REŽIM SHERLOCK – metoda dedukce: 1) pozorování (co přesně víme, včetně drobností), " +
        "2) hypotézy (všechna rozumná vysvětlení), 3) důkazy pro a proti každé, 4) vylučování – co zbude, je nejpravděpodobnější, " +
        "5) závěr s mírou jistoty a tím, co by ho vyvrátilo. Hledej nesrovnalosti a skryté předpoklady, nic nepřijímej bez důkazu."

    // ------------------------------------------------------------------ 2) eskalace

    /** Po odpovědi: má RANDY spustit silnější variantu? Vrací variantu nebo null. */
    fun escalation(plan: Plan, confidence: Int?, answerChars: Int): Variant? {
        if (!plan.mayEscalate) return null
        val weak = (confidence != null && confidence < 45) || answerChars < 40
        if (!weak) return null
        return when (plan.variant) {
            Variant.FLASH, Variant.AUTO, Variant.MENTOR, Variant.MUSE -> Variant.THINK
            Variant.QUANT -> Variant.NEMESIS
            else -> null
        }
    }

    // ------------------------------------------------------------------ 3) dohled nad agentem

    /**
     * Strážce kroků agenta. Volá se před každým nástrojem; může volání zablokovat (smyčka, opakovaně
     * selhávající nástroj) nebo nařídit závěr (časový rozpočet).
     */
    class Watchdog(private val budgetMillis: Long, private val authority: Authority, private val now: () -> Long = System::currentTimeMillis) {
        private val started = now()
        private val seen = HashMap<String, String>()
        private val failures = HashMap<String, Int>()
        val interventions = mutableListOf<String>()

        sealed class Verdict {
            data object Run : Verdict()
            data class Reuse(val observation: String, val note: String) : Verdict()
            data class Block(val note: String) : Verdict()
        }

        fun overBudget(): Boolean {
            if (authority == Authority.ADVISORY) return false
            val over = now() - started > budgetMillis
            if (over && interventions.none { it.startsWith("čas") }) interventions += "čas ${budgetMillis / 1000} s vyčerpán → RANDY nařídil závěr"
            return over
        }

        fun before(tool: String, args: String): Verdict {
            if (authority == Authority.ADVISORY) return Verdict.Run
            val key = "$tool|${args.replace(Regex("\\s+"), "").lowercase()}"
            seen[key]?.let { prev ->
                val note = "opakované volání $tool → RANDY použil předchozí výsledek"
                interventions += note
                return Verdict.Reuse(prev, note)
            }
            if ((failures[tool] ?: 0) >= 2) {
                val note = "nástroj $tool selhal 2× → RANDY ho zablokoval"
                if (note !in interventions) interventions += note
                return Verdict.Block(note)
            }
            return Verdict.Run
        }

        fun after(tool: String, args: String, observation: String) {
            val key = "$tool|${args.replace(Regex("\\s+"), "").lowercase()}"
            seen[key] = observation
            if (observation.startsWith("CHYBA")) failures[tool] = (failures[tool] ?: 0) + 1 else failures.remove(tool)
        }
    }

    // ------------------------------------------------------------------ 4) učení

    fun update(old: VariantStat?, millis: Long, confidence: Int?, escalated: Boolean): VariantStat {
        val o = old ?: VariantStat()
        val n = o.runs + 1
        val avgMs = (o.avgMillis * o.runs + millis) / n
        val avgConf = when {
            confidence == null -> o.avgConfidence
            o.avgConfidence == null -> confidence
            else -> ((o.avgConfidence * minOf(o.runs, 19)) + confidence) / (minOf(o.runs, 19) + 1)
        }
        return o.copy(runs = n, avgMillis = avgMs, avgConfidence = avgConf, escalations = o.escalations + if (escalated) 1 else 0)
    }

    fun rated(old: VariantStat?, good: Boolean): VariantStat =
        (old ?: VariantStat()).let { if (good) it.copy(good = it.good + 1) else it.copy(bad = it.bad + 1) }
}

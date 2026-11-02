package cz.axiom.omega.core.device

import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.util.TextUtil

/**
 * REGULÁTOR VÝKONU – modul s pravomocí nad tím, KOLIK výkonu dostane každý dotaz.
 *
 * Rozhoduje před RANDY Supervisorem a jeho rozhodnutí jsou závazná (Supervisor i Orchestrátor je respektují):
 *  1. ODHAD NÁROČNOSTI dotazu 0–100 (úroveň RANDY, délka, typ úlohy),
 *  2. PŘIDĚLENÍ STUPNĚ výkonu (Základ / Pokročilé / Sherlock) – v režimu Auto podle náročnosti,
 *     v ručním režimu je volba uživatele STROPEM, ne povinností,
 *  3. POJISTKY ZAŘÍZENÍ, které mají vždy přednost: baterie, teplota, volná RAM, rychlost mozku,
 *  4. ROZPOČET: max. délka odpovědi, vlákna CPU, přemýšlení, řetězce podmodelů, agent, podklady, historie.
 *
 * Čistě deterministické – každé rozhodnutí má čitelný důvod (zobrazí se u odpovědi).
 */
object Governor {

    data class Input(
        val prompt: String,
        val level: RandyLevel,
        /** null = Auto (regulátor volí sám), jinak ruční volba uživatele = strop */
        val userPower: Power?,
        val batteryPct: Int? = null,
        val charging: Boolean? = null,
        /** PowerManager.THERMAL_STATUS_* (0 = v normě … 6 = vypínání) */
        val thermal: Int = 0,
        val thermalCaution: Int = 3,
        val freeRamMb: Long? = null,
        /** odpovídá mozek v telefonu (ne cloud) */
        val local: Boolean = true,
        /** naměřená rychlost psaní mozku v telefonu (tokeny/s), pokud je známá */
        val writeTps: Double? = null,
        /** výkonná vlákna podle profilu zařízení */
        val threads: Int = 4,
        /** všechna jádra CPU (pro čtení zadání) */
        val cores: Int = 8,
        /** SÍLA VÝKONU nastavená uživatelem 25–100 % (posuvník v aplikaci) */
        val strength: Int = 75,
    )

    data class Budget(
        val power: Power,
        /** náročnost dotazu 0–100 */
        val demand: Int,
        val maxTokens: Int,
        val threads: Int,
        /** vlákna pro čtení zadání (prefill) */
        val batchThreads: Int,
        val thinking: Boolean,
        val allowPipeline: Boolean,
        val allowAgent: Boolean,
        /** počet podkladů ze znalostí */
        val knowledge: Int,
        val reasons: List<String>,
        /** pojistka zařízení stáhla výkon pod požadovaný stupeň */
        val limited: Boolean,
    ) {
        val summary get() = "${power.label} · náročnost $demand" + if (limited) " · omezeno" else ""
    }

    private val deepWords = listOf(
        "proc", "vysvetli", "porovnej", "analyz", "navrhni", "strategi", "rizik", "dokaz", "odvod", "vypocitej",
        "naplanuj", "zhodnot", "kriticky", "do hloubky", "podrobne", "krok za krokem", "pro a proti", "scenar",
    )
    private val quickWords = listOf("kolik", "kdy", "kde", "kdo", "jak se rekne", "preloz", "co je", "ano nebo ne", "rychle", "strucne")

    /** Náročnost dotazu 0–100. */
    fun demand(prompt: String, level: RandyLevel): Int {
        val f = " " + TextUtil.fold(prompt) + " "
        var d = when (level) {
            RandyLevel.L0_DIRECT -> 12
            RandyLevel.L1_EXPERT -> 32
            RandyLevel.L2_DEEP -> 52
            RandyLevel.L3_STRATEGIC_EV -> 66
            RandyLevel.L4_NEMESIS_REDTEAM -> 78
            RandyLevel.L5_FORENSIC -> 84
            RandyLevel.L6_OMNISCIENT -> 92
        }
        d += deepWords.count { it in f } * 7
        if (quickWords.any { f.startsWith(" $it") }) d -= 8
        val len = prompt.length
        d += when { len > 1200 -> 15; len > 500 -> 9; len > 200 -> 4; len < 40 -> -6; else -> 0 }
        if (prompt.count { it == '?' } >= 3) d += 5
        return d.coerceIn(0, 100)
    }

    fun decide(i: Input): Budget {
        val why = mutableListOf<String>()
        val demand = demand(i.prompt, i.level)

        // 2) stupeň podle náročnosti (Auto) / ruční volba jako strop
        val wanted = when {
            demand >= 72 -> Power.SHERLOCK
            demand >= 38 -> Power.ADVANCED
            else -> Power.BASIC
        }
        var power = if (i.userPower == null) wanted.also { why += "náročnost $demand → ${it.label}" }
        else minOf(i.userPower, wanted, compareBy { it.ordinal }).also {
            why += if (it == i.userPower) "tvá volba ${it.label}" else "tvá volba ${i.userPower.label}, dotaz stačí na ${it.label}"
        }
        // výslovně zvolený Sherlock u náročné otázky platí celý (uživatel chce maximum)
        if (i.userPower == Power.SHERLOCK && demand >= 38) power = Power.SHERLOCK
        val before = power

        // 3) POJISTKY ZAŘÍZENÍ – mají přednost před vším
        // SÍLA VÝKONU (uživatel): nízká síla nikdy nepustí Sherlock, maximum ho povolí už od střední náročnosti
        val strength = i.strength.coerceIn(25, 100)
        if (strength < 40 && power == Power.SHERLOCK) { power = Power.ADVANCED; why += "síla výkonu $strength % → bez Sherlocka" }
        if (strength >= 100 && i.userPower == null && power == Power.ADVANCED && demand >= 60) { power = Power.SHERLOCK; why += "síla výkonu 100 % → Sherlock" }

        val bat = i.batteryPct
        val onCharger = i.charging == true
        if (bat != null && !onCharger) {
            if (bat <= 10) { power = Power.BASIC; why += "baterie $bat % → jen Základ" }
            else if (bat <= 25 && power == Power.SHERLOCK) { power = Power.ADVANCED; why += "baterie $bat % → bez Sherlocka" }
        }
        val hot = i.thermal >= i.thermalCaution
        val severe = i.thermal >= i.thermalCaution + 2
        if (severe) { power = Power.BASIC; why += "telefon je přehřátý → Základ" }
        else if (hot && power == Power.SHERLOCK) { power = Power.ADVANCED; why += "telefon je teplý → bez Sherlocka" }
        val lowRam = i.freeRamMb != null && i.freeRamMb in 1..1_200
        if (lowRam && i.local) why += "málo volné RAM → méně podkladů"
        val slow = i.local && i.writeTps != null && i.writeTps in 0.1..4.5
        if (slow) why += "mozek píše jen ${"%.1f".format(i.writeTps).replace('.', ',')} tok/s → kratší odpověď"

        // 4) rozpočet
        var maxTokens = if (i.local) when (power) { Power.BASIC -> 320; Power.ADVANCED -> 560; Power.SHERLOCK -> 900 }
        else when (power) { Power.BASIC -> 2_000; Power.ADVANCED -> 8_000; Power.SHERLOCK -> 32_000 }
        if (slow) maxTokens = (maxTokens * 0.7).toInt()
        maxTokens = (maxTokens * when { strength <= 25 -> 0.6; strength <= 50 -> 0.8; strength < 100 -> 1.0; else -> 1.3 }).toInt()
        if (i.local && demand < 20) maxTokens = minOf(maxTokens, 220)
        // vlákna podle síly: 25 % = polovina výkonných jader, 100 % = všechna výkonná (úsporná jádra generování brzdí)
        var threads = when { strength <= 25 -> (i.threads / 2).coerceAtLeast(2); strength <= 50 -> (i.threads - 1).coerceAtLeast(2); else -> i.threads }
        // čtení zadání je dobře paralelní → při vyšší síle všechna jádra kromě jednoho
        var batch = when { strength <= 25 -> threads; strength <= 50 -> i.threads; else -> (i.cores - 1).coerceAtLeast(i.threads) }
        if (threads < i.threads && strength < 75) why += "síla výkonu $strength % → $threads vlákna"
        if (i.local) {
            if (hot) threads = (threads - 1).coerceAtLeast(2)
            if (severe || (bat != null && bat <= 15 && !onCharger)) threads = (threads - 2).coerceAtLeast(2)
            if ((hot || severe || (bat != null && bat <= 15 && !onCharger)) && threads < i.threads) why += "${threads} vlákna CPU místo ${i.threads} (šetří teplotu a baterii)"
            if (hot || severe) batch = minOf(batch, i.threads)
        }
        val thinking = power == Power.SHERLOCK && demand >= 70 && !hot && strength >= 50
        val pipeline = power == Power.SHERLOCK && !slow && !lowRam
        val agent = !i.local || power != Power.BASIC
        val knowledge = when { lowRam -> 1; power == Power.BASIC -> 2; power == Power.ADVANCED -> 3; else -> 5 }
        return Budget(power, demand, maxTokens, threads, batch.coerceAtLeast(threads), thinking, pipeline, agent, knowledge, why, power.ordinal < before.ordinal)
    }
}

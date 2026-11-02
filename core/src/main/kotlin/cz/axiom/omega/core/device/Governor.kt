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
    )

    data class Budget(
        val power: Power,
        /** náročnost dotazu 0–100 */
        val demand: Int,
        val maxTokens: Int,
        val threads: Int,
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
        if (i.local && demand < 20) maxTokens = minOf(maxTokens, 220)
        var threads = i.threads
        if (i.local) {
            if (hot) threads = (threads - 1).coerceAtLeast(2)
            if (severe || (bat != null && bat <= 15 && !onCharger)) threads = (threads - 2).coerceAtLeast(2)
            if (threads < i.threads) why += "${threads} vlákna CPU místo ${i.threads} (šetří teplotu a baterii)"
        }
        val thinking = power == Power.SHERLOCK && demand >= 70 && !hot
        val pipeline = power == Power.SHERLOCK && !slow && !lowRam
        val agent = !i.local || power != Power.BASIC
        val knowledge = when { lowRam -> 1; power == Power.BASIC -> 2; power == Power.ADVANCED -> 3; else -> 5 }
        return Budget(power, demand, maxTokens, threads, thinking, pipeline, agent, knowledge, why, power.ordinal < before.ordinal)
    }
}

package cz.axiom.omega.core.device

/**
 * PROFIL ZAŘÍZENÍ – AVIOR ušitý na míru konkrétnímu telefonu.
 *
 * llama.cpp běží nejrychleji na výkonných jádrech; zapojení úsporných jader (big.LITTLE) generování
 * zpomaluje, protože všechna vlákna čekají na nejpomalejší. Profil proto určí počet vláken podle
 * skutečných frekvencí jader, kontext podle RAM a režimu výkonu a doporučený mozek.
 */
enum class Power(val label: String, val description: String) {
    BASIC("Základ", "Rychlé a úsporné odpovědi – 1 průchod, bez řetězců. Šetří baterii."),
    ADVANCED("Pokročilé", "Vyvážené – RANDY volí hloubku, podmodely i řetězce podle úlohy."),
    SHERLOCK("Sherlock", "Maximum: dedukce, 3 průchody, přemýšlení, řetězce s oponenturou, delší agenti. Pomalejší, nejpřesnější."),
    ;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: ADVANCED }
}

data class DeviceProfile(
    val name: String,
    val soc: String?,
    val ramGb: Int,
    /** vlákna pro generování (jen výkonná jádra) */
    val threads: Int,
    val ctx: Map<Power, Int>,
    val recommendedBrain: String,
    /** od jaké úrovně PowerManager.THERMAL_STATUS má RANDY šetřit (Tensor se zahřívá dřív) */
    val thermalCaution: Int,
    val notes: List<String>,
    val known: Boolean,
) {
    fun ctxFor(p: Power) = ctx[p] ?: 8192
}

object DeviceProfiles {

    /**
     * @param maxFreqsKHz maximální frekvence jednotlivých jader (/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq)
     */
    fun detect(manufacturer: String, model: String, soc: String?, ramGb: Int, maxFreqsKHz: List<Long>): DeviceProfile {
        val perf = performanceCores(maxFreqsKHz)
        if (model.trim().equals("Pixel 9a", ignoreCase = true) || (soc?.contains("Tensor G4", true) == true && ramGb <= 8)) {
            return DeviceProfile(
                name = "Google Pixel 9a", soc = soc ?: "Tensor G4", ramGb = ramGb,
                threads = 4,
                // stejný kontext ve všech režimech → přepnutí výkonu nevyžaduje znovunačtení mozku
                ctx = mapOf(Power.BASIC to 8192, Power.ADVANCED to 8192, Power.SHERLOCK to 8192),
                // nejrychlejší mozek s dobrou češtinou: E2B (3,1 GB) – vejde se s rezervou, odpovídá zhruba 2× rychleji než E4B
                recommendedBrain = "omega-lite-gemma4-e2b-q4km",
                thermalCaution = 2,
                notes = listOf(
                    "Tensor G4: 1× Cortex-X4 (3,1 GHz) + 3× A720 (2,6 GHz) + 4× A520 (1,9 GHz) – AVIOR počítá na 4 výkonných jádrech, úsporná nechává systému.",
                    "8 GB RAM: doporučený mozek OMEGA Lite (Gemma 4 E2B, 3,1 GB) – nejrychlejší s dobrou češtinou, v paměti s rezervou. OMEGA Core (E4B, 5 GB) je přesnější, ale pomalejší a na hraně RAM (načítá se úsporně).",
                    "GPU Mali-G715: llama.cpp na Mali běží spolehlivěji a obvykle i rychleji na CPU – AVIOR používá CPU (ARMv9 i8mm/dotprod přes GGML_CPU_ALL_VARIANTS).",
                    "Tensor G4 se při dlouhé zátěži zahřívá: RANDY šetří už od stavu „teplé“, Sherlock se při přehřátí sám zmírní.",
                    "Sémantický modul OMEGA Sense se zapne jen při dostatku volné RAM (> 1,5 GB).",
                ),
                known = true,
            )
        }
        val threads = (if (perf > 0) perf else (maxFreqsKHz.size.takeIf { it > 0 } ?: 6) - 2).coerceIn(2, 6)
        val ctx = when {
            ramGb >= 12 -> mapOf(Power.BASIC to 8192, Power.ADVANCED to 16384, Power.SHERLOCK to 16384)
            ramGb >= 8 -> mapOf(Power.BASIC to 4096, Power.ADVANCED to 8192, Power.SHERLOCK to 8192)
            ramGb >= 6 -> mapOf(Power.BASIC to 4096, Power.ADVANCED to 4096, Power.SHERLOCK to 8192)
            else -> mapOf(Power.BASIC to 2048, Power.ADVANCED to 4096, Power.SHERLOCK to 4096)
        }
        val brain = when {
            ramGb >= 16 -> "omega-max-qwen36-35b-a3b-iq2m"
            ramGb >= 12 -> "omega-ultra-gemma4-26b-a4b-iq2m"
            ramGb >= 8 -> "omega-core-gemma4-e4b-q4km"
            ramGb >= 6 -> "omega-lite-gemma4-e2b-q4km"
            else -> "omega-mini-qwen35-2b-q4km"
        }
        return DeviceProfile(
            "$manufacturer $model".trim(), soc, ramGb, threads, ctx, brain, 3,
            listOf("Obecný profil: $threads výkonných vláken, kontext podle $ramGb GB RAM."), known = false,
        )
    }

    /** Počet výkonných jader: frekvence aspoň 75 % nejrychlejšího jádra (big + mid, bez úsporných). */
    fun performanceCores(maxFreqsKHz: List<Long>): Int {
        val f = maxFreqsKHz.filter { it > 0 }
        if (f.isEmpty()) return 0
        val top = f.max()
        return f.count { it >= top * 0.75 }
    }
}

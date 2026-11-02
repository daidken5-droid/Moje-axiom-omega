package cz.axiom.omega.core.phone

import cz.axiom.omega.core.util.TextUtil

/**
 * OVLÁDÁNÍ TELEFONU – povely v češtině bez mozku (okamžitě, offline).
 *
 * BEZPEČNOST: rozpoznává se jen to, co uživatel sám napsal/řekl (nikdy text z webu, souborů ani výstup
 * modelu) → prompt injection nemůže spustit akci. Root a nevratné akce mají [Command.confirm] = true
 * a provedou se až po potvrzení v dialogu.
 */
object PhoneCommands {

    enum class Action {
        TORCH, VOLUME, MEDIA, ALARM, TIMER, SETTINGS, WIFI, BLUETOOTH, AIRPLANE, DND, BRIGHTNESS, LAUNCH, DIAL, STATUS,
        ROOT_KILL_BACKGROUND, ROOT_FORCE_STOP, ROOT_DROP_CACHES, ROOT_REBOOT, ROOT_SHELL, ROOT_STATUS,
    }

    data class Command(
        val action: Action,
        val args: Map<String, String> = emptyMap(),
        /** co se stane – zobrazí se uživateli (a v potvrzení) */
        val label: String,
        val confirm: Boolean = false,
        val root: Boolean = false,
    )

    private fun on(f: String) = !Regex("\\b(vypni|zhasni|vypnout|zhasnout|deaktivuj|off)\\b").containsMatchIn(f)

    val SETTINGS = linkedMapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "displej" to "Displej", "jas" to "Displej", "baterie" to "Baterie",
        "zvuk" to "Zvuk", "aplikace" to "Aplikace", "uloziste" to "Úložiště", "poloha" to "Poloha", "gps" to "Poloha",
        "oznameni" to "Oznámení", "notifikace" to "Oznámení", "vyvojar" to "Vývojářské možnosti", "nerusit" to "Nerušit",
        "mobilni data" to "Mobilní síť", "sit" to "Síť a internet", "nfc" to "NFC", "hotspot" to "Hotspot",
        "zabezpeceni" to "Zabezpečení", "soukromi" to "Soukromí", "jazyk" to "Jazyk", "datum" to "Datum a čas",
        "pristupnost" to "Přístupnost", "aktualizace" to "Aktualizace systému", "telefon" to "O telefonu", "ucty" to "Účty",
    )

    private fun num(f: String) = Regex("(\\d{1,3})\\s*(%|procent)").find(f)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)

    fun parse(text: String): Command? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > 160 || raw.contains('\n')) return null
        val f = TextUtil.fold(raw).replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ' ')

        // OTÁZKA ≠ POVEL: „Jak zapnout svítilnu na iPhonu?“ je dotaz pro mozek, ne akce
        val question = raw.endsWith("?") || Regex("^(jak|proc|co|kdy|kde|muzes mi vysvetlit|vysvetli|rekni mi)\\b").containsMatchIn(f)
        if (question) {
            if (Regex("^(je|mam|ma) (telefon |muj telefon )?(root|rootnuty|rootovany)").containsMatchIn(f))
                return Command(Action.ROOT_STATUS, label = "Zjistit, zda má telefon root (su)", root = true)
            return null
        }

        // ---------------- root (vždy s potvrzením)
        Regex("^root\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            val cmd = m.groupValues[1].trim()
            return Command(Action.ROOT_SHELL, mapOf("cmd" to cmd), "Spustit jako root: $cmd", confirm = true, root = true)
        }
        if (Regex("^(je|mam|ma) (telefon |muj telefon )?(root|rootnuty|rootovany)|^stav root").containsMatchIn(f))
            return Command(Action.ROOT_STATUS, label = "Zjistit, zda má telefon root (su)", root = true)
        if (Regex("(zavri|ukonci|zabij|vypni) (vsechny )?(aplikace|procesy) na pozadi").containsMatchIn(f) && "root" in f)
            return Command(Action.ROOT_KILL_BACKGROUND, label = "Root: ukončit všechny aplikace na pozadí (am kill-all)", confirm = true, root = true)
        Regex("^(?:root:? )?(?:vynut(?:ne)? ukonci|force ?stop|zabij aplikaci|ukonci aplikaci) (.+)$").find(f)?.let { m ->
            return Command(Action.ROOT_FORCE_STOP, mapOf("app" to m.groupValues[1].trim()), "Root: násilně ukončit aplikaci „${m.groupValues[1].trim()}“", confirm = true, root = true)
        }
        if (Regex("(vycisti|vymaz|uvolni) (systemovou )?(cache|mezipamet)").containsMatchIn(f) && "root" in f)
            return Command(Action.ROOT_DROP_CACHES, label = "Root: uvolnit systémovou mezipaměť (drop_caches)", confirm = true, root = true)
        if (Regex("^(restartuj|rebootuj|reboot|restart) (telefon|mobil|zarizeni)").containsMatchIn(f))
            return Command(Action.ROOT_REBOOT, label = "Restartovat telefon (root)", confirm = true, root = true)

        // ---------------- svítilna
        if (Regex("\\b(svitiln\\w*|baterk\\w*|blesk|flashlight)\\b").containsMatchIn(f) && Regex("^(zapn|rozsvit|vypn|zhasn|svitiln|baterk)").containsMatchIn(f))
            return Command(Action.TORCH, mapOf("on" to "${on(f)}"), if (on(f)) "Zapnout svítilnu" else "Vypnout svítilnu")

        // ---------------- hlasitost
        if (Regex("^(ztlum|ztis|zesil|zvys|sniz|uber|pridej|nastav hlasitost|hlasitost|vypni zvuk|zapni zvuk|potichu|nahlas|mute|odtlum|dej hlasitost)").containsMatchIn(f)) {
            val n = num(f)
            val mode = when {
                n != null -> "set"
                Regex("ztlum|ztis|mute|vypni zvuk").containsMatchIn(f) -> "mute"
                Regex("zesil|nahlas|pridej|vic|zvys").containsMatchIn(f) -> "up"
                Regex("potichu|snizi|ubere|min|tiss").containsMatchIn(f) -> "down"
                Regex("zapni zvuk|odtlum").containsMatchIn(f) -> "unmute"
                else -> null
            }
            if (mode != null) return Command(Action.VOLUME, buildMap { put("mode", mode); n?.let { put("pct", "$it") } },
                when (mode) { "set" -> "Hlasitost médií na $n %"; "mute" -> "Ztlumit zvuk"; "up" -> "Zesílit"; "down" -> "Zeslabit"; else -> "Zapnout zvuk" })
        }

        // ---------------- přehrávání
        when {
            Regex("^(pauza|pozastav|zastav hudbu|stop hudba|pause)").containsMatchIn(f) -> return Command(Action.MEDIA, mapOf("key" to "pause"), "Pozastavit přehrávání")
            Regex("^(pust|prehraj|pokracuj v prehravani|play)( hudbu)?$").containsMatchIn(f) -> return Command(Action.MEDIA, mapOf("key" to "play"), "Přehrát")
            Regex("^(dalsi|preskoc|next)( pisnicka| skladba)?$").containsMatchIn(f) -> return Command(Action.MEDIA, mapOf("key" to "next"), "Další skladba")
            Regex("^(predchozi|zpet|previous)( pisnicka| skladba)?$").containsMatchIn(f) -> return Command(Action.MEDIA, mapOf("key" to "prev"), "Předchozí skladba")
        }

        // ---------------- budík a časovač
        Regex("(budik|vzbud me|probud me|alarm)\\D{0,12}(\\d{1,2})[:. ]?(\\d{2})?").find(f)?.let { m ->
            if (!Regex("^(nastav|budik|vzbud|probud|alarm|dej)").containsMatchIn(f)) return@let
            val h = m.groupValues[2].toInt(); val min = m.groupValues[3].ifBlank { "0" }.toInt()
            if (h in 0..23 && min in 0..59) return Command(Action.ALARM, mapOf("h" to "$h", "m" to "$min"), "Budík na %d:%02d".format(h, min))
        }
        Regex("(minutk\\w*|casovac|odpocet|stopk)\\D{0,15}(\\d{1,3})\\s*(min|minut|s|sekund|h|hodin)").find(f)?.let { m ->
            val n = m.groupValues[2].toInt()
            val sec = when { m.groupValues[3].startsWith("s") -> n; m.groupValues[3].startsWith("h") -> n * 3600; else -> n * 60 }
            if (sec in 1..86_400) return Command(Action.TIMER, mapOf("sec" to "$sec"), "Minutka na ${m.groupValues[2]} ${m.groupValues[3]}")
        }

        // ---------------- přepínače, které Android aplikacím nedovolí přímo (bez rootu → systémový panel)
        val toggle = Regex("^(zapni|vypni|zapnout|vypnout|aktivuj|deaktivuj)\\s+(.+)$").find(f)
        if (toggle != null) {
            val what = toggle.groupValues[2]
            val enable = on(f)
            when {
                what.startsWith("wifi") || what.startsWith("wi-fi") -> return Command(Action.WIFI, mapOf("on" to "$enable"), (if (enable) "Zapnout" else "Vypnout") + " Wi-Fi")
                what.startsWith("bluetooth") || what == "bt" -> return Command(Action.BLUETOOTH, mapOf("on" to "$enable"), (if (enable) "Zapnout" else "Vypnout") + " Bluetooth")
                what.startsWith("letadl") || what.startsWith("rezim letadl") -> return Command(Action.AIRPLANE, mapOf("on" to "$enable"), (if (enable) "Zapnout" else "Vypnout") + " režim letadlo")
                what.startsWith("nerusit") || what.startsWith("rezim nerusit") -> return Command(Action.DND, mapOf("on" to "$enable"), (if (enable) "Zapnout" else "Vypnout") + " Nerušit")
            }
        }

        // ---------------- jas
        if (Regex("^(jas|nastav jas|dej jas|zvys jas|sniz jas|ztlum jas)\\b").containsMatchIn(f)) {
            num(f)?.let { return Command(Action.BRIGHTNESS, mapOf("pct" to "$it"), "Jas obrazovky na $it %") }
            if (Regex("max|nejvic|naplno").containsMatchIn(f)) return Command(Action.BRIGHTNESS, mapOf("pct" to "100"), "Jas na maximum")
            if (Regex("min|nejmin|ztlum").containsMatchIn(f)) return Command(Action.BRIGHTNESS, mapOf("pct" to "5"), "Jas na minimum")
        }

        // ---------------- nastavení telefonu
        Regex("^(?:otevri|ukaz|jdi do|prejdi do)?\\s*(?:nastaveni|nastavenich)\\s*(.*)$").find(f)?.let { m ->
            if (!f.startsWith("otevri") && !f.startsWith("ukaz") && !f.startsWith("nastaveni") && !f.startsWith("jdi") && !f.startsWith("prejdi")) return@let
            val rest = m.groupValues[1].trim()
            val key = SETTINGS.keys.firstOrNull { rest.contains(it) } ?: ""
            return Command(Action.SETTINGS, mapOf("section" to key), "Otevřít nastavení" + (SETTINGS[key]?.let { ": $it" } ?: ""))
        }

        // ---------------- spuštění aplikace, vytáčení
        Regex("^(?:otevri|spust|zapni aplikaci|pust aplikaci|otevri aplikaci|spust aplikaci)\\s+(.{2,40})$").find(f)?.let { m ->
            val app = m.groupValues[1].trim()
            if (SETTINGS.keys.any { app.startsWith(it) } || app.startsWith("nastaveni")) return@let
            return Command(Action.LAUNCH, mapOf("app" to app), "Spustit aplikaci „$app“")
        }
        Regex("^(?:zavolej|vytoc|volej)\\s+(\\+?[\\d ]{6,16})$").find(f)?.let { m ->
            return Command(Action.DIAL, mapOf("number" to m.groupValues[1].replace(" ", "")), "Vytočit ${m.groupValues[1].trim()} (hovor potvrdíš v telefonu)")
        }

        // ---------------- stav
        if (Regex("^(stav|stav telefonu|jak je na tom telefon|diagnostika|info o telefonu|telefon stav)$").containsMatchIn(f))
            return Command(Action.STATUS, label = "Stav telefonu")
        return null
    }
}

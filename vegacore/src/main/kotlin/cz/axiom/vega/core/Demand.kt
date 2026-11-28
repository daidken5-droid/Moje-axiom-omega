package cz.axiom.vega.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * PŘEDŽALOBNÍ VÝZVA (dokument jako jeden z výstupů společného systému): z faktury a dokladu o částečné úhradě
 * spočítá dlužnou jistinu a úrok z prodlení, u každé částky uvede původ (doklad) a právní oporu k rozhodnému datu.
 * Výstup: text → DOCX (upravitelný) a PDF (v aplikaci, se zpětnou kontrolou obsahu).
 *
 * Sazba: NV č. 351/2013 Sb. – repo sazba ČNB platná POSLEDNÍ DEN pololetí předcházejícího pololetí, v němž prodlení
 * začalo, + 8 procentních bodů. Tabulka repo sazeb je ověřená jen do [REPO_VERIFIED_TO]; pro pozdější prodlení
 * musí uživatel sazbu potvrdit (výstup to výslovně uvede).
 */
object Demand {
    data class Doc(val name: String, val date: LocalDate)
    data class Invoice(val number: String, val issued: LocalDate, val due: LocalDate, val amount: Double, val subject: String, val doc: Doc)
    data class Payment(val date: LocalDate, val amount: Double, val doc: Doc)
    data class Party(val name: String, val address: String, val id: String = "")
    data class Input(val creditor: Party, val debtor: Party, val invoice: Invoice, val payments: List<Payment>, val asOf: LocalDate,
                     val b2b: Boolean = true, val deadlineDays: Int = 7, val repoOverride: Double? = null, val bankAccount: String = "")

    data class Line(val label: String, val amount: Double, val origin: String)
    data class Result(
        val principalLeft: Double, val interest: Double, val costs: Double, val ratePct: Double, val rateSource: String, val rateVerified: Boolean,
        val lines: List<Line>, val legal: List<String>, val warnings: List<String>, val text: String,
    )

    /** Repo sazba ČNB k poslednímu dni pololetí (zdroj: ČNB, historie repo sazby). */
    val REPO_HALF_YEAR_END: Map<LocalDate, Double> = linkedMapOf(
        LocalDate.of(2022, 12, 31) to 7.00,
        LocalDate.of(2023, 6, 30) to 7.00,
        LocalDate.of(2023, 12, 31) to 6.75,
        LocalDate.of(2024, 6, 30) to 4.75,
        LocalDate.of(2024, 12, 31) to 4.00,
        LocalDate.of(2025, 6, 30) to 3.50,
    )
    val REPO_VERIFIED_TO: LocalDate = LocalDate.of(2025, 6, 30)

    /** Rozhodný den pro sazbu: poslední den pololetí PŘED pololetím, v němž prodlení začalo. */
    fun rateDate(delayStart: LocalDate): LocalDate =
        if (delayStart.monthValue <= 6) LocalDate.of(delayStart.year - 1, 12, 31) else LocalDate.of(delayStart.year, 6, 30)

    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun d(x: LocalDate) = "${x.dayOfMonth}. ${x.monthValue}. ${x.year}"
    private fun kc(v: Double) = CzNum.format(v) + " Kč"

    fun compute(i: Input): Result {
        val warnings = ArrayList<String>()
        val delayStart = i.invoice.due.plusDays(1)
        val rd = rateDate(delayStart)
        val repo = i.repoOverride ?: REPO_HALF_YEAR_END[rd]
        val verified = i.repoOverride == null && repo != null && !rd.isAfter(REPO_VERIFIED_TO)
        if (repo == null) warnings += "Repo sazbu ČNB k ${d(rd)} nemám ověřenou – doplň ji (cnb.cz → Historie repo sazby) a výpočet zopakuj."
        if (i.repoOverride != null) warnings += "Repo sazba ${i.repoOverride} % zadána ručně – ověř ji na cnb.cz."
        val rate = (repo ?: 0.0) + 8.0
        if (i.payments.any { it.date.isBefore(i.invoice.issued) }) warnings += "Platba je datovaná před vystavením faktury – zkontroluj doklady."
        if (!i.asOf.isAfter(i.invoice.due)) warnings += "K rozhodnému datu ${d(i.asOf)} ještě neuplynula splatnost – dlužník není v prodlení."

        // průběh: úrok z jistiny po dnech; platba se započte nejdřív na úrok, pak na jistinu (§ 1932 OZ)
        var principal = i.invoice.amount
        var accrued = 0.0
        var from = delayStart
        val lines = ArrayList<Line>()
        lines += Line("Jistina dle faktury č. ${i.invoice.number}", i.invoice.amount, "${i.invoice.doc.name} ze dne ${d(i.invoice.doc.date)}, splatnost ${d(i.invoice.due)}")
        for (p in i.payments.sortedBy { it.date }) {
            if (p.date.isAfter(i.invoice.due)) {
                val days = ChronoUnit.DAYS.between(from, p.date) + 1
                if (days > 0) { val int = principal * rate / 100 * days / 365; accrued += int; lines += Line("Úrok z prodlení ${d(from)}–${d(p.date)} ($days dní, ${String.format(java.util.Locale.forLanguageTag("cs-CZ"), "%.2f", rate)} % p. a. z ${kc(principal)})", r2(int), "výpočet: jistina × sazba × dny / 365") }
                from = p.date.plusDays(1)
            }
            val toInterest = minOf(accrued, p.amount)
            accrued -= toInterest
            val toPrincipal = p.amount - toInterest
            principal -= toPrincipal
            lines += Line("Částečná úhrada ${d(p.date)}" + if (toInterest > 0) " (na úrok ${kc(r2(toInterest))}, na jistinu ${kc(r2(toPrincipal))})" else "", -p.amount, "${p.doc.name} ze dne ${d(p.doc.date)}")
        }
        if (principal < -0.005) warnings += "Úhrady převyšují fakturu – přeplatek ${kc(-principal)}."
        val daysTail = ChronoUnit.DAYS.between(from, i.asOf) + 1
        if (daysTail > 0 && principal > 0) { val int = principal * rate / 100 * daysTail / 365; accrued += int; lines += Line("Úrok z prodlení ${d(from)}–${d(i.asOf)} ($daysTail dní z ${kc(r2(principal))})", r2(int), "výpočet: jistina × sazba × dny / 365") }
        val costs = if (i.b2b) 1200.0 else 0.0
        if (i.b2b) lines += Line("Náklady spojené s uplatněním pohledávky (paušál)", costs, "§ 3 NV č. 351/2013 Sb. (jen mezi podnikateli)")

        val legal = buildList {
            add("§ 1968 zákona č. 89/2012 Sb., občanský zákoník – dlužník, který neplní řádně a včas, je v prodlení.")
            add("§ 1970 občanského zákoníku – po dlužníkovi v prodlení lze požadovat úrok z prodlení; výši stanoví vláda nařízením.")
            add("§ 2 nařízení vlády č. 351/2013 Sb. – roční úrok z prodlení = repo sazba ČNB k poslednímu dni pololetí předcházejícího pololetí, v němž prodlení nastalo, + 8 p. b. (${d(rd)}: ${repo?.let { String.format(java.util.Locale.forLanguageTag("cs-CZ"), "%.2f", it) } ?: "?"} % → ${String.format(java.util.Locale.forLanguageTag("cs-CZ"), "%.2f", rate)} %).")
            add("§ 1932 občanského zákoníku – plnění se započte nejprve na náklady, poté na úroky z prodlení a nakonec na jistinu, neurčí-li dlužník jinak.")
            if (i.b2b) add("§ 3 nařízení vlády č. 351/2013 Sb. – minimální výše nákladů spojených s uplatněním pohledávky 1 200 Kč (vztah mezi podnikateli).")
            add("§ 142a zákona č. 99/1963 Sb., občanský soudní řád – výzva k plnění zaslaná nejméně 7 dní před podáním žaloby je podmínkou náhrady nákladů řízení.")
        }
        if (!verified) warnings += "Právní opora sazby NENÍ ověřena k rozhodnému datu – nepoužívej dokument bez kontroly."
        val p2 = r2(principal); val int2 = r2(accrued)
        val text = letter(i, p2, int2, costs, rate, rd)
        return Result(p2, int2, costs, rate, "repo ČNB k ${d(rd)} + 8 p. b.", verified, lines, legal, warnings, text)
    }

    private fun letter(i: Input, principal: Double, interest: Double, costs: Double, rate: Double, rd: LocalDate): String = buildString {
        appendLine(i.creditor.name); appendLine(i.creditor.address); if (i.creditor.id.isNotBlank()) appendLine("IČO: ${i.creditor.id}")
        appendLine()
        appendLine(i.debtor.name); appendLine(i.debtor.address); if (i.debtor.id.isNotBlank()) appendLine("IČO: ${i.debtor.id}")
        appendLine()
        appendLine("${i.creditor.address.substringAfterLast(' ').ifBlank { "…" }}, ${d(i.asOf)}")
        appendLine()
        appendLine("# PŘEDŽALOBNÍ VÝZVA K ÚHRADĚ")
        appendLine()
        appendLine("Vážení,")
        appendLine()
        appendLine("dne ${d(i.invoice.issued)} jsme Vám vystavili fakturu č. ${i.invoice.number} na částku ${kc(i.invoice.amount)} za ${i.invoice.subject}, splatnou dne ${d(i.invoice.due)}. " +
            if (i.payments.isNotEmpty()) "Dosud jste uhradili pouze ${i.payments.joinToString(" a ") { "${kc(it.amount)} dne ${d(it.date)}" }}. " else "Fakturu jste dosud neuhradili. ")
        appendLine()
        appendLine("Ke dni ${d(i.asOf)} evidujeme tyto dlužné částky:")
        appendLine()
        appendLine("- nezaplacená jistina: ${kc(principal)}")
        appendLine("- úrok z prodlení ve výši ${String.format(java.util.Locale.forLanguageTag("cs-CZ"), "%.2f", rate)} % ročně do ${d(i.asOf)}: ${kc(interest)}")
        if (costs > 0) appendLine("- náklady spojené s uplatněním pohledávky: ${kc(costs)}")
        appendLine("- **celkem: ${kc(principal + interest + costs)}**")
        appendLine()
        appendLine("Úrok z prodlení ve výši ${String.format(java.util.Locale.forLanguageTag("cs-CZ"), "%.2f", rate)} % ročně z částky ${kc(principal)} běží i nadále až do zaplacení (§ 1970 občanského zákoníku, nařízení vlády č. 351/2013 Sb.; repo sazba ČNB k ${d(rd)} zvýšená o 8 procentních bodů).")
        appendLine()
        appendLine("Vyzýváme Vás k úhradě celkové částky do ${i.deadlineDays} dnů od doručení této výzvy" + if (i.bankAccount.isNotBlank()) " na účet č. ${i.bankAccount}, variabilní symbol ${i.invoice.number.filter(Char::isDigit)}." else ".")
        appendLine()
        appendLine("Tato výzva je předžalobní výzvou ve smyslu § 142a občanského soudního řádu. Neuhradíte-li dluh ve stanovené lhůtě, budeme nuceni uplatnit pohledávku u soudu, což pro Vás bude spojeno s dalšími náklady (soudní poplatek, náklady právního zastoupení).")
        appendLine()
        appendLine("S pozdravem")
        appendLine()
        appendLine(i.creditor.name)
    }

    /** Syntetický český případ (testy + ukázka v aplikaci). */
    fun sample(asOf: LocalDate = LocalDate.of(2025, 6, 15)) = Input(
        creditor = Party("Ukázka Dodavatel s.r.o.", "Masarykova 12, 602 00 Brno", "12345678"),
        debtor = Party("Vzorový Odběratel a.s.", "Nádražní 5, 110 00 Praha", "87654321"),
        invoice = Invoice("FV2025014", LocalDate.of(2025, 2, 28), LocalDate.of(2025, 3, 15), 48_400.0, "dodávku a montáž regálového systému", Doc("Faktura FV2025014", LocalDate.of(2025, 2, 28))),
        payments = listOf(Payment(LocalDate.of(2025, 4, 10), 20_000.0, Doc("Výpis z účtu – příchozí platba VS 2025014", LocalDate.of(2025, 4, 10)))),
        asOf = asOf, bankAccount = "123456789/0800",
    )
}

/**
 * DOCX bez knihoven (Office Open XML): odstavce, nadpis („# “), tučné (**…**), odrážky („- “). Otevře Word,
 * LibreOffice i Google Dokumenty; dá se dál upravovat.
 */
object Docx {
    // znaky zakázané v XML 1.0 (řídicí, U+FFFE/FFFF, osamocené náhradní) pryč – jinak Word označí výzvu za poškozený soubor (noc 28. 11.)
    private val XML_BAD = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]")
    private fun esc(s: String) = s.replace(XML_BAD, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun runs(text: String): String {
        val parts = text.split("**")
        return parts.mapIndexed { k, p -> if (p.isEmpty()) "" else "<w:r>${if (k % 2 == 1) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(p)}</w:t></w:r>" }.joinToString("")
    }

    fun build(text: String, title: String = "Dokument"): ByteArray {
        val body = text.lines().joinToString("") { l ->
            when {
                l.startsWith("# ") -> "<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:before=\"240\" w:after=\"240\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr><w:t>${esc(l.removePrefix("# "))}</w:t></w:r></w:p>"
                l.startsWith("- ") -> "<w:p><w:pPr><w:ind w:left=\"360\"/></w:pPr><w:r><w:t xml:space=\"preserve\">• </w:t></w:r>${runs(l.removePrefix("- "))}</w:p>"
                l.isBlank() -> "<w:p/>"
                else -> "<w:p><w:pPr><w:jc w:val=\"both\"/></w:pPr>${runs(l)}</w:p>"
            }
        }
        val doc = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="cs-CZ"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>"""
        val ct = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title><dc:creator>VEGA</dc:creator></cp:coreProperties>"""
        val o = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(o).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", ct); put("_rels/.rels", rels); put("word/document.xml", doc); put("word/_rels/document.xml.rels", docRels); put("word/styles.xml", styles); put("docProps/core.xml", core)
        }
        return o.toByteArray()
    }

    /** Zpětná kontrola: text z DOCX (pro ověření, že v dokumentu jsou spočítané částky). */
    fun text(bytes: ByteArray): String {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "word/document.xml") return Regex("<w:t[^>]*>([^<]*)</w:t>").findAll(String(z.readBytes(), Charsets.UTF_8)).joinToString("") { it.groupValues[1] }.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">") }
        }
        return ""
    }
}

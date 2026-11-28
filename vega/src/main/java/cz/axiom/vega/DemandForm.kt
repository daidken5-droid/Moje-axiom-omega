package cz.axiom.vega

import cz.axiom.vega.core.Demand
import cz.axiom.vega.core.ToolError
import org.json.JSONObject

/**
 * Formulář předžalobní výzvy ⇄ vstup výpočtu (refaktoring 10, noc 27./28. 11. – vyjmuto z Ops.kt; doporučení T1).
 * Vadné datum = srozumitelná chyba s názvem pole. Strážný test celé cesty: OpsDemandGuardTest.
 */
object DemandForm {
    fun toJson(i: Demand.Input): JSONObject = JSONObject().put("creditor", i.creditor.name).put("creditorAddr", i.creditor.address).put("creditorId", i.creditor.id)
        .put("debtor", i.debtor.name).put("debtorAddr", i.debtor.address).put("debtorId", i.debtor.id).put("invoice", i.invoice.number).put("issued", i.invoice.issued.toString())
        .put("due", i.invoice.due.toString()).put("amount", i.invoice.amount).put("subject", i.invoice.subject).put("payDate", i.payments.firstOrNull()?.date?.toString() ?: "")
        .put("payAmount", i.payments.firstOrNull()?.amount ?: 0.0).put("payDoc", i.payments.firstOrNull()?.doc?.name ?: "").put("asOf", i.asOf.toString()).put("account", i.bankAccount).put("b2b", i.b2b)

    /** Povinná textová pole formuláře a jejich názvy pro laika (dřív chybějící pole = „Něco se pokazilo uvnitř VEGA (JSONException)“). */
    private val LABELS = mapOf("creditor" to "věřitel", "debtor" to "dlužník", "invoice" to "číslo faktury")

    fun input(o: JSONObject): Demand.Input {
        fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() } ?: throw ToolError("Vyplň pole „${LABELS[k] ?: k}“ – bez něj výzvu nesestavím.")
        fun num(k: String, label: String) = o.optString(k).trim().replace(" ", "").replace('\u00a0', ' ').replace(" ", "").replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
            ?: throw ToolError("Pole „$label“ musí být kladné číslo (např. 12500 nebo 12 500,50).")
        fun d(k: String) = runCatching { java.time.LocalDate.parse(o.getString(k)) }.getOrElse { throw ToolError("Datum „$k“ chybí nebo nemá tvar RRRR-MM-DD.") }
        val number = s("invoice")
        // adresy a předmět jsou nepovinné (formulář je posílá prázdné; před 27. 11. byly také nepovinné)
        val inv = Demand.Invoice(number, d("issued"), d("due"), num("amount", "částka"), o.optString("subject"), Demand.Doc("Faktura $number", d("issued")))
        val pays = if (o.optString("payDate").isNotBlank() && o.optDouble("payAmount", 0.0) > 0) listOf(Demand.Payment(d("payDate"), num("payAmount", "zaplacená částka"), Demand.Doc(o.optString("payDoc").ifBlank { "Doklad o úhradě" }, d("payDate")))) else emptyList()
        return Demand.Input(Demand.Party(s("creditor"), o.optString("creditorAddr"), o.optString("creditorId")), Demand.Party(s("debtor"), o.optString("debtorAddr"), o.optString("debtorId")),
            inv, pays, d("asOf"), o.optBoolean("b2b", true), repoOverride = o.optDouble("repo", Double.NaN).takeIf { !it.isNaN() }, bankAccount = o.optString("account"))
    }
}

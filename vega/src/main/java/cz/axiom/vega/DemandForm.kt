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

    fun input(o: JSONObject): Demand.Input {
        fun d(k: String) = runCatching { java.time.LocalDate.parse(o.getString(k)) }.getOrElse { throw ToolError("Datum „$k“ chybí nebo nemá tvar RRRR-MM-DD.") }
        val inv = Demand.Invoice(o.getString("invoice"), d("issued"), d("due"), o.getDouble("amount"), o.getString("subject"), Demand.Doc("Faktura ${o.getString("invoice")}", d("issued")))
        val pays = if (o.optString("payDate").isNotBlank() && o.optDouble("payAmount", 0.0) > 0) listOf(Demand.Payment(d("payDate"), o.getDouble("payAmount"), Demand.Doc(o.optString("payDoc").ifBlank { "Doklad o úhradě" }, d("payDate")))) else emptyList()
        return Demand.Input(Demand.Party(o.getString("creditor"), o.getString("creditorAddr"), o.optString("creditorId")), Demand.Party(o.getString("debtor"), o.getString("debtorAddr"), o.optString("debtorId")),
            inv, pays, d("asOf"), o.optBoolean("b2b", true), repoOverride = o.optDouble("repo", Double.NaN).takeIf { !it.isNaN() }, bankAccount = o.optString("account"))
    }
}

package cz.axiom.vega.core

/**
 * Hlášky režimu Bez sítě (refaktoring 11, 28. 11.): návod „jak ho vypnout“ byl osmkrát ručně opsaný ve třech souborech
 * (jedna kopie s jiným, nejasným zněním). Jedno místo – když se ovládání přesune, opraví se jedna věta.
 */
object AirgapText {
    const val HOW_OFF = "vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě"

    /** „Je zapnutý režim Bez sítě – [what] (návod).[ tail]“ */
    fun msg(what: String? = null, tail: String = ""): String =
        "Je zapnutý režim Bez sítě" + (what?.let { " – $it" } ?: "") + " ($HOW_OFF)." + (if (tail.isNotEmpty()) " $tail" else "")
}

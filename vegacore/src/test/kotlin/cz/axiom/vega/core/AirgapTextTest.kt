package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Strážný test refaktoringu 11: přesná znění hlášek před sjednocením (literály z původního kódu) se nesmí změnit. */
class AirgapTextTest {
    @Test fun sameWordingAsBefore() {
        assertEquals("Je zapnutý režim Bez sítě – na GitHub ani k modelu nesahám (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě).", AirgapText.msg("na GitHub ani k modelu nesahám"))
        assertEquals("Je zapnutý režim Bez sítě – na GitHub nesahám (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě).", AirgapText.msg("na GitHub nesahám"))
        assertEquals("Je zapnutý režim Bez sítě (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě).", AirgapText.msg())
        assertEquals("Je zapnutý režim Bez sítě – model v cloudu nevolám (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě). Výpočty a nástroje fungují dál.", AirgapText.msg("model v cloudu nevolám", "Výpočty a nástroje fungují dál."))
        assertEquals("Je zapnutý režim Bez sítě – na tohle potřebuji model v cloudu (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě). Ruční nástroje (Nástroje) fungují bez sítě.", AirgapText.msg("na tohle potřebuji model v cloudu", "Ruční nástroje (Nástroje) fungují bez sítě."))
        assertEquals("Je zapnutý režim Bez sítě – rešerši na webu nedělám (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě). Lokálně můžu hledat v tvých souborech: „najdi v mých souborech most“.", AirgapText.msg("rešerši na webu nedělám", "Lokálně můžu hledat v tvých souborech: „najdi v mých souborech most“."))
        assertEquals("Je zapnutý režim Bez sítě – balíček nestahuji (vypneš ho nahoře: klepni na „VEGA ▾“ → Bez sítě). Nainstaluj ho ze souboru.", AirgapText.msg("balíček nestahuji", "Nainstaluj ho ze souboru."))
    }
}

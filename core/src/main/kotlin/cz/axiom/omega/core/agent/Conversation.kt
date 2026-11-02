package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx

/**
 * KONVERZACE – co se děje MEZI otázkami ve stejném vlákně (podle reportů z reálného používání):
 *  • nová otázka na jiné téma → malý mozek v telefonu nesmí dostat starou výměnu (zkopíroval by ji),
 *  • ozvěna: mozek vrátí skoro stejnou odpověď jako minule na jinou otázku → zeptat se znovu bez historie,
 *  • oprava od uživatele („Špatně, hraje to Petr Rychlý“) → zapamatovat a špatnou odpověď zapomenout,
 *  • úklid artefaktů modelů (citační značky gpt-oss 【4】, zbytky značek sebereflexe).
 */
object Conversation {

    /** Slova, která odkazují na předchozí výměnu („a kdy to bylo“, „proč“, „podrobněji“) → otázka NENÍ samostatná. */
    private val anaphora = rx("^(a |i |tak |proc|jak to|co to|kdy to|kde to|kdo to|to |ten |ta |tam|tomu|toho|tim|on |ona |oni |jeho|jeji|jejich|" +
        "dal|pokracuj|vic|podrobn|rozved|vysvetli to|presneji|kratsi|delsi|jinak|znovu|jeste|a co|a kdy|a kde|a kdo|a kolik|a proc|" +
        "porovnej|srovnej|shrn|preloz|oprav|to je|je to|myslis)|\\b(to|tomu|toho|tim|jeho|jeji|tam|tenhle|tahle|tohle|predchozi|vyse|minul)\\b")

    private fun keys(s: String) = TextUtil.words(s, 4).map { it.take(5) }.toSet()

    /** Samostatná otázka na nové téma (bez odkazu na předchozí a bez společných klíčových slov). */
    fun standalone(question: String, previousQuestion: String?): Boolean {
        if (previousQuestion.isNullOrBlank()) return true
        val f = TextUtil.fold(question).lowercase().trim()
        if (anaphora.containsMatchIn(f) || isCorrection(question)) return false
        if (f.split(' ').size <= 2) return false // „a Brno?“, „proč?“ – krátké dotazy navazují
        return (keys(question) intersect keys(previousQuestion)).isEmpty()
    }

    /** Odpověď je skoro kopie předchozí odpovědi, i když se otázka změnila (typická chyba malých modelů). */
    fun isEcho(answer: String, previousAnswer: String?, question: String, previousQuestion: String?): Boolean {
        if (previousAnswer.isNullOrBlank() || answer.length < 15) return false
        if (!standalone(question, previousQuestion)) return false
        val a = keys(answer); val b = keys(previousAnswer)
        if (a.isEmpty() || b.isEmpty()) return false
        return (a intersect b).size.toDouble() / (a union b).size >= 0.6
    }

    private val correction = rx("^(spatne|spatna odpoved|to je spatne|to neni pravda|neni to pravda|nespravne|chyba|omyl|mylis se|pletes se|kdepak|" +
        "ne,? (to|hraje|byl|je|jmenuje|bylo)|ne ne|naopak|oprava|ve skutecnosti)\\b")

    /** Uživatel opravuje předchozí odpověď. */
    fun isCorrection(text: String): Boolean = correction.containsMatchIn(TextUtil.fold(text).lowercase().trim())

    /** Text vzpomínky z opravy – aby příště stejná otázka dostala správný fakt (i mozek ho uvidí v paměti). */
    fun correctionMemory(previousQuestion: String, correctionText: String): String =
        "Uživatel opravil odpověď na otázku „${TextUtil.clip(previousQuestion.trim(), 160)}“: ${TextUtil.clip(correctionText.trim(), 240)}"

    /** Úklid artefaktů modelů: citační značky gpt-oss (【4】, 【zdroj…】, [3†L10]) a zbytky značek sebereflexe. */
    fun clean(answer: String): String = answer
        .replace(Regex("【[^】]{0,120}】"), "")
        .replace(Regex("\\[\\d+†[^\\]]{0,60}]"), "")
        .replace(Regex("⟨/?SEBEREFLEXE⟩|</?SEBEREFLEXE>"), "")
        .replace(Regex("[ \\t]+([.,;:!?])"), "$1")
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()
}

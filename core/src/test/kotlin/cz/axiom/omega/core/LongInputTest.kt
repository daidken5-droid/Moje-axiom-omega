package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.llm.*
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.SelfState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** REBIRTH test B: dlouhé zadání (5k/20k/50k znaků, 15+ podotázek) – nic se neztratí. */
class LongInputTest {
    private val questions = (1..18).map { "$it. Jak souvisí téma číslo $it s ostatními a co z toho plyne pro výzkum $it?" }
    private fun longText(chars: Int): String {
        val filler = "Toto je kontext výzkumného zadání, který popisuje historii, souvislosti a zdroje. ".repeat(4)
        val sb = StringBuilder("Výzkumné zadání (česky a English mixed): Please analyze carefully.\n\n")
        var q = 0
        while (sb.length < chars) { sb.append(filler).append("\n\n"); if (q < questions.size && sb.length > (q + 1) * chars / (questions.size + 1)) sb.append(questions[q++]).append("\n\n") }
        while (q < questions.size) sb.append(questions[q++]).append("\n\n")
        return sb.toString()
    }

    @Test fun subQuestionsAreAllFound() {
        val t = longText(20_000)
        val subs = LongInput.subQuestions(t)
        questions.forEach { q -> assertTrue(subs.any { it.contains(q.substringAfter(". ").take(30)) }, "chybí: $q") }
    }

    @Test fun chunksLoseNothing() {
        listOf(5_000, 20_000, 50_000).forEach { n ->
            val t = longText(n); val parts = LongInput.chunks(t, 4_000)
            assertEquals(t, parts.joinToString(""), "rekonstrukce $n")
            assertTrue(parts.all { it.length <= 4_000 })
        }
    }

    private class Small : LlmEngine {
        override val id = "small"; override val label = "malé okno"; override val kind = EngineKind.FREE
        override val contextBudgetChars = 24_000
        val prompts = mutableListOf<String>()
        override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
            val u = messages.last { it.role == Role.USER }.content; prompts += u
            check(messages.sumOf { it.content.length } <= contextBudgetChars) { "přetečení okna: ${messages.sumOf { it.content.length }}" }
            return GenResult(if (u.startsWith("ČÁST")) "požadavky: " + Regex("\\d+\\. Jak souvisí téma číslo \\d+").findAll(u).joinToString { it.value } else "Odpověď na všechny body.", model = "s")
        }
    }
    private val noKb = object : KnowledgeSource { override fun search(query: String, limit: Int) = emptyList<Snippet>() }
    private val mem = object : MemoryStore { override fun all() = emptyList<MemoryItem>(); override fun remember(item: MemoryItem) = 0L; override fun forget(query: String) = 0 }

    @Test fun fiftyThousandCharsAreMappedNotTruncated() {
        val t = longText(50_000); val eng = Small()
        val r = Orchestrator(noKb, mem, { SelfState("S", "t", false) }, sleeper = {}).run(AskRequest(t, maxPasses = 1, reflect = false), eng, CancelToken())
        assertEquals("Odpověď na všechny body.", r.answer)
        val mapCalls = eng.prompts.filter { it.startsWith("ČÁST") }
        assertTrue(mapCalls.size >= 3, "částí ${mapCalls.size}")
        // každá část zadání prošla modelem, finální syntéza nese kontrolní seznam všech 18 podotázek
        val final = eng.prompts.last()
        questions.forEach { q -> assertTrue(final.contains(q.substringAfter(". ").take(30)), "syntéza ztratila: $q") }
        assertTrue("50" in final.substringBefore("znaků"))
    }

    @Test fun fittingMultiQuestionPromptGetsChecklist() {
        val t = longText(5_000); val eng = Small()
        Orchestrator(noKb, mem, { SelfState("S", "t", false) }, sleeper = {}).run(AskRequest(t, maxPasses = 1, reflect = false), eng, CancelToken())
        assertTrue(eng.prompts.none { it.startsWith("ČÁST") })
        assertTrue("KONTROLNÍ SEZNAM" in eng.prompts.last() && "18 podotázek" in eng.prompts.last(), eng.prompts.last().takeLast(400))
    }
}

package cz.axiom.omega.core.security

import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.llm.ChatMessage
import cz.axiom.omega.core.llm.Delta
import cz.axiom.omega.core.llm.GenOptions
import cz.axiom.omega.core.llm.GenResult
import cz.axiom.omega.core.llm.LlmEngine

/**
 * DLP ŠTÍT pro cloudové mozky: každá zpráva, která opouští telefon, projde maskováním tajemství
 * (API klíče, tokeny, privátní klíče, hesla v konfiguraci). Model v cloudu je nikdy neuvidí –
 * uživatel může klidně vložit log nebo konfiguraci a ptát se na ni.
 * Mozek v telefonu se neobaluje (data telefon neopouští).
 */
class DlpEngine(private val inner: LlmEngine, private val onRedact: (List<String>) -> Unit = {}) : LlmEngine by inner {
    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val found = mutableListOf<String>()
        val safe = messages.map { m ->
            val (t, hits) = SecTools.redact(m.content)
            found += hits
            if (hits.isEmpty()) m else ChatMessage(m.role, t)
        }
        if (found.isNotEmpty()) onRedact(found.distinct())
        return inner.generate(safe, options, cancel, onDelta)
    }
}

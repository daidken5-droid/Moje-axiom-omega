package cz.axiom.omega.core.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Claude přes oficiální Anthropic Java SDK (Messages API, streaming).
 *
 *  • výchozí model `claude-opus-5-5`, adaptivní přemýšlení (u Opus 5.5 vždy zapnuté),
 *    hloubku řídí `effort` – mapovaný z úrovně RANDY,
 *  • volitelně serverový nástroj web_search (citace se vrací jako zdroje),
 *  • zapnuté serverové fallbacky (`fallbacks: "default"`): když bezpečnostní klasifikátor
 *    požadavek odmítne, API ho samo zopakuje na záložním modelu.
 */
class ClaudeEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** Pevná hloubka (Turbo: "low" = nejrychlejší odpověď), jinak podle úrovně RANDY. */
    private val effortOverride: String? = null,
) : LlmEngine {

    override val id = "claude:$model"
    override val label = "Claude ($model)"
    override val kind = EngineKind.CLAUDE
    override val contextBudgetChars = 600_000

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .timeout(Duration.ofMinutes(15))
            .maxRetries(2)
            .build()
    }

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        val started = System.currentTimeMillis()
        val params = buildParams(messages, options)
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                cancel.onCancel { runCatching { stream.close() } }
                val acc = BetaMessageAccumulator.create()
                stream.stream().forEach { event ->
                    cancel.check()
                    acc.accumulate(event)
                    event.contentBlockDelta().ifPresent { d ->
                        d.delta().text().ifPresent { onDelta(Delta(it.text())) }
                        d.delta().thinking().ifPresent { onDelta(Delta(it.thinking(), reasoning = true)) }
                    }
                }
                val msg = acc.message()
                val text = StringBuilder()
                val reasoning = StringBuilder()
                val sources = LinkedHashMap<String, Source>()
                for (block in msg.content()) {
                    block.text().ifPresent { t ->
                        text.append(t.text())
                        t.citations().ifPresent { cites ->
                            cites.forEach { c ->
                                c.webSearchResultLocation().ifPresent { w ->
                                    sources.putIfAbsent(w.url(), Source(w.title().orElse(w.url()), w.url()))
                                }
                            }
                        }
                    }
                    block.thinking().ifPresent { reasoning.append(it.thinking()) }
                }
                val stop = msg.stopReason().map { it.asString() }.orElse(null)
                if (stop == "refusal") {
                    throw EngineException("Claude požadavek odmítl (bezpečnostní pravidla) a záložní model také. Přeformuluj zadání.")
                }
                if (text.isBlank()) throw EngineException("Claude nevrátil text (stop_reason=$stop).")
                if (stop == "max_tokens") text.append("\n\n[Odpověď byla zkrácena limitem tokenů.]")
                return GenResult(
                    text = text.toString().trim(),
                    reasoning = reasoning.toString().trim(),
                    model = msg.model().asString(),
                    stopReason = stop,
                    sources = sources.values.toList(),
                    inputTokens = msg.usage().inputTokens(),
                    outputTokens = msg.usage().outputTokens(),
                    millis = System.currentTimeMillis() - started,
                )
            }
        } catch (e: CancelledException) {
            throw e
        } catch (e: EngineException) {
            throw e
        } catch (e: UnauthorizedException) {
            throw EngineException("Claude: neplatný API klíč (401). Zkontroluj klíč v Systému.", cause = e)
        } catch (e: PermissionDeniedException) {
            throw EngineException("Claude: klíč nemá oprávnění k modelu $model (403).", cause = e)
        } catch (e: NotFoundException) {
            throw EngineException("Claude: model '$model' nenalezen (404). Načti seznam modelů.", cause = e)
        } catch (e: RateLimitException) {
            throw EngineException("Claude: limit požadavků (429). Zkus to za chvíli.", retryable = true, cause = e)
        } catch (e: AnthropicServiceException) {
            throw EngineException("Claude: HTTP ${e.statusCode()} – ${e.message}", retryable = e.statusCode() >= 500, cause = e)
        } catch (e: AnthropicIoException) {
            if (cancel.cancelled) throw CancelledException()
            throw EngineException("Claude: síťová chyba – ${e.message}", retryable = true, cause = e)
        } catch (e: RuntimeException) {
            if (cancel.cancelled) throw CancelledException()
            throw EngineException("Claude: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
    }

    internal fun buildParams(messages: List<ChatMessage>, options: GenOptions): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(options.maxTokens.toLong().coerceIn(1024, 64_000))
            .thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(BetaOutputConfig.builder().effort(mapEffort(effortOverride ?: options.effort)).build())
        // serverové záložní modely (při odmítnutí) – Haiku je nepodporuje
        if (!model.contains("haiku")) b.addBeta(FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        if (system.isNotBlank()) b.system(system)
        for (m in messages) when (m.role) {
            Role.USER -> b.addUserMessage(m.content)
            Role.ASSISTANT -> b.addAssistantMessage(m.content)
            Role.SYSTEM -> Unit
        }
        if (options.webSearch) b.addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
        return b.build()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        /** Turbo: nejrychlejší model Claude. */
        const val TURBO_MODEL = "claude-haiku-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        fun mapEffort(effort: String): BetaOutputConfig.Effort = when (effort.lowercase()) {
            "low" -> BetaOutputConfig.Effort.LOW
            "medium" -> BetaOutputConfig.Effort.MEDIUM
            "xhigh" -> BetaOutputConfig.Effort.XHIGH
            "max" -> BetaOutputConfig.Effort.MAX
            else -> BetaOutputConfig.Effort.HIGH
        }

        fun listModels(apiKey: String): List<String> {
            val c = AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build()
            try {
                return c.models().list().autoPager().map { it.id() }.toList()
            } finally {
                c.close()
            }
        }
    }
}

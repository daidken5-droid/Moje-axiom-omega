package cz.axiom.omega.core.facts

import java.security.MessageDigest
import java.time.LocalDate

/**
 * HYPERFORGE M4 – VERIFIED FACT STORE: kompaktní verzovaná fakta ODDĚLENÁ od Kotlin logiky
 * (soubory resources/factstore/NÁZEV.tsv, vyrábí je tools/factc – zdroj, licence, platnost a kontrolní součet u každého řádku).
 *
 * Integrita: při načtení se spočítá SHA-256 celého souboru a porovná s manifestem. Nesoulad (poškozený / podvržený
 * soubor) = obchod je PRÁZDNÝ a reflex se zdrží odpovědi – nikdy neodpovídá z dat, která neprošla kompilátorem.
 * Prošlý fakt (validUntil < dnes) se jako ověřený nevrátí.
 */
class FactStore private constructor(val facts: List<Fact>, val integrity: Integrity) {

    data class Fact(
        val factId: String, val entityId: String, val predicate: String, val value: String, val language: String,
        val source: String, val asOf: String, val validUntil: String, val reliabilityClass: String,
        val license: String, val revision: Int, val checksum: String,
    ) {
        fun valid(today: LocalDate): Boolean = runCatching { !LocalDate.parse(validUntil).isBefore(today) }.getOrDefault(false)
    }

    enum class Integrity { OK, MISSING, CHECKSUM_MISMATCH, SCHEMA_ERROR }

    private val byEntity: Map<String, List<Fact>> = facts.groupBy { it.entityId }
    fun of(entityId: String): List<Fact> = byEntity[entityId].orEmpty()
    fun value(entityId: String, predicate: String, today: LocalDate): Fact? = of(entityId).firstOrNull { it.predicate == predicate && it.valid(today) }
    fun entities(): Set<String> = byEntity.keys

    companion object {
        private val HEADER = listOf("factId", "entityId", "predicate", "value", "language", "source", "asOf", "validUntil", "reliabilityClass", "license", "revision", "checksum")

        /** Načte `factstore/<name>.tsv` a ověří ho proti `factstore/<name>.manifest.json`. */
        fun load(name: String): FactStore {
            val cl = FactStore::class.java.classLoader ?: return FactStore(emptyList(), Integrity.MISSING)
            val bytes = cl.getResourceAsStream("factstore/$name.tsv")?.use { it.readBytes() } ?: return FactStore(emptyList(), Integrity.MISSING)
            val manifest = cl.getResourceAsStream("factstore/$name.manifest.json")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return FactStore(emptyList(), Integrity.MISSING)
            val expected = Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(manifest)?.groupValues?.get(1) ?: return FactStore(emptyList(), Integrity.SCHEMA_ERROR)
            return parse(bytes, expected)
        }

        fun parse(bytes: ByteArray, expectedSha256: String): FactStore {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (actual != expectedSha256) return FactStore(emptyList(), Integrity.CHECKSUM_MISMATCH)
            val lines = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
            if (lines.isEmpty() || lines[0].split('\t') != HEADER) return FactStore(emptyList(), Integrity.SCHEMA_ERROR)
            val out = ArrayList<Fact>(lines.size)
            for (l in lines.drop(1)) {
                val p = l.split('\t')
                if (p.size != HEADER.size) return FactStore(emptyList(), Integrity.SCHEMA_ERROR)
                out += Fact(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10].toIntOrNull() ?: return FactStore(emptyList(), Integrity.SCHEMA_ERROR), p[11])
            }
            return FactStore(out, Integrity.OK)
        }
    }
}

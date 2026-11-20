package cz.axiom.vega.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * PRACOVNÍ PROSTOR PROJEKTU (programování): VEGA pracuje v KOPII otevřeného projektu ([root]/work), původní
 * import drží v [root]/base. Každá úprava ověří aktuální verzi souboru (otisk), mění jen svou část a zapíše se do
 * deníku změn → návrat jedné změny nezničí jiné úpravy uživatele.
 */
class Workspace(val root: File) {
    private val lock: Any = LOCKS.computeIfAbsent(root.absolutePath) { Any() }
    /** Tajný podle jména NEBO podle obsahu (prvních 64 kB) – do modelu, testů ani exportu nepatří. */
    fun isSecretFile(p: String): Boolean = isSecret(p) || runCatching { file(p).let { f -> f.isFile && f.inputStream().use { s -> looksSecretContent(String(s.readNBytes(65_536), Charsets.UTF_8)) } } }.getOrDefault(false)
    val work = File(root, "work")
    private val base = File(root, "base")
    private val meta = File(root, "meta").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Change(val id: Int, val path: String, val op: String, val before: String?, val after: String?, val search: String? = null, val replace: String? = null, val at: Long = System.currentTimeMillis(), val note: String = "")

    @Serializable
    data class Verification(val kind: String, val command: String, val exitCode: Int, val output: String, val revision: String, val at: Long = System.currentTimeMillis()) {
        /** „run“ = skutečně spuštěno; „static“ = jen statická kontrola (výslovně označeno). */
        val label get() = when (kind) { "run" -> "spuštěno"; "static" -> "jen staticky zkontrolováno"; else -> "neověřeno (testy nedoběhly)" }
    }

    data class Symbol(val name: String, val kind: String, val path: String, val line: Int)

    class Conflict(msg: String) : Exception(msg)

    init { work.mkdirs() }

    val name: String get() = File(meta, "name").takeIf { it.exists() }?.readText() ?: root.name

    companion object {
        /** Soubory, které se nikdy nečtou do kontextu modelu (klíče, osobní data). */
        /** Kontroluj vždy NORMALIZOVANOU cestu ([isSecret]) – „.env/“ nebo „cfg\\.env“ by surový výraz obešly. */
        val SECRET = Regex("(^|/)(\\.env(\\..*)?|.*\\.(pem|key|p12|pfx|jks|keystore|ovpn)|id_(rsa|ed25519|ecdsa|dsa).*|secrets?\\.(json|ya?ml|properties|toml)|.*credentials.*\\.json|.*service[-_.]?account.*\\.json|client_secret.*\\.json|.*firebase-adminsdk.*\\.json|token\\.json|google-services\\.json|GoogleService-Info\\.plist|local\\.properties|keystore\\.properties|gradle\\.properties|\\.npmrc|\\.netrc|\\.pypirc|\\.git-credentials|\\.htpasswd)$", RegexOption.IGNORE_CASE)

        fun isSecret(path: String): Boolean {
            // stejná normalizace jako safe() (i ořezání mezer): „.env “ musí být tajné stejně jako „.env“
            val n = path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }.joinToString("/")
            return SECRET.containsMatchIn(n) || SECRET.containsMatchIn(path)
        }
        private val SKIP = Regex("(^|/)(\\.git|node_modules|build|\\.gradle|dist|__pycache__|\\.idea)(/|$)")
        val TEXT_EXT = setOf("kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "json", "md", "txt", "csv", "tsv", "xml", "html", "css", "yml", "yaml", "toml", "gradle", "properties", "sh", "sql", "go", "rs", "c", "h", "cpp", "swift", "rb", "php", "ini", "cfg", "log")
        const val MAX_FILE = 2_000_000

        fun create(root: File, name: String, files: Map<String, ByteArray> = emptyMap()): Workspace {
            require(!root.exists() || root.listFiles().isNullOrEmpty()) { "Složka projektu už existuje" }
            val w = Workspace(root)
            File(w.meta, "name").writeText(name)
            files.forEach { (p, b) -> val safe = w.safe(p); if (!SKIP.containsMatchIn(safe) && b.size <= MAX_FILE) { File(w.work, safe).apply { parentFile.mkdirs() }.writeBytes(b); File(w.base, safe).apply { parentFile.mkdirs() }.writeBytes(b) } }
            return w
        }

        /** Obsah, který vypadá jako klíč (privátní klíč, JSON služebního účtu, API klíče) – i když jméno souboru nevadí. */
        private val SECRET_CONTENT = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----|\"private_key\"\\s*:|\"client_secret\"\\s*:|\"refresh_token\"\\s*:")
        fun looksSecretContent(s: String) = SECRET_CONTENT.containsMatchIn(s) || Mem.looksSecret(s)
        /** Zámek na projekt napříč instancemi (most i výkonné vlákno vytvářejí vlastní Workspace). */
        private val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()

        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    /** Relativní cesta bez úniku mimo projekt. */
    fun safe(p: String): String {
        val n = p.replace('\\', '/').trim().trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }
        require(n.none { it == ".." } && n.isNotEmpty()) { "Neplatná cesta $p" }
        return n.joinToString("/")
    }

    fun file(p: String) = File(work, safe(p))

    fun files(): List<String> = work.walkTopDown().filter { it.isFile }.map { it.relativeTo(work).path.replace('\\', '/') }.filter { !SKIP.containsMatchIn(it) }.sorted().toList()

    fun read(p: String): String = file(p).readText()
    fun hash(p: String): String? = file(p).takeIf { it.exists() }?.let { sha(it.readBytes()) }

    /** Otisk celé revize projektu (cesta + obsah) – patří ke každému záznamu ověření. */
    fun revision(): String = sha(files().joinToString("\n") { "$it:${hash(it)}" }.toByteArray()).take(12)

    // ------------------------------------------------------------------ deník změn

    private val logFile = File(meta, "changes.json")
    fun changes(): List<Change> = runCatching { json.decodeFromString(ListSerializer(Change.serializer()), logFile.readText()) }.getOrDefault(emptyList())
    private fun log(c: Change) { logFile.writeText(json.encodeToString(ListSerializer(Change.serializer()), (changes() + c).takeLast(300))) }
    private fun nextId() = (changes().maxOfOrNull { it.id } ?: 0) + 1

    private fun atomicWrite(f: File, text: String) {
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    /** Zapíše celý soubor. [expectedHash] = otisk, který viděl ten, kdo změnu navrhl (null = nový soubor). */
    fun write(p: String, content: String, expectedHash: String?, note: String = ""): Change = synchronized(lock) {
        val f = file(p)
        val cur = hash(p)
        if (cur != expectedHash) throw Conflict(if (cur == null) "Soubor $p mezitím zmizel." else if (expectedHash == null) "Soubor $p už existuje – načti ho a navrhni úpravu." else "Soubor $p se mezitím změnil (jiná úprava) – načítám znovu, nic nepřepisuji.")
        if (f.exists()) requireUtf8(f, p)
        val before = if (f.exists()) f.readText() else null
        atomicWrite(f, content)
        return Change(nextId(), safe(p), if (before == null) "create" else "write", before, content, note = note).also { log(it) }
    }

    /** Soubor v jiném kódování (např. windows-1250) by úprava přes UTF-8 nevratně poškodila (diakritika → �). */
    private fun requireUtf8(f: File, p: String) { if (!Encoding.isUtf8(f.readBytes())) throw Conflict("Soubor $p není v UTF-8 (nejspíš windows-1250) – neupravuji ho, poškodila by se diakritika. Převeď ho na UTF-8.") }

    /**
     * PŘESNÁ ÚPRAVA: nahradí jediný výskyt [search]. Víc výskytů nebo žádný → chyba (žádné hádání).
     * Zbytek souboru – včetně nesouvisejících úprav uživatele – zůstane beze změny.
     */
    fun patch(p: String, search0: String, replace0: String, expectedHash: String? = null, note: String = ""): Change = synchronized(lock) {
        val f = file(p)
        require(f.exists()) { "Soubor $p neexistuje" }
        requireUtf8(f, p)
        val cur = f.readText()
        // soubor s CRLF: model vrací úsek s \n → porovnat ve stejných koncích řádků
        val crlf = cur.contains("\r\n") && !search0.contains("\r\n")
        val search = if (crlf) search0.replace("\n", "\r\n") else search0
        val replace = if (crlf) replace0.replace("\r\n", "\n").replace("\n", "\r\n") else replace0
        if (expectedHash != null && sha(cur.toByteArray()) != expectedHash) {
            // soubor se změnil, ale hledaný úsek je stále jednoznačný → úprava je bezpečná (jiná změna jinde)
            if (Regex.escape(search).toRegex().findAll(cur).count() != 1) throw Conflict("Soubor $p se mezitím změnil a upravovaný úsek už nesedí.")
        }
        val n = countOf(cur, search)
        if (n == 0) throw Conflict("V $p jsem hledaný úsek nenašel (soubor je jiný, než jsem čekal).")
        if (n > 1) throw Conflict("Úsek se v $p vyskytuje ${n}×, potřebuji delší jednoznačný kus.")
        val out = cur.replaceFirst(search, replace)
        atomicWrite(f, out)
        return Change(nextId(), safe(p), "patch", cur, out, search, replace, note = note).also { log(it) }
    }

    private fun countOf(s: String, sub: String): Int { if (sub.isEmpty()) return 0; var i = 0; var n = 0; while (true) { i = s.indexOf(sub, i); if (i < 0) return n; n++; i += sub.length } }

    fun delete(p: String, expectedHash: String?): Change = synchronized(lock) {
        val f = file(p); val cur = hash(p) ?: throw Conflict("Soubor $p neexistuje")
        if (expectedHash != null && cur != expectedHash) throw Conflict("Soubor $p se mezitím změnil.")
        val before = f.readText(); f.delete()
        return Change(nextId(), safe(p), "delete", before, null).also { log(it) }
    }

    /**
     * NÁVRAT ZMĚNY: vrátí změnu [id] tak, aby ostatní úpravy zůstaly. Přesná úprava se vrací obráceně (replace → search)
     * jen v jejím úseku; celý zápis jen tehdy, když soubor od té doby nikdo nezměnil.
     */
    fun undo(id: Int? = null): Change = synchronized(lock) { undo0(id) }
    private fun undo0(id: Int?): Change {
        val all = changes()
        val c = (if (id == null) all.lastOrNull { !it.op.startsWith("undo") && all.none { u -> u.op == "undo#${it.id}" } } else all.firstOrNull { it.id == id }) ?: throw Conflict("Není co vrátit.")
        if (c.op.startsWith("undo") || all.any { it.op == "undo#${c.id}" }) throw Conflict("Změna #${c.id} už je vrácená.")
        val f = file(c.path)
        val cur = if (f.exists()) f.readText() else null
        val restored: String? = when {
            cur == c.after -> c.before
            c.op == "patch" && cur != null && c.replace != null && c.search != null && countOf(cur, c.replace) == 1 && !c.search.contains(c.replace) -> cur.replaceFirst(c.replace, c.search)
            else -> throw Conflict("Soubor ${c.path} se od změny #${c.id} upravoval jinde – vrácení by přepsalo cizí změnu. Ukážu rozdíl, rozhodni ručně.")
        }
        if (restored == null) f.delete() else atomicWrite(f, restored)
        return Change(nextId(), c.path, "undo#${c.id}", cur, restored).also { log(it) }
    }

    /** Rozdíl proti původnímu importu (nebo jen jeden soubor). */
    fun diffFromBase(p: String? = null): List<Pair<String, Value.DiffV>> {
        val paths = (p?.let { listOf(safe(it)) } ?: (files() + base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).path.replace('\\', '/') }.toList()).distinct().sorted())
        return paths.mapNotNull { path ->
            val a = File(base, path).takeIf { it.exists() }?.readText() ?: ""
            val b = File(work, path).takeIf { it.exists() }?.readText() ?: ""
            // soubory s klíči do rozdílu (UI, historie chatu) nepatří
            if (a == b || isSecret(path)) null else path to Diff.lines(a, b)
        }
    }

    // ------------------------------------------------------------------ porozumění kódu

    /** (výraz, skupina druhu nebo pevný druh, skupina jména) */
    private class SymRx(val rx: Regex, val kind: String?, val kindGroup: Int, val nameGroup: Int)

    private val symRx = mapOf(
        "kt" to listOf(SymRx(Regex("^\\s*(?:(?:private|internal|public|protected|override|suspend|inline|data|sealed|abstract|open|enum|const)\\s+)*(fun|class|object|interface|val|var)\\s+(?:<[^>]+>\\s*)?(?:[[\\p{L}\\p{N}_$].]+\\.)?([\\p{L}\\p{N}_$]+)"), null, 1, 2)),
        "java" to listOf(SymRx(Regex("^\\s*(?:(?:public|private|protected|static|final|abstract)\\s+)*(class|interface|enum)\\s+([\\p{L}\\p{N}_$]+)"), null, 1, 2),
            SymRx(Regex("^\\s*(?:(?:public|private|protected|static|final|synchronized)\\s+)+[\\p{L}\\p{N}_$<>\\[\\], ]+\\s+([\\p{L}\\p{N}_$]+)\\s*\\("), "method", 0, 1)),
        "js" to listOf(SymRx(Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([\\p{L}\\p{N}_$]+)"), "function", 0, 1),
            SymRx(Regex("^\\s*(?:export\\s+)?class\\s+([\\p{L}\\p{N}_$]+)"), "class", 0, 1),
            SymRx(Regex("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([\\p{L}\\p{N}_$]+)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[\\p{L}\\p{N}_$]+)\\s*=>"), "function", 0, 1),
            SymRx(Regex("^\\s*([\\p{L}\\p{N}_$]+)\\s*\\([^)]*\\)\\s*\\{\\s*$"), "method", 0, 1)),
        "py" to listOf(SymRx(Regex("^\\s*(def|class)\\s+([\\p{L}\\p{N}_$]+)"), null, 1, 2)),
    )

    private val notNames = setOf("if", "for", "while", "switch", "catch", "return", "function")

    private fun lang(p: String) = when (p.substringAfterLast('.').lowercase()) { "kt", "kts" -> "kt"; "java" -> "java"; "js", "mjs", "cjs", "ts", "tsx", "jsx" -> "js"; "py" -> "py"; else -> null }

    fun symbols(): List<Symbol> = files().filter { lang(it) != null && !isSecret(it) }.flatMap { p ->
        val rxs = symRx[lang(p)]!!
        read(p).lines().mapIndexedNotNull { i, l ->
            rxs.firstNotNullOfOrNull { r -> r.rx.find(l)?.let { m -> Symbol(m.groupValues[r.nameGroup], r.kind ?: m.groupValues[r.kindGroup], p, i + 1) } }?.takeIf { it.name !in notNames }
        }
    }

    /** Kde se symbol volá (mimo jeho definici). */
    fun callers(name: String): List<Symbol> {
        val defs = symbols().filter { it.name == name }.map { it.path to it.line }.toSet()
        val rx = Regex("\\b${Regex.escape(name)}\\s*\\(")
        return files().filter { lang(it) != null }.flatMap { p -> read(p).lines().mapIndexedNotNull { i, l -> if (rx.containsMatchIn(l) && (p to i + 1) !in defs) Symbol(name, "volání", p, i + 1) else null } }
    }

    /** Konfigurace projektu (build, balíčky) – nikdy soubory s klíči. */
    fun config(): Map<String, String> = files().filter { p ->
        !isSecretFile(p) && p.substringAfterLast('/') in setOf("package.json", "build.gradle", "build.gradle.kts", "settings.gradle.kts", "pyproject.toml", "requirements.txt", "tsconfig.json", "Cargo.toml", "go.mod", "pom.xml", "vega.json")
    }.associateWith { read(it).take(8_000) }

    /** Soubory relevantní k zadání (jména symbolů/souborů ve větě) – pro kontext modelu, v limitu znaků. */
    fun relevant(task: String, maxChars: Int = 24_000): List<String> {
        val words = cz.axiom.vega.base.Text.words(task, 3).map { it.lowercase() }.toSet()
        val syms = symbols()
        val scored = files().filter { !isSecretFile(it) && it.substringAfterLast('.').lowercase() in TEXT_EXT && file(it).length() <= maxChars }.map { p ->
            val n = p.lowercase()
            var s = words.count { n.contains(it) } * 5
            s += syms.count { it.path == p && it.name.lowercase() in words } * 4
            if (Regex("(test|spec)").containsMatchIn(n)) s += 1
            p to s
        }.sortedByDescending { it.second }
        val out = ArrayList<String>(); var used = 0
        for ((p, _) in scored) { val len = file(p).length().toInt(); if (used + len > maxChars && out.isNotEmpty()) continue; out += p; used += len; if (used > maxChars) break }
        return out
    }

    /** ZIP s pracovní kopií (export). */
    fun exportZip(): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        // soubory s klíči do exportu (veřejné Stažené) nepatří
        val dir = name.replace(Regex("[^\\p{L}\\p{Nd} ._-]"), "_").replace(Regex("\\.{2,}"), "_").trim('.', ' ').ifBlank { "projekt" } // název nesmí udělat „../“ v ZIPu
        java.util.zip.ZipOutputStream(o).use { z -> files().filter { !isSecretFile(it) }.forEach { p -> z.putNextEntry(java.util.zip.ZipEntry("$dir/$p")); z.write(file(p).readBytes()); z.closeEntry() } }
        return o.toByteArray()
    }

    // ------------------------------------------------------------------ ověření

    private val verFile = File(meta, "verifications.json")
    fun verifications(): List<Verification> = runCatching { json.decodeFromString(ListSerializer(Verification.serializer()), verFile.readText()) }.getOrDefault(emptyList())
    fun record(v: Verification) { verFile.writeText(json.encodeToString(ListSerializer(Verification.serializer()), (verifications() + v).takeLast(100))) }

    /** Statická kontrola (závorky, uvozovky, JSON) – VÝSLOVNĚ označená jako „jen staticky“. */
    fun staticCheck(paths: List<String> = files()): Verification {
        val problems = ArrayList<String>()
        for (p in paths) {
            if (!file(p).exists()) continue
            val ext = p.substringAfterLast('.').lowercase()
            val t = read(p)
            if (ext == "json") JsonTool.parse(t).second?.let { problems += "$p: $it" }
            if (ext in setOf("kt", "kts", "java", "js", "ts", "py", "c", "cpp", "go", "rs", "swift")) brackets(t)?.let { problems += "$p: $it" }
        }
        val v = Verification("static", "statická kontrola (${paths.size} souborů)", if (problems.isEmpty()) 0 else 1, if (problems.isEmpty()) "Bez nálezů (závorky, JSON)." else problems.joinToString("\n"), revision())
        record(v); return v
    }

    private fun brackets(t: String): String? {
        val st = ArrayDeque<Pair<Char, Int>>(); var line = 1; var i = 0; var q: Char? = null
        while (i < t.length) {
            val c = t[i]
            if (c == '\n') line++
            if (q != null) { if (c == '\\') i++ else if (c == q) q = null }
            else when (c) {
                '"', '\'', '`' -> q = c
                '/' -> if (i + 1 < t.length && t[i + 1] == '/') { while (i < t.length && t[i] != '\n') i++; line++ } else if (i + 1 < t.length && t[i + 1] == '*') { val e = t.indexOf("*/", i + 2); if (e < 0) return null; line += t.substring(i, e).count { it == '\n' }; i = e + 1 }
                '#' -> {}
                '(', '[', '{' -> st.addLast(c to line)
                ')', ']', '}' -> { val o = st.removeLastOrNull() ?: return "přebývá „$c“ na řádku $line"; if ("([{".indexOf(o.first) != ")]}".indexOf(c)) return "„${o.first}“ z řádku ${o.second} se zavírá „$c“ na řádku $line" }
            }
            i++
        }
        return st.lastOrNull()?.let { "neuzavřené „${it.first}“ z řádku ${it.second}" }
    }
}

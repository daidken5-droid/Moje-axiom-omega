package cz.axiom.omega.core.step0

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * STEP-0 kalkulačka – deterministický parser výrazů (rekurzivní sestup).
 *
 * Gramatika:
 *   expr   := term (('+'|'-') term)*
 *   term   := unary (('*'|'/'|'%') unary)*
 *   unary  := ('+'|'-') unary | power
 *   power  := atom ('^' unary)?          // pravě asociativní, -2^2 = -4
 *   atom   := number | const | func '(' expr ')' | '(' expr ')'
 *
 * Oproti AXIOM 2.1/Titanium: chybějící ')' je chyba (dřív se tiše ignorovala),
 * zbytek vstupu po výrazu je chyba (dřív "2+2)" vrátilo 4), podpora × ÷ a funkcí.
 */
object Calc {

    fun eval(expr: String): Result<Double> = runCatching {
        val cleaned = expr
            .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-')
            .replace(" ", "").replace(" ", "")
            .replace(',', '.')
            .lowercase()
        require(cleaned.isNotEmpty()) { "Prázdný výraz" }
        val p = Parser(cleaned)
        val v = p.parseExpression()
        require(p.done()) { "Neočekávaný znak '${cleaned[p.pos]}' na pozici ${p.pos + 1}" }
        require(v.isFinite()) { "Výsledek není konečné číslo" }
        v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun done() = pos >= s.length
        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; x += parseTerm() }
                    '-' -> { pos++; x -= parseTerm() }
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseUnary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; x *= parseUnary() }
                    '/' -> {
                        pos++
                        val d = parseUnary()
                        require(d != 0.0) { "Dělení nulou" }
                        x /= d
                    }
                    '%' -> {
                        pos++
                        val d = parseUnary()
                        require(d != 0.0) { "Modulo nulou" }
                        x %= d
                    }
                    else -> return x
                }
            }
        }

        private fun parseUnary(): Double = when (peek()) {
            '+' -> { pos++; parseUnary() }
            '-' -> { pos++; -parseUnary() }
            else -> parsePower()
        }

        private fun parsePower(): Double {
            val base = parseAtom()
            if (peek() == '^') {
                pos++
                return base.pow(parseUnary())
            }
            return base
        }

        private fun parseAtom(): Double {
            val c = peek() ?: throw IllegalArgumentException("Neúplný výraz")
            if (c == '(') {
                pos++
                val v = parseExpression()
                require(peek() == ')') { "Chybí uzavírací závorka" }
                pos++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (pos < s.length && s[pos] == 'e' && pos + 1 < s.length &&
                    (s[pos + 1].isDigit() || ((s[pos + 1] == '-' || s[pos + 1] == '+') && pos + 2 < s.length && s[pos + 2].isDigit()))
                ) {
                    pos += 2
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                return s.substring(start, pos).toDoubleOrNull()
                    ?: throw IllegalArgumentException("Neplatné číslo '${s.substring(start, pos)}'")
            }
            if (c.isLetter()) {
                val start = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val name = s.substring(start, pos)
                when (name) {
                    "pi" -> return PI
                    "e" -> return E
                }
                require(peek() == '(') { "Neznámý symbol '$name'" }
                pos++
                val arg = parseExpression()
                require(peek() == ')') { "Chybí uzavírací závorka u $name()" }
                pos++
                return when (name) {
                    "sqrt", "odm" -> { require(arg >= 0) { "Odmocnina ze záporného čísla" }; sqrt(arg) }
                    "abs" -> abs(arg)
                    "ln" -> { require(arg > 0) { "ln mimo definiční obor" }; ln(arg) }
                    "log" -> { require(arg > 0) { "log mimo definiční obor" }; log10(arg) }
                    "sin" -> sin(arg)
                    "cos" -> cos(arg)
                    "tan" -> tan(arg)
                    else -> throw IllegalArgumentException("Neznámá funkce '$name'")
                }
            }
            throw IllegalArgumentException("Neočekávaný znak '$c' na pozici ${pos + 1}")
        }
    }
}

package app.murmur.brain

import kotlin.math.pow

/**
 * A small calculator the Brain hands arithmetic to, because small models get sums wrong.
 * Handles + - * / ^ %, parentheses, and "15% of 80" / "80 + 15%" the way people write them.
 */
object Calc {

    fun eval(input: String): Double? = runCatching {
        val prepared = input.lowercase()
            .replace("×", "*").replace("÷", "/").replace(",", "")
            .replace(Regex("(?<=\\d)\\s*(x|times)\\s*(?=[\\d(])"), "*")
            .replace(Regex("\\s(plus)\\s"), " + ").replace(Regex("\\s(minus)\\s"), " - ")
            .replace(Regex("\\s(divided by|over)\\s"), " / ")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*"), "($1/100)*")
            .replace(Regex("[\\$€£]"), "")
            .replace("what is", "").replace("what's", "").replace("?", "").replace("=", "")
            .trim()
        Parser(prepared).run { val v = expr(); skip(); require(i == s.length) { "leftover input" }; v }
    }.getOrNull()?.takeIf { it.isFinite() }

    fun format(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.4f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skip(); return s.getOrNull(i) }

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v = plusMinus(v, true) }
                    '-' -> { i++; v = plusMinus(v, false) }
                    else -> return v
                }
            }
        }

        /** "80 + 15%" means 80 plus 15% of 80. */
        fun plusMinus(left: Double, plus: Boolean): Double {
            val right = term()
            skip()
            val percent = i < s.length && s[i] == '%'
            if (percent) i++
            val amount = if (percent) left * right / 100 else right
            return if (plus) left + amount else left - amount
        }

        fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= power() }
                    '/' -> { i++; v /= power() }
                    '%' -> {
                        // A trailing % on its own ("15%") is a percentage; leave it for plusMinus.
                        val after = s.substring(i + 1).trimStart()
                        if (after.isEmpty() || after[0] in "+-)") return v
                        i++; v %= power()
                    }
                    else -> return v
                }
            }
        }

        fun power(): Double {
            val base = unary()
            return if (peek() == '^') { i++; base.pow(power()) } else base
        }

        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> atom()
        }

        fun atom(): Double {
            if (peek() == '(') { i++; val v = expr(); require(peek() == ')'); i++; return v }
            skip()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "number expected" }
            return s.substring(start, i).toDouble()
        }
    }
}

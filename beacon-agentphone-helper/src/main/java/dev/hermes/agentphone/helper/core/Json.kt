package dev.hermes.agentphone.helper.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * JSON as the plugin's reference codec reads and writes it (Python's json module): objects become LinkedHashMap
 * (the last of duplicate keys wins), arrays List, integers Long (BigInteger past 64 bits), other numbers Double, plus
 * String, Boolean and null. [encode] writes the bytes json.dumps(ensure_ascii=False, separators=(',', ':')) writes,
 * so a sealed frame can be compared byte for byte with the test vectors. org.json is not used: it is lenient, and the
 * device's copy differs from the JVM test artifact.
 */
object Json {
    class Invalid : Exception("json")

    private const val MAX_DEPTH = 64

    /** Strict UTF-8 (no replacement characters) then [parse]; [Invalid] for anything else. */
    fun parseUtf8(bytes: ByteArray): Any? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw Invalid()
        }
        return parse(text)
    }

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.space()
        val value = reader.value(0)
        reader.space()
        if (reader.i != text.length) throw Invalid()
        return value
    }

    private class Reader(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH || i >= s.length) throw Invalid()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                'N' -> word("NaN", Double.NaN)
                'I' -> word("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' && s.startsWith("-Infinity", i)) word("-Infinity", Double.NEGATIVE_INFINITY) else num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Invalid()
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            space()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') throw Invalid()
                val key = str()
                space()
                if (i >= s.length || s[i] != ':') throw Invalid()
                i++
                space()
                val v = value(depth + 1)
                out[key] = v // like Python's dict: the first position, the last value
                space()
                if (i >= s.length) throw Invalid()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw Invalid()
            }
        }

        fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            space()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                space()
                out.add(value(depth + 1))
                space()
                if (i >= s.length) throw Invalid()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw Invalid()
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw Invalid()
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c < ' ' -> throw Invalid()
                    c != '\\' -> b.append(c)
                    else -> {
                        if (i >= s.length) throw Invalid()
                        when (s[i++]) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Invalid()
                                var v = 0
                                for (k in 0 until 4) {
                                    val d = Character.digit(s[i + k], 16)
                                    if (d < 0 || s[i + k].code > 0x7f) throw Invalid()
                                    v = v * 16 + d
                                }
                                i += 4
                                b.append(v.toChar())
                            }
                            else -> throw Invalid()
                        }
                    }
                }
            }
        }

        fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length || s[i] !in '0'..'9') throw Invalid()
            if (s[i] == '0') i++ else while (i < s.length && s[i] in '0'..'9') i++
            var integer = true
            if (i < s.length && s[i] == '.') {
                integer = false
                i++
                if (i >= s.length || s[i] !in '0'..'9') throw Invalid()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integer = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') throw Invalid()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (!integer) return text.toDouble()
            return text.toLongOrNull() ?: BigInteger(text)
        }
    }

    fun encode(value: Any?): ByteArray = StringBuilder().also { write(it, value) }.toString().toByteArray(Charsets.UTF_8)

    private fun write(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is Boolean -> b.append(if (v) "true" else "false")
            is Int, is Long -> b.append(v.toString())
            is String -> string(b, v)
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(',')
                    first = false
                    string(b, k as String)
                    b.append(':')
                    write(b, x)
                }
                b.append('}')
            }
            is List<*> -> {
                b.append('[')
                v.forEachIndexed { n, x -> if (n > 0) b.append(','); write(b, x) }
                b.append(']')
            }
            else -> throw IllegalArgumentException("not a JSON value")
        }
    }

    private fun string(b: StringBuilder, s: String) {
        b.append('"')
        var n = 0
        while (n < s.length) {
            val c = s[n]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c == '\b' -> b.append("\\b")
                c == '\u000c' -> b.append("\\f")
                c < ' ' -> b.append(hex(c))
                Character.isHighSurrogate(c) && n + 1 < s.length && Character.isLowSurrogate(s[n + 1]) -> { b.append(c).append(s[n + 1]); n++ }
                Character.isSurrogate(c) -> b.append(hex(c)) // a lone half: escaped, never '?'
                else -> b.append(c)
            }
            n++
        }
        b.append('"')
    }

    private fun hex(c: Char) = "\\u" + Integer.toHexString(c.code).padStart(4, '0')
}

package com.capstone.stethoscope.analysis.segmenter

/**
 * Just enough JSON to read the algorithm team's `model.json` and `testvectors.json`.
 *
 * Not `org.json`: Android's copy is a stub on the JVM, so unit tests that load the real
 * model would throw "Method ... not mocked". A recursive-descent reader is ~100 lines and
 * keeps the model file exactly as the team exported it — no conversion step to forget
 * when they send new weights.
 *
 * Objects become `Map<String, Any?>`, arrays `List<Any?>`, numbers `Double`.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.i == text.length) { "Trailing content at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(s[i] == ':') { "Expected ':' at $i" }
                i++
                m[k] = value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> error("Expected ',' or '}' at ${i - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> error("Expected ',' or ']' at ${i - 1}")
                }
            }
        }

        private fun str(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("Bad escape \\$e at ${i - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}

/** Typed accessors over a parsed [MiniJson] object, failing with the key's name. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String) =
    requireNotNull(this[key]) { "Missing '$key'" } as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(key: String) =
    requireNotNull(this[key]) { "Missing '$key'" } as List<Any?>

internal fun Map<String, Any?>.num(key: String) =
    (requireNotNull(this[key]) { "Missing '$key'" } as Double)

internal fun Map<String, Any?>.int(key: String) = num(key).toInt()

internal fun Map<String, Any?>.str(key: String) =
    requireNotNull(this[key]) { "Missing '$key'" } as String

internal fun Map<String, Any?>.doubles(key: String): DoubleArray {
    val l = list(key)
    return DoubleArray(l.size) { (l[it] as Double) }
}

internal fun Map<String, Any?>.floats(key: String): FloatArray {
    val l = list(key)
    return FloatArray(l.size) { (l[it] as Double).toFloat() }
}

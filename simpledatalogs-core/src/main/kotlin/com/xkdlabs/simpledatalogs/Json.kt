package com.xkdlabs.simpledatalogs

/** A hand-written JSON writer for flat objects, so the core needs no serializer library. */
internal object JsonWriter {
    /** Hex digits for `\u00XX` escapes. */
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Encodes a flat object whose values are strings, longs, doubles, booleans or nested flat objects; keys keep map order. */
    fun encodeObject(fields: Map<String, Any?>): String {
        val jsonBuilder = StringBuilder()
        appendObject(jsonBuilder, fields)

        return jsonBuilder.toString()
    }

    /** Appends one object, skipping null fields. */
    fun appendObject(jsonBuilder: StringBuilder, fields: Map<String, Any?>) {
        jsonBuilder.append('{')

        var isFirstField = true
        for ((fieldName, fieldValue) in fields) {
            // Absent optional fields are left out, never written as null.
            if (fieldValue == null) {
                continue
            }

            if (!isFirstField) {
                jsonBuilder.append(',')
            }

            isFirstField = false
            appendString(jsonBuilder, fieldName)
            jsonBuilder.append(':')
            appendValue(jsonBuilder, fieldValue)
        }

        jsonBuilder.append('}')
    }

    /** Appends one value already reduced to a JSON-safe type. */
    @Suppress("UNCHECKED_CAST")
    private fun appendValue(jsonBuilder: StringBuilder, fieldValue: Any) {
        when (fieldValue) {
            is String -> appendString(jsonBuilder, fieldValue)
            is Boolean -> jsonBuilder.append(if (fieldValue) "true" else "false")
            is Long, is Int -> jsonBuilder.append(fieldValue.toString())
            is Double -> jsonBuilder.append(fieldValue.toString())
            is Map<*, *> -> appendObject(jsonBuilder, fieldValue as Map<String, Any?>)
            else -> appendString(jsonBuilder, fieldValue.toString())
        }
    }

    /** Appends a quoted string, escaping quotes, backslashes and every control character, so one event is always one line. */
    fun appendString(jsonBuilder: StringBuilder, text: String) {
        jsonBuilder.append('"')

        for (character in text) {
            when (character) {
                '"' -> jsonBuilder.append("\\\"")
                '\\' -> jsonBuilder.append("\\\\")
                '\n' -> jsonBuilder.append("\\n")
                '\r' -> jsonBuilder.append("\\r")
                '\t' -> jsonBuilder.append("\\t")
                '\b' -> jsonBuilder.append("\\b")
                '\u000C' -> jsonBuilder.append("\\f")
                ' ', ' ' -> appendUnicodeEscape(jsonBuilder, character)
                else -> {
                    if (character < ' ') {
                        appendUnicodeEscape(jsonBuilder, character)
                    } else {
                        jsonBuilder.append(character)
                    }
                }
            }
        }

        jsonBuilder.append('"')
    }

    // Appends `\uXXXX` for one UTF-16 code unit.
    private fun appendUnicodeEscape(jsonBuilder: StringBuilder, character: Char) {
        val codeUnit = character.code

        jsonBuilder.append("\\u")
        jsonBuilder.append(HEX_DIGITS[codeUnit shr 12 and 0xF])
        jsonBuilder.append(HEX_DIGITS[codeUnit shr 8 and 0xF])
        jsonBuilder.append(HEX_DIGITS[codeUnit shr 4 and 0xF])
        jsonBuilder.append(HEX_DIGITS[codeUnit and 0xF])
    }
}

/** Thrown when text is not valid JSON. */
internal class JsonParseException(message: String) : IllegalArgumentException(message)

/** A small strict JSON reader for the SDK's own files and for validating journal lines. */
internal class JsonReader private constructor(private val text: String) {
    /** The index of the next unread character. */
    private var position = 0

    /** The entry points. */
    companion object {
        /** Parses one JSON document into maps, lists, strings, longs, doubles, booleans and nulls. */
        fun parse(text: String): Any? {
            val reader = JsonReader(text)
            val parsedValue = reader.readValue()

            // Nothing but whitespace may follow the value.
            reader.skipWhitespace()
            if (reader.position != text.length) {
                throw JsonParseException("Unexpected text after the JSON value at ${reader.position}")
            }

            return parsedValue
        }

        /** Parses one JSON object, or returns null when the text is not one. */
        @Suppress("UNCHECKED_CAST")
        fun parseObjectOrNull(text: String): Map<String, Any?>? {
            return try {
                parse(text) as? Map<String, Any?>
            } catch (parseError: JsonParseException) {
                null
            }
        }

        /** True when the text is exactly one JSON object. */
        fun isJsonObject(text: String): Boolean {
            return parseObjectOrNull(text) != null
        }
    }

    // Reads any JSON value at the current position.
    private fun readValue(): Any? {
        skipWhitespace()

        if (position >= text.length) {
            throw JsonParseException("Unexpected end of JSON")
        }

        return when (val character = text[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> {
                if (character == '-' || character in '0'..'9') {
                    readNumber()
                } else {
                    throw JsonParseException("Unexpected character '$character' at $position")
                }
            }
        }
    }

    // Reads `{ "key": value, ... }`.
    private fun readObject(): Map<String, Any?> {
        val parsedObject = LinkedHashMap<String, Any?>()
        position += 1

        // An empty object closes at once.
        skipWhitespace()
        if (peek() == '}') {
            position += 1
            return parsedObject
        }

        // Read key-value pairs until the closing brace.
        while (true) {
            skipWhitespace()
            if (peek() != '"') {
                throw JsonParseException("Expected a key at $position")
            }

            val fieldName = readString()
            skipWhitespace()
            expect(':')
            parsedObject[fieldName] = readValue()
            skipWhitespace()

            when (peek()) {
                ',' -> position += 1
                '}' -> {
                    position += 1
                    return parsedObject
                }
                else -> throw JsonParseException("Expected ',' or '}' at $position")
            }
        }
    }

    // Reads `[ value, ... ]`.
    private fun readArray(): List<Any?> {
        val parsedArray = ArrayList<Any?>()
        position += 1

        // An empty array closes at once.
        skipWhitespace()
        if (peek() == ']') {
            position += 1
            return parsedArray
        }

        // Read values until the closing bracket.
        while (true) {
            parsedArray.add(readValue())
            skipWhitespace()

            when (peek()) {
                ',' -> position += 1
                ']' -> {
                    position += 1
                    return parsedArray
                }
                else -> throw JsonParseException("Expected ',' or ']' at $position")
            }
        }
    }

    // Reads a quoted string, decoding every escape.
    private fun readString(): String {
        expect('"')
        val decodedText = StringBuilder()

        while (true) {
            if (position >= text.length) {
                throw JsonParseException("Unterminated string")
            }

            val character = text[position]
            position += 1

            when {
                character == '"' -> return decodedText.toString()
                character == '\\' -> decodedText.append(readEscape())
                character < ' ' -> throw JsonParseException("Raw control character in a string at ${position - 1}")
                else -> decodedText.append(character)
            }
        }
    }

    // Reads the character after a backslash.
    private fun readEscape(): Char {
        if (position >= text.length) {
            throw JsonParseException("Unterminated escape")
        }

        val escapeCharacter = text[position]
        position += 1

        return when (escapeCharacter) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> throw JsonParseException("Invalid escape '\\$escapeCharacter'")
        }
    }

    // Reads the four hex digits of a `\uXXXX` escape.
    private fun readUnicodeEscape(): Char {
        if (position + 4 > text.length) {
            throw JsonParseException("Short unicode escape")
        }

        val hexDigits = text.substring(position, position + 4)
        val codeUnit = hexDigits.toIntOrNull(16) ?: throw JsonParseException("Invalid unicode escape '$hexDigits'")
        position += 4

        return codeUnit.toChar()
    }

    // Reads a number as a Long when it is integral and fits, otherwise as a Double.
    private fun readNumber(): Any {
        val numberStart = position

        // Optional minus, then the integer part.
        if (peek() == '-') {
            position += 1
        }

        readDigits(isRequired = true)

        // Optional fraction and exponent.
        var isIntegral = true
        if (peek() == '.') {
            position += 1
            readDigits(isRequired = true)
            isIntegral = false
        }

        if (peek() == 'e' || peek() == 'E') {
            position += 1
            if (peek() == '+' || peek() == '-') {
                position += 1
            }

            readDigits(isRequired = true)
            isIntegral = false
        }

        val numberText = text.substring(numberStart, position)
        if (isIntegral) {
            numberText.toLongOrNull()?.let { integralValue -> return integralValue }
        }

        return numberText.toDouble()
    }

    // Reads a run of digits, requiring at least one when asked.
    private fun readDigits(isRequired: Boolean) {
        val digitsStart = position
        while (position < text.length && text[position] in '0'..'9') {
            position += 1
        }

        if (isRequired && position == digitsStart) {
            throw JsonParseException("Expected a digit at $position")
        }
    }

    // Reads an exact literal such as `true`.
    private fun readLiteral(literal: String, literalValue: Any?): Any? {
        if (!text.startsWith(literal, position)) {
            throw JsonParseException("Invalid literal at $position")
        }

        position += literal.length
        return literalValue
    }

    // Consumes one expected character.
    private fun expect(expectedCharacter: Char) {
        if (peek() != expectedCharacter) {
            throw JsonParseException("Expected '$expectedCharacter' at $position")
        }

        position += 1
    }

    // The character at the current position, or a NUL past the end.
    private fun peek(): Char {
        return if (position < text.length) text[position] else '\u0000'
    }

    // Skips JSON whitespace.
    private fun skipWhitespace() {
        while (position < text.length && text[position] in " \t\r\n") {
            position += 1
        }
    }
}

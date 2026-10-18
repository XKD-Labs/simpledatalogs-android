package com.xkdlabs.simpledatalogs

/** Reduces loosely typed property maps to the four JSON types ingest takes. */
internal object Properties {
    /** Keeps strings, whole numbers, finite floating-point numbers and booleans; drops everything else with a log line. */
    fun sanitize(properties: Map<String, Any?>, logger: SdkLogger): Map<String, Any> {
        val validProperties = LinkedHashMap<String, Any>(properties.size)

        for ((propertyKey, propertyValue) in properties) {
            val jsonSafeValue = toJsonSafeValue(propertyValue)

            // Log every dropped key, so the host can fix its call site.
            if (jsonSafeValue == null) {
                logger.warning("Dropped property $propertyKey: ${describeRejection(propertyValue)}")
                continue
            }

            validProperties[propertyKey] = jsonSafeValue
        }

        return validProperties
    }

    /** Merges the defaults under the event's own properties (the event wins), with keys sorted for stable lines. */
    fun merge(defaultProperties: Map<String, Any>, eventProperties: Map<String, Any>): Map<String, Any> {
        val mergedProperties = sortedMapOf<String, Any>()
        mergedProperties.putAll(defaultProperties)
        mergedProperties.putAll(eventProperties)

        return mergedProperties
    }

    // Maps one value to String, Long, Double or Boolean, or null when JSON (or ingest) cannot take it.
    private fun toJsonSafeValue(propertyValue: Any?): Any? {
        return when (propertyValue) {
            is String -> propertyValue
            is Boolean -> propertyValue
            is Int -> propertyValue.toLong()
            is Long -> propertyValue
            is Short -> propertyValue.toLong()
            is Byte -> propertyValue.toLong()
            is Double -> if (propertyValue.isFinite()) propertyValue else null
            is Float -> if (propertyValue.isFinite()) propertyValue.toString().toDouble() else null
            else -> null
        }
    }

    // Explains why a value was dropped.
    private fun describeRejection(propertyValue: Any?): String {
        return when (propertyValue) {
            null -> "null is not a supported value"
            is Double, is Float -> "NaN and infinity are not valid JSON"
            else -> "${propertyValue.javaClass.simpleName} is not a string, number or boolean"
        }
    }
}

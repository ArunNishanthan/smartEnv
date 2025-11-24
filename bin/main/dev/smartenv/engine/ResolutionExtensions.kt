package dev.smartenv.engine

import java.util.LinkedHashMap

fun SmartEnvResolutionResult.flattenedEntries(): List<SmartEnvVariableEntry> =
    flattenedEntryMap().values.toList()

fun SmartEnvResolutionResult.flattenedEntryMap(): LinkedHashMap<String, SmartEnvVariableEntry> {
    val ordered = LinkedHashMap<String, SmartEnvVariableEntry>()
    entries.forEach { entry ->
        ordered[entry.key] = entry
    }
    return ordered
}

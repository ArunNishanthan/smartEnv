package dev.smartenv.logging

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.smartenv.engine.SmartEnvVariableEntry
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class SmartEnvLogsService(private val project: Project) {
    private val entries = ArrayDeque<SmartEnvLogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val maxEntries = 50

    fun record(entry: SmartEnvLogEntry) {
        synchronized(entries) {
            entries.addFirst(entry)
            if (entries.size > maxEntries) {
                entries.removeLast()
            }
        }
        listeners.forEach { it() }
    }

    fun getEntries(): List<SmartEnvLogEntry> {
        synchronized(entries) {
            return entries.toList()
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}

data class SmartEnvLogEntry(
    val timestamp: LocalDateTime,
    val profileId: String,
    val profileName: String,
    val chain: List<String>,
    val variables: List<SmartEnvVariableEntry>
) {
    val summaryTitle: String
        get() = "${timestamp.toLocalTime()} – ${profileName.ifBlank { profileId }} (${variables.size} vars)"
}

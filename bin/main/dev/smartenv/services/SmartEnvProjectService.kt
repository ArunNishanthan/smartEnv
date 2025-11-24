package dev.smartenv.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "SmartEnvProjectState", storages = [Storage("smartenv.xml")])
class SmartEnvProjectService(private val project: Project) : PersistentStateComponent<SmartEnvProjectState> {
    private var state = SmartEnvProjectState()

    override fun getState(): SmartEnvProjectState = state

    override fun loadState(state: SmartEnvProjectState) {
        this.state = state
    }

    fun updateEnabled(enabled: Boolean) {
        state.enabled = enabled
    }

    fun setActiveProfile(profileId: String?) {
        state.activeProfileId = profileId
    }

    fun findProfile(id: String?): SmartEnvProfile? {
        return state.profiles.firstOrNull { it.id == id }
    }

    fun getActiveProfile(): SmartEnvProfile? {
        return findProfile(state.activeProfileId)
    }
}

data class SmartEnvProjectState(
    var enabled: Boolean = true,
    var activeProfileId: String? = null,
    var profiles: MutableList<SmartEnvProfile> = mutableListOf()
)

data class SmartEnvProfile(
    var id: String = "",
    var name: String = "",
    var color: String = "#b67df2",
    var icon: String = "SE",
    var extends: MutableList<String> = mutableListOf(),
    var files: MutableList<SmartEnvFileEntry> = mutableListOf(),
    var showLogsWhenRunning: Boolean = true
)

data class SmartEnvFileEntry(
    var path: String = "",
    var enabled: Boolean = true,
    var type: SmartEnvFileType = SmartEnvFileType.AUTO,
    var jsonMode: SmartEnvJsonMode = SmartEnvJsonMode.FLAT,
    var mode1Key: String? = null,
    var order: Int = 0
)

enum class SmartEnvFileType {
    AUTO,
    DOTENV,
    PROPERTIES,
    YAML,
    JSON,
    TEXT
}

enum class SmartEnvJsonMode {
    BLOB,
    FLAT
}

fun SmartEnvProjectState.deepCopy(): SmartEnvProjectState {
    return SmartEnvProjectState(
        enabled = enabled,
        activeProfileId = activeProfileId,
        profiles = profiles.map { it.deepCopy() }.toMutableList()
    )
}

fun SmartEnvProjectState.findProfileById(id: String?): SmartEnvProfile? {
    return profiles.firstOrNull { it.id == id }
}

fun SmartEnvProfile.deepCopy(): SmartEnvProfile {
    return SmartEnvProfile(
        id = id,
        name = name,
        color = color,
        icon = icon,
        extends = extends.toMutableList(),
        files = files.map { it.deepCopy() }.toMutableList(),
        showLogsWhenRunning = showLogsWhenRunning
    )
}

fun SmartEnvFileEntry.deepCopy(): SmartEnvFileEntry {
    return SmartEnvFileEntry(
        path = path,
        enabled = enabled,
        type = type,
        jsonMode = jsonMode,
        mode1Key = mode1Key,
        order = order
    )
}

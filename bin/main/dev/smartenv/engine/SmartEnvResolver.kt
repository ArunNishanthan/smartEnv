package dev.smartenv.engine

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.smartenv.services.SmartEnvProfile
import dev.smartenv.services.SmartEnvProjectState
import dev.smartenv.services.findProfileById
import java.nio.file.Paths
import java.util.LinkedHashMap

private val LOG = Logger.getInstance(SmartEnvResolver::class.java)
private const val MAX_CHAIN_DEPTH = 5

class SmartEnvResolver(private val fileProcessor: SmartEnvFileProcessor = SmartEnvFileProcessor()) {

    fun resolve(
        project: Project,
        state: SmartEnvProjectState,
        profile: SmartEnvProfile?
    ): SmartEnvResolutionResult {
        if (profile == null) {
            return SmartEnvResolutionResult(LinkedHashMap(), emptyList(), emptyList())
        }

        val basePath = project.basePath?.let { Paths.get(it) }
        val chainProfiles = buildChain(profile, state, mutableSetOf(), 0)
        val mergedProfiles = (chainProfiles + profile).distinctBy { it.id }
        val variables = LinkedHashMap<String, String>()
        val entries = mutableListOf<SmartEnvVariableEntry>()

        for (profileCandidate in mergedProfiles) {
            val activeFiles = profileCandidate.files.withIndex()
                .filter { it.value.enabled }
                .sortedWith(compareBy({ it.value.order }, { it.index }))
                .map { it.value }

            for (fileEntry in activeFiles) {
                val parseResult = fileProcessor.parse(fileEntry, basePath)
                if (!parseResult.success) continue
                for ((key, value) in parseResult.values) {
                    variables[key] = value
                    entries.add(
                        SmartEnvVariableEntry(
                            key = key,
                            value = value,
                            profileId = profileCandidate.id,
                            profileName = profileCandidate.name,
                            filePath = fileEntry.path
                        )
                    )
                }
            }
        }

        val chainNames = mergedProfiles.map { it.name.ifBlank { it.id } }.filter { it.isNotBlank() }
        return SmartEnvResolutionResult(variables, entries, chainNames)
    }

    private fun buildChain(
        profile: SmartEnvProfile,
        state: SmartEnvProjectState,
        seen: MutableSet<String>,
        depth: Int
    ): List<SmartEnvProfile> {
        if (depth >= MAX_CHAIN_DEPTH) {
            LOG.debug("SmartEnv chain depth reach $MAX_CHAIN_DEPTH for profile '${profile.name}'.")
            return emptyList()
        }

        val chain = mutableListOf<SmartEnvProfile>()
        for (parentId in profile.extends) {
            if (parentId.isBlank() || !seen.add(parentId)) {
                continue
            }
            val parent = state.findProfileById(parentId) ?: continue
            chain.addAll(buildChain(parent, state, seen, depth + 1))
            chain.add(parent)
        }
        return chain
    }
}

data class SmartEnvResolutionResult(
    val variables: LinkedHashMap<String, String>,
    val entries: List<SmartEnvVariableEntry>,
    val chain: List<String>
)

data class SmartEnvVariableEntry(
    val key: String,
    val value: String,
    val profileId: String,
    val profileName: String,
    val filePath: String
)

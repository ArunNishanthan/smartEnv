package dev.smartenv.engine

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.smartenv.services.SmartEnvProfile
import dev.smartenv.services.SmartEnvProjectState
import dev.smartenv.services.findProfileById
import java.nio.file.Path
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
            return SmartEnvResolutionResult(
                variables = LinkedHashMap(),
                resolvedKeys = emptyList(),
                chain = emptyList(),
                layers = emptyList()
            )
        }

        val basePath = project.basePath?.let { Paths.get(it) }
        val chainProfiles = resolveProfileChain(profile, state)
        val layers = buildLayers(chainProfiles, basePath)
        val resolvedKeys = flattenLayers(layers)

        val variables = LinkedHashMap<String, String>()
        resolvedKeys.forEach { key -> variables[key.key] = key.finalValue }

        val chainNames = chainProfiles.map { it.name.ifBlank { it.id } }.filter { it.isNotBlank() }
        return SmartEnvResolutionResult(
            variables = variables,
            resolvedKeys = resolvedKeys,
            chain = chainNames,
            layers = layers
        )
    }

    private fun resolveProfileChain(profile: SmartEnvProfile, state: SmartEnvProjectState): List<SmartEnvProfile> {
        val chain = mutableListOf<SmartEnvProfile>()
        val visited = mutableSetOf<String>()
        var depth = 0
        var current: SmartEnvProfile? = profile

        while (current != null && visited.add(current.id)) {
            chain.add(current)
            val parentId = current.parentId?.takeIf { it.isNotBlank() } ?: break
            if (depth >= MAX_CHAIN_DEPTH) {
                LOG.debug("SmartEnv chain depth reached $MAX_CHAIN_DEPTH for profile '${profile.name}'.")
                break
            }
            current = state.findProfileById(parentId)
            depth++
        }
        return chain.asReversed()
    }

    private fun buildLayers(
        chainProfiles: List<SmartEnvProfile>,
        basePath: Path?
    ): List<EnvLayer> {
        val layers = mutableListOf<EnvLayer>()
        var order = 0
        for (profile in chainProfiles) {
            val fileLayers = buildFileLayers(profile, basePath, order)
            layers.addAll(fileLayers)
            order += fileLayers.size

            val customLayer = buildCustomLayer(profile, order)
            if (customLayer != null) {
                layers.add(customLayer)
                order++
            }
        }
        return layers
    }

    private fun buildFileLayers(
        profile: SmartEnvProfile,
        basePath: Path?,
        startOrder: Int
    ): List<EnvLayer> {
        val result = mutableListOf<EnvLayer>()
        var order = startOrder
        profile.files.withIndex()
            .filter { it.value.enabled }
            .sortedWith(compareBy({ it.value.order }, { it.index }))
            .forEach { (index, entry) ->
                val parseResult = fileProcessor.parse(entry, basePath)
                if (!parseResult.success || parseResult.values.isEmpty()) {
                    return@forEach
                }

                result.add(
                    EnvLayer(
                        id = "file:${profile.id}:$index:${entry.path.hashCode()}",
                        profileId = profile.id,
                        profileName = profile.name,
                        type = EnvLayerType.FILE,
                        order = order++,
                        displayName = entry.path,
                        sourceDetail = entry.path,
                        entries = LinkedHashMap(parseResult.values)
                    )
                )
            }
        return result
    }

    private fun buildCustomLayer(profile: SmartEnvProfile, order: Int): EnvLayer? {
        val enabledEntries = profile.customEntries
            .filter { it.enabled && it.key.isNotBlank() }
            .associate { it.key to it.value }
        if (enabledEntries.isEmpty()) {
            return null
        }
        return EnvLayer(
            id = "custom:${profile.id}",
            profileId = profile.id,
            profileName = profile.name,
            type = EnvLayerType.CUSTOM,
            order = order,
            displayName = "${profile.name.ifBlank { profile.id }} custom entries",
            sourceDetail = "Custom entries",
            entries = LinkedHashMap(enabledEntries)
        )
    }

    private fun flattenLayers(layers: List<EnvLayer>): List<ResolvedEnvKey> {
        val history = LinkedHashMap<String, MutableList<LayerValue>>()
        layers.sortedBy { it.order }.forEach { layer ->
            val source = layer.toLayerSource()
            layer.entries.forEach { (key, value) ->
                if (key.isBlank()) return@forEach
                val stack = history.getOrPut(key) { mutableListOf() }
                stack.add(LayerValue(source, value))
            }
        }
        return history.map { (key, stack) ->
            ResolvedEnvKey(
                key = key,
                finalValue = stack.last().value,
                stack = stack.toList()
            )
        }
    }

    private fun EnvLayer.toLayerSource(): LayerSource {
        return LayerSource(
            layerId = id,
            profileId = profileId,
            profileName = profileName,
            layerName = displayName,
            layerType = type,
            sourceDetail = sourceDetail
        )
    }
}

data class SmartEnvResolutionResult(
    val variables: LinkedHashMap<String, String>,
    val resolvedKeys: List<ResolvedEnvKey>,
    val chain: List<String>,
    val layers: List<EnvLayer>
)

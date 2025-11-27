package dev.smartenv.engine

enum class EnvLayerType {
    FILE,
    CUSTOM
}

data class EnvLayer(
    val id: String,
    val profileId: String,
    val profileName: String,
    val type: EnvLayerType,
    val order: Int,
    val displayName: String,
    val sourceDetail: String?,
    val entries: Map<String, String>
)

data class LayerSource(
    val layerId: String,
    val profileId: String,
    val profileName: String,
    val layerName: String,
    val layerType: EnvLayerType,
    val sourceDetail: String?
)

data class LayerValue(
    val source: LayerSource,
    val value: String
)

data class ResolvedEnvKey(
    val key: String,
    val finalValue: String,
    val stack: List<LayerValue>
) {
    val hasOverrides: Boolean
        get() = stack.size > 1

    val winningLayer: LayerSource
        get() = stack.last().source
}

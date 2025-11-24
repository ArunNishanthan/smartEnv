package dev.smartenv.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.diagnostic.Logger
import dev.smartenv.services.SmartEnvFileEntry
import dev.smartenv.services.SmartEnvFileType
import dev.smartenv.services.SmartEnvJsonMode
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import java.util.Locale

private val LOG = Logger.getInstance(SmartEnvFileProcessor::class.java)

data class SmartEnvFileParseResult(
    val values: LinkedHashMap<String, String>,
    val success: Boolean,
    val previewText: String?,
    val sourceType: SmartEnvFileType?,
    val note: String? = null
)

class SmartEnvFileProcessor {
    private val yaml = Yaml()
    private val objectMapper = jacksonObjectMapper()

    fun parse(entry: SmartEnvFileEntry, basePath: Path?): SmartEnvFileParseResult {
        val resolvedPath = resolvePath(basePath, entry.path)
        if (resolvedPath == null || !Files.exists(resolvedPath)) {
            LOG.debug("SmartEnv file not found: ${entry.path}")
            return SmartEnvFileParseResult(LinkedHashMap(), false, null, null, "Missing file")
        }
        val typeCandidates = determineTypeSequence(entry, resolvedPath)
        for (type in typeCandidates) {
            val attempt = when (type) {
                SmartEnvFileType.DOTENV,
                SmartEnvFileType.PROPERTIES,
                SmartEnvFileType.TEXT -> tryParseProperties(resolvedPath)
                SmartEnvFileType.YAML -> tryParseYaml(resolvedPath)
                SmartEnvFileType.JSON -> tryParseJson(resolvedPath, entry)
                SmartEnvFileType.AUTO -> ParseAttempt(LinkedHashMap(), false, null)
            }
            if (attempt.success) {
                return SmartEnvFileParseResult(attempt.values, true, attempt.preview, type, attempt.note)
            }
        }
        return SmartEnvFileParseResult(LinkedHashMap(), false, null, null, "Unparsable")
    }

    private fun resolvePath(basePath: Path?, entryPath: String): Path? {
        val candidate = Paths.get(entryPath)
        return when {
            candidate.isAbsolute -> candidate
            basePath != null -> basePath.resolve(candidate).normalize()
            else -> null
        }
    }

    private fun determineTypeSequence(entry: SmartEnvFileEntry, path: Path): List<SmartEnvFileType> {
        if (entry.type != SmartEnvFileType.AUTO) {
            return listOf(entry.type)
        }
        val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        val mapped = when (extension) {
            "env" -> SmartEnvFileType.DOTENV
            "properties" -> SmartEnvFileType.PROPERTIES
            "yaml", "yml" -> SmartEnvFileType.YAML
            "json" -> SmartEnvFileType.JSON
            "txt" -> SmartEnvFileType.TEXT
            else -> null
        }
        if (mapped != null) {
            return listOf(mapped)
        }

        // Unknown extension: fall back to JSON blob mode for predictable ingestion.
        entry.jsonMode = SmartEnvJsonMode.BLOB
        return listOf(SmartEnvFileType.JSON)
    }

    private fun tryParseProperties(path: Path): ParseAttempt {
        return try {
            val lines = Files.readAllLines(path)
            val map = LinkedHashMap<String, String>()
            for (line in lines) {
                parseKeyValueLine(line)?.let { (key, value) ->
                    map[key] = value
                }
            }
            ParseAttempt(map, map.isNotEmpty(), map.entries.joinToString("\n") { "${it.key} = ${it.value}" }, "Properties")
        } catch (exc: Exception) {
            LOG.debug("SmartEnv failed to parse properties/dotenv file $path: ${exc.message}")
            ParseAttempt(LinkedHashMap(), false, null, "Failed to parse properties")
        }
    }

    private fun tryParseYaml(path: Path): ParseAttempt {
        return try {
            val content = Files.readString(path)
            val loaded = yaml.load<Any>(content)
            val map = LinkedHashMap<String, String>()
            flattenYaml(loaded, null, map)
            ParseAttempt(map, map.isNotEmpty(), yaml.dump(loaded).trim(), "YAML")
        } catch (exc: Exception) {
            LOG.debug("SmartEnv failed to parse YAML file $path: ${exc.message}")
            ParseAttempt(LinkedHashMap(), false, null, "Failed to parse YAML")
        }
    }

    private fun tryParseJson(path: Path, entry: SmartEnvFileEntry): ParseAttempt {
        return try {
            val text = Files.readString(path)
            val node = objectMapper.readTree(text)
            when (entry.jsonMode) {
                SmartEnvJsonMode.BLOB -> {
                    val key = deriveMode1Key(entry, path)
                    val map = linkedMapOf(key to objectMapper.writeValueAsString(node))
                    val preview = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                    ParseAttempt(map, true, preview, "JSON Mode 1")
                }
                SmartEnvJsonMode.FLAT -> {
                    val map = LinkedHashMap<String, String>()
                    flattenJson(node, null, map)
                    val preview = map.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                    ParseAttempt(map, true, preview, "JSON Mode 2")
                }
            }
        } catch (exc: Exception) {
            LOG.debug("SmartEnv failed to parse JSON file $path: ${exc.message}")
            ParseAttempt(LinkedHashMap(), false, null, "Failed to parse JSON")
        }
    }

    private fun flattenYaml(value: Any?, prefix: String?, target: MutableMap<String, String>) {
        when (value) {
            is Map<*, *> -> {
                for ((rawKey, rawValue) in value) {
                    val key = rawKey?.toString()?.trim() ?: continue
                    flattenYaml(rawValue, buildKey(prefix, key), target)
                }
            }
            is Iterable<*> -> {
                value.forEachIndexed { index, element ->
                    flattenYaml(element, buildKey(prefix, index.toString()), target)
                }
            }
            else -> {
                val key = prefix ?: "value"
                target[key] = serializeValue(value)
            }
        }
    }

    private fun flattenJson(node: JsonNode, prefix: String?, target: MutableMap<String, String>) {
        when {
            node.isObject -> {
                val fields = node.fields()
                while (fields.hasNext()) {
                    val (field, value) = fields.next()
                    flattenJson(value, buildKey(prefix, field), target)
                }
            }
            node.isArray -> {
                for (index in 0 until node.size()) {
                    flattenJson(node[index], buildKey(prefix, index.toString()), target)
                }
            }
            else -> {
                val key = prefix ?: "value"
                target[key] = jsonNodeToString(node)
            }
        }
    }

    private fun serializeValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value
            else -> objectMapper.writeValueAsString(value)
        }
    }

    private fun jsonNodeToString(node: JsonNode): String {
        return when {
            node.isTextual -> node.textValue()
            node.isNumber -> node.numberValue().toString()
            node.isBoolean -> node.booleanValue().toString()
            node.isNull -> "null"
            else -> objectMapper.writeValueAsString(node)
        }
    }

    private fun buildKey(prefix: String?, segment: String): String {
        return if (prefix.isNullOrBlank()) segment else "$prefix.$segment"
    }

    private fun deriveMode1Key(entry: SmartEnvFileEntry, path: Path): String {
        val trimmed = entry.mode1Key?.takeIf { it.isNotBlank() }
            ?: path.fileName?.toString()?.substringBeforeLast('.', path.fileName.toString()).orEmpty()
        return trimmed.ifBlank { "json" }
    }

    private fun parseKeyValueLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
            return null
        }
        val idx = trimmed.indexOf('=').takeIf { it >= 0 } ?: trimmed.indexOf(':').takeIf { it >= 0 } ?: return null
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isEmpty()) {
            return null
        }
        return key to value
    }

    private data class ParseAttempt(
        val values: LinkedHashMap<String, String>,
        val success: Boolean,
        val preview: String?,
        val note: String? = null
    )
}

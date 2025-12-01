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
        val pathInfo = resolvePath(basePath, entry.path)
        val resolvedPath = pathInfo?.path
        if (resolvedPath == null || !Files.exists(resolvedPath)) {
            LOG.debug("SmartEnv file not found: ${entry.path}")
            val note = buildMissingFileNote(entry.path, pathInfo)
            return SmartEnvFileParseResult(LinkedHashMap(), false, null, null, note)
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

    private fun resolvePath(basePath: Path?, entryPath: String): ResolvedPath? {
        val candidate = try {
            Paths.get(entryPath)
        } catch (exc: Exception) {
            LOG.debug("SmartEnv invalid path '$entryPath': ${exc.message}")
            return null
        }
        val wasRelative = !candidate.isAbsolute
        val resolved = when {
            !wasRelative -> candidate.normalize()
            basePath != null -> basePath.resolve(candidate).normalize()
            else -> candidate.toAbsolutePath().normalize()
        }
        return ResolvedPath(resolved, basePath == null, wasRelative)
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
            ParseAttempt(map, true, map.entries.joinToString("\n") { "${it.key} = ${it.value}" }, "Properties")
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
            ParseAttempt(map, true, yaml.dump(loaded).trim(), "YAML")
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
            ?: path.fileName?.toString().orEmpty()
        return trimmed.ifBlank { "json" }
    }

    private fun parseKeyValueLine(line: String): Pair<String, String>? {
        var trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
            return null
        }
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.removePrefix("export").trimStart()
        }
        val idx = trimmed.indexOf('=').takeIf { it >= 0 } ?: trimmed.indexOf(':').takeIf { it >= 0 } ?: return null
        val key = trimmed.substring(0, idx).trim()
        val rawValuePortion = trimmed.substring(idx + 1)
        val value = stripInlineComment(rawValuePortion).trim().unquote()
        if (key.isEmpty()) {
            return null
        }
        return key to value
    }

    private fun stripInlineComment(segment: String): String {
        var quoteChar: Char? = null
        for (i in segment.indices) {
            val ch = segment[i]
            if (ch == '"' || ch == '\'') {
                quoteChar = when {
                    quoteChar == null -> ch
                    quoteChar == ch -> null
                    else -> quoteChar
                }
                continue
            }
            if (quoteChar == null && (ch == '#' || ch == ';')) {
                return segment.substring(0, i)
            }
        }
        return segment
    }

    private fun String.unquote(): String {
        if (length >= 2 && (startsWith("\"") && endsWith("\"") || startsWith("'") && endsWith("'"))) {
            return substring(1, length - 1)
        }
        return this
    }

    private fun buildMissingFileNote(originalPath: String, resolved: ResolvedPath?): String {
        val display = resolved?.path?.toString()?.ifBlank { originalPath } ?: originalPath
        return if (resolved?.baseMissing == true && resolved.wasRelative) {
            "Missing file: $display (project base path unavailable; relative path may be wrong)"
        } else {
            "Missing file: $display"
        }
    }

    private data class ResolvedPath(val path: Path, val baseMissing: Boolean, val wasRelative: Boolean)

    private data class ParseAttempt(
        val values: LinkedHashMap<String, String>,
        val success: Boolean,
        val preview: String?,
        val note: String? = null
    )
}

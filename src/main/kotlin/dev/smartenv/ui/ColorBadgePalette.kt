package dev.smartenv.ui

import com.intellij.ui.ColorUtil
import java.awt.Color
import java.util.Locale

object ColorBadgePalette {
    private val presetBorders = mapOf(
        "#ff2d95" to "#5c0c31",
        "#b67df2" to "#3f1c73",
        "#00f5ff" to "#04505a",
        "#9ef01a" to "#2d4700",
        "#ffbd00" to "#5d3a00",
        "#ff0054" to "#520018",
        "#74c0fc" to "#1a4a73",
        "#008c8c" to "#b7f7f7",
        "#a29bfe" to "#3c3173",
        "#ff7f51" to "#6b2813"
    )

    fun borderColorForHex(hex: String?, fillColor: Color?): Color {
        val normalized = normalize(hex)
        val preset = normalized?.let { presetBorders[it] }?.let { ColorUtil.fromHex(it.removePrefix("#")) }
        if (preset != null) {
            return preset
        }
        val fallback = fillColor ?: return Color(0x20, 0x20, 0x20)
        return if (ColorUtil.isDark(fallback)) {
            mixWith(fallback, Color.WHITE, 0.4f)
        } else {
            mixWith(fallback, Color(0x12, 0x12, 0x12), 0.35f)
        }
    }

    fun normalize(hex: String?): String? {
        val cleaned = hex?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val ensured = if (cleaned.startsWith("#")) cleaned else "#$cleaned"
        return ensured.lowercase(Locale.ENGLISH)
    }
}

private fun mixWith(base: Color, blend: Color, ratio: Float): Color {
    val clamped = ratio.coerceIn(0f, 1f)
    val r = (base.red + ((blend.red - base.red) * clamped)).toInt().coerceIn(0, 255)
    val g = (base.green + ((blend.green - base.green) * clamped)).toInt().coerceIn(0, 255)
    val b = (base.blue + ((blend.blue - base.blue) * clamped)).toInt().coerceIn(0, 255)
    return Color(r, g, b)
}

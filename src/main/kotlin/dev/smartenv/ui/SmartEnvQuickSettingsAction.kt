package dev.smartenv.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import dev.smartenv.services.SmartEnvProfile
import dev.smartenv.services.SmartEnvProjectService
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JComponent

class SmartEnvQuickSettingsAction : ComboBoxAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        val presentation = e.presentation

        if (project == null) {
            presentation.isEnabled = false
            presentation.icon = AllIcons.Nodes.Tag
            presentation.text = "SmartEnv"
            return
        }

        val service = project.getService(SmartEnvProjectService::class.java)
        if (service == null) {
            presentation.isEnabled = false
            presentation.icon = AllIcons.Nodes.Tag
            presentation.text = "SmartEnv"
            return
        }

        val state = service.getState()
        val profile = service.getActiveProfile()
        presentation.isEnabled = true
        val baseIcon = profile?.colorIconOrNull() ?: AllIcons.Nodes.Tag
        presentation.icon = if (state.enabled) baseIcon else IconLoader.getDisabledIcon(baseIcon)
        presentation.text = when {
            !state.enabled -> "SmartEnv (off)"
            profile != null -> profile.name.ifBlank { profile.id }
            else -> "Select SmartEnv Profile"
        }
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        return buildGroup(CommonDataKeys.PROJECT.getData(dataContext))
    }

    private fun buildGroup(project: Project?): DefaultActionGroup {
        val service = project?.getService(SmartEnvProjectService::class.java) ?: return DefaultActionGroup()
        val state = service.getState()
        val group = DefaultActionGroup()

        group.add(object : ToggleAction("Enabled") {
            override fun isSelected(e: AnActionEvent): Boolean = state.enabled
            override fun setSelected(e: AnActionEvent, enabled: Boolean) {
                service.updateEnabled(enabled)
            }
        })

        group.addSeparator("Profiles")

        if (state.profiles.isEmpty()) {
            group.add(object : AnAction("No profiles configured") {
                override fun actionPerformed(e: AnActionEvent) {}
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = false
                }
            })
        } else {
            state.profiles.forEach { profile ->
                group.add(object : ToggleAction(profile.name.ifBlank { profile.id }) {
                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isEnabled = state.enabled
                    }

                    override fun isSelected(e: AnActionEvent): Boolean = state.activeProfileId == profile.id
                    override fun setSelected(e: AnActionEvent, selected: Boolean) {
                        if (selected) {
                            service.setActiveProfile(profile.id)
                        }
                    }
                })
            }
        }

        group.addSeparator()
        group.add(object : AnAction("Open SmartEnv Settings…") {
            override fun actionPerformed(e: AnActionEvent) {
                project?.let {
                    ShowSettingsUtil.getInstance().showSettingsDialog(it, SmartEnvSettingsConfigurable::class.java)
                }
            }
        })

        return group
    }
}

private fun SmartEnvProfile.colorIconOrNull(): Icon? {
    val color = color.parseColorOrNull() ?: return null
    return ProfileColorIcon(color)
}

private fun String?.parseColorOrNull(): Color? {
    val hex = this?.trim()?.removePrefix("#").orEmpty()
    if (hex.isEmpty()) return null
    return runCatching { ColorUtil.fromHex(hex) }.getOrNull()
}

private class ProfileColorIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = 12
    override fun getIconHeight(): Int = 12

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        val g2 = (g as? Graphics2D)?.create() as? Graphics2D ?: return
        try {
            g2.color = color
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.fillOval(x, y, iconWidth, iconHeight)
        } finally {
            g2.dispose()
        }
    }
}

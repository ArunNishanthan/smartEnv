package dev.smartenv.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartenv.logging.SmartEnvLogEntry
import dev.smartenv.logging.SmartEnvLogsService
import javax.swing.DefaultListModel
import javax.swing.ListCellRenderer
import javax.swing.JPanel
import java.awt.BorderLayout
import com.intellij.ui.content.ContentFactory
import javax.swing.ListSelectionModel

class SmartEnvLogsToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SmartEnvLogsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(toolWindow.disposable, panel)
    }
}

private class SmartEnvLogsPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.getService(SmartEnvLogsService::class.java)
    private val entriesModel = DefaultListModel<SmartEnvLogEntry>()
    private val entryList = JBList(entriesModel)
    private val detailArea = JBTextArea()
    private val listener = {
        ApplicationManager.getApplication().invokeLater { refreshEntries() }
    }

    init {
        border = JBUI.Borders.empty(6)
        entryList.cellRenderer = SmartEnvLogCellRenderer()
        entryList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        entryList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                showDetails(entryList.selectedValue)
            }
        }
        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.wrapStyleWord = true
        detailArea.background = UIUtil.getPanelBackground()

        add(JBLabel("Recent SmartEnv injections"), BorderLayout.NORTH)
        add(JBScrollPane(entryList), BorderLayout.WEST)
        add(JBScrollPane(detailArea), BorderLayout.CENTER)

        service.addListener(listener)
        refreshEntries()
    }

    private fun refreshEntries() {
        entriesModel.removeAllElements()
        service.getEntries().forEach { entriesModel.addElement(it) }
        if (!entriesModel.isEmpty) {
            entryList.selectedIndex = 0
        }
    }

    private fun showDetails(entry: SmartEnvLogEntry?) {
        detailArea.text = entry?.let {
            buildString {
                append("Profile: ${it.profileName} (${it.profileId})\n")
                append("Chain: ${it.chain.joinToString(" -> ")}\n")
                append("Injected ${it.variables.size} variable(s)\n\n")
                it.variables.forEach { variable ->
                    val winningLayer = variable.winningLayer
                    val sourceLabel = winningLayer.sourceDetail ?: winningLayer.layerName
                    append("${variable.key} = ${variable.finalValue} ($sourceLabel)\n")
                    if (variable.hasOverrides) {
                        append("  Sources:\n")
                        variable.stack.forEachIndexed { idx, layerValue ->
                            val label = layerValue.source.layerName
                            append("    ${idx + 1}. $label = ${layerValue.value}\n")
                        }
                    }
                    append("\n")
                }
            }
        } ?: ""
    }

    override fun dispose() {
        service.removeListener(listener)
    }
}

private class SmartEnvLogCellRenderer : JBLabel(), ListCellRenderer<SmartEnvLogEntry> {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<out SmartEnvLogEntry>?,
        value: SmartEnvLogEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ): java.awt.Component {
        text = value?.summaryTitle ?: "No entries"
        border = JBUI.Borders.empty(4)
        background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        foreground = if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
        isOpaque = true
        return this
    }
}

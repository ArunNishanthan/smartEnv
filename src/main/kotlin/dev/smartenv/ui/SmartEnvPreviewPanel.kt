package dev.smartenv.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.smartenv.engine.LayerSource
import dev.smartenv.engine.ResolvedEnvKey
import dev.smartenv.engine.SmartEnvResolutionResult
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

class SmartEnvPreviewPanel(private val project: Project) {
    private val searchField = SearchTextField()
    private val overridesOnlyToggle = JBCheckBox("Show only keys with overrides")
    private val countLabel = JBLabel("0 keys")
    private val tableModel = PreviewTableModel()
    private val table = JBTable(tableModel)
    private val detailArea = JBTextArea()

    private val borderColor: JBColor = JBColor.namedColor("Component.borderColor", java.awt.Color(0xDCDCDC))

    private val rootPanel = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty()
        add(createToolbar(), BorderLayout.NORTH)
        add(createTableComponent(), BorderLayout.CENTER)
        add(createDetailsComponent(), BorderLayout.SOUTH)
    }

    private var allKeys: List<ResolvedEnvKey> = emptyList()

    init {
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        overridesOnlyToggle.addActionListener { applyFilter() }
        detailArea.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(6)
        }
        table.apply {
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            setAutoCreateRowSorter(true)
            selectionModel.addListSelectionListener { event ->
                if (!event.valueIsAdjusting) {
                    updateDetailsFromSelection()
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        currentSelection()?.let { showOverrideDialog(it) }
                    }
                }
            })
        }
        configureColumns()
    }

    val component: JPanel
        get() = rootPanel

    fun setResolution(result: SmartEnvResolutionResult?) {
        if (result == null) {
            allKeys = emptyList()
            detailArea.text = "No profile selected."
            countLabel.text = "0 keys"
            tableModel.update(emptyList())
            return
        }
        allKeys = result.resolvedKeys
        countLabel.text = "${allKeys.size} key(s)"
        applyFilter()
        if (table.rowCount > 0) {
            table.setRowSelectionInterval(0, 0)
        } else {
            detailArea.text = "No environment keys were resolved."
        }
    }

    private fun createToolbar(): JPanel {
        return JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(searchField, BorderLayout.CENTER)
            val rightPanel = JPanel(BorderLayout(8, 0)).apply {
                border = JBUI.Borders.empty()
                add(overridesOnlyToggle, BorderLayout.CENTER)
                add(countLabel, BorderLayout.EAST)
            }
            add(rightPanel, BorderLayout.EAST)
            searchField.textEditor.emptyText.text = "Search keys..."
        }
    }

    private fun createTableComponent(): JBScrollPane {
        return JBScrollPane(table).apply {
            preferredSize = Dimension(0, 240)
            border = JBUI.Borders.customLine(borderColor, 1, 1, 1, 1)
        }
    }

    private fun createDetailsComponent(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(JBLabel("Override stack"), BorderLayout.NORTH)
            add(JBScrollPane(detailArea).apply {
                preferredSize = Dimension(0, 160)
                border = JBUI.Borders.customLine(borderColor, 1, 1, 1, 1)
            }, BorderLayout.CENTER)
        }
    }

    private fun applyFilter() {
        val query = searchField.text?.trim()?.lowercase() ?: ""
        val overridesOnly = overridesOnlyToggle.isSelected
        val filtered = allKeys.filter { key ->
            val match = query.isBlank() ||
                key.key.lowercase().contains(query) ||
                key.finalValue.lowercase().contains(query)
            val overrideCheck = !overridesOnly || key.hasOverrides
            match && overrideCheck
        }
        tableModel.update(filtered)
        countLabel.text = "${filtered.size} key(s)"
        if (filtered.isEmpty()) {
            detailArea.text = "No environment keys match the current filters."
        }
    }

    private fun updateDetailsFromSelection() {
        val key = currentSelection()
        if (key == null) {
            detailArea.text = "Select a row to see its override sources."
            return
        }
        detailArea.text = buildString {
            append("Final value: ${key.finalValue}\n")
            append("From: ${formatLayerLabel(key.winningLayer)}\n\n")
            append("Sources (lowest -> highest):\n")
            key.stack.forEachIndexed { index, layerValue ->
                append("${index + 1}. ${formatLayerLabel(layerValue.source)} = ${layerValue.value}\n")
            }
        }
    }

    private fun currentSelection(): ResolvedEnvKey? {
        val viewIndex = table.selectedRow.takeIf { it >= 0 } ?: return null
        val modelIndex = table.convertRowIndexToModel(viewIndex)
        return tableModel.rows.getOrNull(modelIndex)
    }

    private fun configureColumns() {
        val columnModel = table.columnModel
        if (columnModel.columnCount < 4) return
        columnModel.getColumn(0).preferredWidth = 220
        columnModel.getColumn(1).preferredWidth = 280
        columnModel.getColumn(2).preferredWidth = 220
        columnModel.getColumn(3).preferredWidth = 140
    }

    private fun showOverrideDialog(key: ResolvedEnvKey) {
        val message = buildString {
            append("Key: ${key.key}\n")
            append("Final value: ${key.finalValue}\n")
            append("From: ${formatLayerLabel(key.winningLayer)}\n\n")
            append("Sources:\n")
            key.stack.forEachIndexed { idx, layerValue ->
                append("${idx + 1}. ${formatLayerLabel(layerValue.source)} = ${layerValue.value}\n")
            }
        }
        Messages.showInfoMessage(project, message, "SmartEnv Override Details")
    }

    private fun formatLayerLabel(source: LayerSource): String {
        val profileName = source.profileName.ifBlank { source.profileId }
        return "${profileName.ifBlank { source.profileId }} • ${source.layerName}"
    }

    private inner class PreviewTableModel : AbstractTableModel() {
        private val columns = arrayOf("Key", "Value", "From", "Details")
        var rows: List<ResolvedEnvKey> = emptyList()
            private set

        override fun getColumnName(column: Int): String = columns[column]

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = rows[rowIndex]
            return when (columnIndex) {
                0 -> item.key
                1 -> item.finalValue
                2 -> formatLayerLabel(item.winningLayer)
                else -> if (item.hasOverrides) "${item.stack.size} layer(s)" else "Single source"
            }
        }

        fun update(newRows: List<ResolvedEnvKey>) {
            rows = newRows
            fireTableDataChanged()
        }
    }
}

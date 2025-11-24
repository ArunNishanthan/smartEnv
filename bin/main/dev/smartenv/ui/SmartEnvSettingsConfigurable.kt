package dev.smartenv.ui

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import dev.smartenv.engine.SmartEnvResolver
import dev.smartenv.engine.flattenedEntryMap
import dev.smartenv.services.SmartEnvFileEntry
import dev.smartenv.services.SmartEnvFileType
import dev.smartenv.services.SmartEnvJsonMode
import dev.smartenv.services.SmartEnvProfile
import dev.smartenv.services.SmartEnvProjectService
import dev.smartenv.services.deepCopy
import dev.smartenv.services.findProfileById
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.RenderingHints
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SmartEnvSettingsConfigurable(private val project: Project) : SearchableConfigurable {
    private val service by lazy { project.getService(SmartEnvProjectService::class.java)!! }
    private val resolver = SmartEnvResolver()
    private var stateSnapshot = service.getState().deepCopy()
    private var mainPanel: JPanel? = null

    private val profilesModel = DefaultListModel<SmartEnvProfile>()
    private val profilesList = JBList(profilesModel)
    private val filesTableModel = ListTableModel<SmartEnvFileEntry>(
        FileEnabledColumn(),
        FilePathColumn(),
        FileFormatColumn(),
        FileKeyColumn()
    )
    private val filesTable = TableView<SmartEnvFileEntry>(filesTableModel)

    private lateinit var enabledCheckBox: JBCheckBox
    private lateinit var profileNameField: JBTextField
    private val colorChoices = mutableListOf(
        ColorChoice("Neon Pink", "#ff2d95"),
        ColorChoice("Electric Purple", "#b67df2"),
        ColorChoice("Cyan Pulse", "#00f5ff"),
        ColorChoice("Lime Burst", "#9ef01a"),
        ColorChoice("Amber Glow", "#ffbd00"),
        ColorChoice("Magenta Shock", "#ff0054"),
        ColorChoice("Arctic Blue", "#74c0fc"),
        ColorChoice("Steel Teal", "#008c8c"),
        ColorChoice("Violet Beam", "#a29bfe"),
        ColorChoice("Sunset Peach", "#ff7f51")
    )
    private val colorComboModel = DefaultComboBoxModel<ColorChoice>(colorChoices.toTypedArray())
    private lateinit var colorCombo: JComboBox<ColorChoice>
    private lateinit var showLogsCheckBox: JBCheckBox
    private lateinit var activeProfileLabel: JLabel
    private lateinit var setActiveButton: JButton
    private lateinit var previewArea: JBTextArea
    private lateinit var previewContainer: JPanel
    private lateinit var previewToggle: JToggleButton


    private var selectedProfile: SmartEnvProfile? = null
    private var dirty = false
    private var suppressFieldUpdates = false

    override fun getId(): String = "dev.smartenv.settings"

    override fun getDisplayName(): String = "SmartEnv"

    override fun createComponent(): JComponent {
        if (mainPanel != null) {
            reloadState()
            return mainPanel!!
        }

        enabledCheckBox = JBCheckBox("Enable SmartEnv", stateSnapshot.enabled).apply {
            addActionListener {
                stateSnapshot.enabled = isSelected
                markModified()
            }
        }

        setupProfilesList()
        activeProfileLabel = JLabel("Active profile: None").apply {
            foreground = UIUtil.getLabelDisabledForeground()
        }

        activeProfileLabel = JLabel()

        val leftPanel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(220, 0)
            border = JBUI.Borders.empty(6)
            add(createProfileToolbar(), BorderLayout.NORTH)
            add(JBScrollPane(profilesList), BorderLayout.CENTER)
        }

        profileNameField = JBTextField().apply {
            columns = 16
            document.addDocumentListener(simpleDocumentListener {
                if (suppressFieldUpdates) return@simpleDocumentListener
                selectedProfile?.let {
                    it.name = text.trim()
                    markModified()
                    profilesList.repaint()
                }
            })
        }

        colorCombo = JComboBox<ColorChoice>(colorComboModel).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val display = (value as? ColorChoice)
                    val component = super.getListCellRendererComponent(
                        list,
                        display?.label ?: "",
                        index,
                        isSelected,
                        cellHasFocus
                    ) as JLabel
                    component.icon = display?.hex?.parseColor()?.let { ColorSwatchIcon(it) }
                    val fg = list?.let { if (isSelected) it.selectionForeground else UIUtil.getLabelForeground() }
                        ?: UIUtil.getLabelForeground()
                    component.foreground = fg
                    return component
                }
            }
            toolTipText = "Pick one of the SmartEnv accent colors"
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener {
                if (suppressFieldUpdates) return@addActionListener
                val choice = selectedItem as? ColorChoice ?: return@addActionListener
                selectedProfile?.let {
                    it.color = choice.hex
                    markModified()
                    profilesList.repaint()
                }
            }
        }

        showLogsCheckBox = JBCheckBox("Auto-open SmartEnv Logs when running").apply {
            addActionListener {
                if (suppressFieldUpdates) return@addActionListener
                selectedProfile?.let {
                    it.showLogsWhenRunning = isSelected
                    markModified()
                }
            }
        }

        previewArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getPanelBackground()
            rows = 12
        }
        val centerPanel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(6)
            add(createProfileDetailPanel(), BorderLayout.NORTH)
            add(createFilesPanel(), BorderLayout.CENTER)
        }

        setActiveButton = JButton("Set Active").apply {
            addActionListener {
                selectedProfile?.let {
                    stateSnapshot.activeProfileId = it.id
                    markModified()
                    updateActiveProfileLabel()
                }
            }
        }

        val headerPanel = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(10, 10, 4, 10)
            add(enabledCheckBox, BorderLayout.WEST)
            val right = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(activeProfileLabel)
                add(Box.createHorizontalStrut(8))
                add(setActiveButton)
            }
            add(right, BorderLayout.EAST)
        }

        mainPanel = JPanel(BorderLayout(10, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(headerPanel, BorderLayout.NORTH)
            add(leftPanel, BorderLayout.WEST)
            add(centerPanel, BorderLayout.CENTER)
        }

        reloadState()
        return mainPanel!!
    }

    private fun setupProfilesList() {
        profilesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        profilesList.cellRenderer = object : ColoredListCellRenderer<SmartEnvProfile>() {
            override fun customizeCellRenderer(
                list: JList<out SmartEnvProfile>,
                value: SmartEnvProfile?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) {
                    append("No profile", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    return
                }
                val swatchAttr = value.color.parseColor()?.let {
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, it)
                } ?: SimpleTextAttributes.GRAYED_ATTRIBUTES
                append("● ", swatchAttr)
                val colorAttr = SimpleTextAttributes.REGULAR_ATTRIBUTES
                val displayName = value.name.ifBlank { value.id.ifBlank { "Unnamed" } }
                append(displayName, colorAttr)
            }
        }
        profilesList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectProfile(profilesList.selectedValue)
            }
        }
    }

    private fun createProfileToolbar(): JPanel {
        val addButton = iconButton(AllIcons.General.Add, "Add profile") {
            val profile = SmartEnvProfile(
                id = "profile_${System.currentTimeMillis()}",
                name = "Profile ${profilesModel.size + 1}"
            )
            stateSnapshot.profiles.add(profile)
            profilesModel.addElement(profile)
            profilesList.setSelectedValue(profile, true)
            markModified()
            refreshPreview()
        }
        val removeButton = iconButton(AllIcons.General.Remove, "Remove selected profile") {
            val candidate = selectedProfile ?: return@iconButton
            if (profilesModel.size <= 1) return@iconButton
            profilesModel.removeElement(candidate)
            stateSnapshot.profiles.remove(candidate)
            if (stateSnapshot.activeProfileId == candidate.id) {
                stateSnapshot.activeProfileId = stateSnapshot.profiles.firstOrNull()?.id
            }
            selectedProfile = profilesModel.firstElementOrNull()
            profilesList.setSelectedValue(selectedProfile, true)
            markModified()
            refreshPreview()
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(8)
            add(addButton)
            add(Box.createHorizontalStrut(4))
            add(removeButton)
            add(Box.createHorizontalGlue())
        }
    }

    private fun createProfileDetailPanel(): JPanel {
        val badgeHint = JLabel("Color shows in Quick Settings & SmartEnv Logs").apply {
            foreground = UIUtil.getLabelDisabledForeground()
        }

        val basicsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Profile Name:", profileNameField, 1, false)
            .addLabeledComponent("Accent Color:", colorCombo, 1, false)
            .addComponent(badgeHint)
            .panel.apply { border = JBUI.Borders.empty(0, 0, 12, 0) }

        val behaviorPanel = FormBuilder.createFormBuilder()
            .addComponent(showLogsCheckBox)
            .panel

        return JPanel(BorderLayout()).apply {
            add(basicsPanel, BorderLayout.NORTH)
            add(behaviorPanel, BorderLayout.CENTER)
        }
    }

    private fun createFilesPanel(): JPanel {
        filesTableModel.items = mutableListOf<SmartEnvFileEntry>()
        filesTable.setShowGrid(false)
        filesTable.setRowHeight(24)
        filesTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        filesTable.tableHeader.reorderingAllowed = false
        filesTable.columnModel.getColumn(0).maxWidth = 60
        filesTableModel.addTableModelListener {
            markModified()
            refreshPreview()
        }

        val addButton = iconButton(AllIcons.General.Add, "Add file or folder") { addPathEntry() }
        val removeButton = iconButton(AllIcons.General.Remove, "Remove selected file") { removeSelectedFile() }
        val moveUpButton = iconButton(AllIcons.Actions.MoveUp, "Move file up") { moveSelectedEntry(-1) }
        val moveDownButton = iconButton(AllIcons.Actions.MoveDown, "Move file down") { moveSelectedEntry(1) }
        val exportButton = iconButton(AllIcons.ToolbarDecorator.Export, "Export resolved preview to JSON") {
            exportResolvedPreview()
        }

        previewToggle = JToggleButton("Show resolved preview", AllIcons.Actions.Preview).apply {
            toolTipText = "Toggle resolved preview panel"
            isFocusable = false
            putClientProperty("JButton.buttonType", "square")
            addActionListener { updatePreviewVisibility(isSelected) }
        }

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = Component.LEFT_ALIGNMENT
            add(addButton)
            add(Box.createHorizontalStrut(4))
            add(removeButton)
            add(Box.createHorizontalStrut(12))
            add(moveUpButton)
            add(Box.createHorizontalStrut(4))
            add(moveDownButton)
            add(Box.createHorizontalStrut(12))
            add(exportButton)
            add(Box.createHorizontalGlue())
        }

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(8)
            val filesLabel = JLabel("Files in profile").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(filesLabel)
            add(Box.createVerticalStrut(4))
            add(toolbar)
        }

        val orderingHint = JLabel("Later files override earlier ones (last wins)").apply {
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }

        previewContainer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
            isVisible = false
        }

        val previewControls = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(previewToggle, BorderLayout.NORTH)
            add(previewContainer, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(topStack, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(JBScrollPane(filesTable), BorderLayout.CENTER)
                add(orderingHint, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(previewControls, BorderLayout.SOUTH)
        }
    }

    private fun addPathEntry() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true).apply {
            title = "Add SmartEnv File or Folder"
        }
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val selection = chooser.choose(project)
        if (selection.isEmpty()) return
        selection.forEach { file ->
            if (file.isDirectory) {
                addFolderEntries(file)
            } else {
                addFileEntry(file)
            }
        }
    }

    private fun addFolderEntries(folder: VirtualFile) {
        try {
            Files.walk(folder.toNioPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { path ->
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
                        ?.let { addFileEntry(it) }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun addFileEntry(file: VirtualFile) {
        val profile = selectedProfile ?: return
        val relativePath = FileUtil.getRelativePath(project.basePath ?: file.path, file.path, '/') ?: file.path
        val inferredFormat = inferFormat(file.path)
        val entry = SmartEnvFileEntry(
            path = relativePath,
            enabled = true,
            type = inferredFormat.type,
            jsonMode = inferredFormat.jsonMode ?: SmartEnvJsonMode.FLAT,
            order = profile.files.size
        )
        profile.files.add(entry)
        filesTableModel.items = profile.files
        markModified()
        refreshPreview()
    }

    private fun inferFormat(path: String): FileFormatOption {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ENGLISH)
        return when {
            extension.isBlank() -> FileFormatOption.JSON_BLOB
            extension == "env" -> FileFormatOption.DOTENV
            extension == "properties" -> FileFormatOption.PROPERTIES
            extension == "yaml" || extension == "yml" -> FileFormatOption.YAML
            extension == "json" -> FileFormatOption.JSON_FLAT
            extension == "txt" -> FileFormatOption.TEXT
            else -> FileFormatOption.AUTO
        }
    }

    private fun removeSelectedFile() {
        val profile = selectedProfile ?: return
        val entry = filesTable.selectedObject ?: return
        profile.files.remove(entry)
        profile.files.forEachIndexed { idx, item -> item.order = idx }
        filesTableModel.items = profile.files
        markModified()
        refreshPreview()
    }

    private fun updatePreviewVisibility(force: Boolean? = null) {
        if (!this::previewContainer.isInitialized) return
        val targetVisible = force ?: (this::previewToggle.isInitialized && previewToggle.isSelected)
        previewContainer.isVisible = targetVisible
        previewToggle.takeIf { this::previewToggle.isInitialized }?.isSelected = targetVisible
    }

    private fun selectColorChoice(hex: String) {
        if (!this::colorCombo.isInitialized) return
        val normalized = hex.lowercase(Locale.getDefault())
        var index = (0 until colorComboModel.size).firstOrNull {
            colorComboModel.getElementAt(it).hex.equals(normalized, true)
        }
        if (index == null && normalized.isNotBlank()) {
            val custom = ColorChoice("Custom $hex", normalized)
            colorComboModel.addElement(custom)
            index = colorComboModel.size - 1
        }
        if (index != null && index >= 0) {
            colorCombo.selectedIndex = index
        } else if (colorComboModel.size > 0) {
            colorCombo.selectedIndex = 0
        }
    }

    private fun updateActiveProfileLabel() {
        if (!this::activeProfileLabel.isInitialized) return
        val active = stateSnapshot.findProfileById(stateSnapshot.activeProfileId)
        val name = active?.name?.ifBlank { active.id } ?: "None"
        activeProfileLabel.text = "Active profile: $name"
        activeProfileLabel.foreground = if (active == null) UIUtil.getLabelDisabledForeground() else UIUtil.getLabelForeground()
        if (this::setActiveButton.isInitialized) {
            val canActivate = selectedProfile?.let { it.id != stateSnapshot.activeProfileId } ?: false
            setActiveButton.isEnabled = canActivate
        }
    }

    private fun selectProfile(profile: SmartEnvProfile?) {
        selectedProfile = profile
        suppressFieldUpdates = true
        profileNameField.text = profile?.name.orEmpty()
        showLogsCheckBox.isEnabled = profile != null
        showLogsCheckBox.isSelected = profile?.showLogsWhenRunning ?: false
        if (this::previewToggle.isInitialized) {
            previewToggle.isEnabled = profile != null
        }
        selectColorChoice(profile?.color.orEmpty())
        suppressFieldUpdates = false
        filesTableModel.items = profile?.files ?: mutableListOf<SmartEnvFileEntry>()
        refreshPreview()
        if (this::setActiveButton.isInitialized) {
            setActiveButton.isEnabled = profile != null && profile.id != stateSnapshot.activeProfileId
        }
        updateActiveProfileLabel()
    }

    private fun markModified() {
        dirty = true
    }

    private fun moveSelectedEntry(delta: Int) {
        val profile = selectedProfile ?: return
        val index = filesTable.selectedRow
        if (index < 0) return
        val newIndex = index + delta
        if (newIndex < 0 || newIndex >= profile.files.size) return
        profile.files.swap(index, newIndex)
        profile.files.forEachIndexed { idx, entry -> entry.order = idx }
        filesTableModel.items = profile.files
        filesTable.selectionModel.setSelectionInterval(newIndex, newIndex)
        markModified()
        refreshPreview()
    }

    private fun <T> MutableList<T>.swap(i: Int, j: Int) {
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }

    private fun refreshPreview() {
        val profile = selectedProfile ?: stateSnapshot.profiles.firstOrNull()
        if (profile == null) {
            previewArea.text = "No profile defined."
            return
        }
        val result = resolver.resolve(project, stateSnapshot, profile)
        val ordered = result.flattenedEntryMap()
        previewArea.text = buildString {
            append("Chain: ${result.chain.joinToString(" ? ")}\n\n")
            if (ordered.isEmpty()) {
                append("<empty profile>\n")
            } else {
                ordered.values.forEach { entry ->
                    append("${entry.key} = ${entry.value}\n")
                }
            }
        }
    }

    private fun exportResolvedPreview() {
        val profile = selectedProfile ?: run {
            Messages.showInfoMessage(project, "Select a profile first.", "SmartEnv")
            return
        }
        val result = resolver.resolve(project, stateSnapshot, profile)
        val descriptor = FileSaverDescriptor(
            "Export SmartEnv Preview",
            "Save merged environment as JSON",
            "json"
        )
        val suggestedName = sanitizeFileName(profile.name.ifBlank { profile.id })
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save("$suggestedName.json") ?: return
        val path = wrapper.getVirtualFile(true)?.toNioPath() ?: wrapper.file.toPath()
        val mapper = jacksonObjectMapper()
        try {
            Files.writeString(
                path,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.variables)
            )
            Messages.showInfoMessage(
                project,
                "Exported ${result.variables.size} variables to ${path.fileName}",
                "SmartEnv"
            )
        } catch (ex: IOException) {
            Messages.showErrorDialog(project, "Failed to export preview: ${ex.message}", "SmartEnv")
        }
    }

    private fun sanitizeFileName(name: String): String {
        val base = name.lowercase(Locale.getDefault()).replace("[^a-z0-9-_]+".toRegex(), "_").trim('_')
        return (if (base.isNotBlank()) base else "smartenv-export")
    }

    private fun reloadState() {
        stateSnapshot = service.getState().deepCopy()
        if (stateSnapshot.profiles.isEmpty()) {
            val fallback = SmartEnvProfile(id = "default", name = "Default")
            stateSnapshot.profiles.add(fallback)
            stateSnapshot.activeProfileId = fallback.id
        }
        if (stateSnapshot.activeProfileId.isNullOrBlank()) {
            stateSnapshot.activeProfileId = stateSnapshot.profiles.firstOrNull()?.id
        }
        enabledCheckBox.isSelected = stateSnapshot.enabled
        profilesModel.clear()
        stateSnapshot.profiles.forEach { profilesModel.addElement(it) }
        val activeProfile = stateSnapshot.findProfileById(stateSnapshot.activeProfileId)
        profilesList.setSelectedValue(activeProfile ?: profilesModel.firstElementOrNull(), true)
        selectProfile(profilesList.selectedValue)
        dirty = false
        updatePreviewVisibility(false)
        updateActiveProfileLabel()
    }

    override fun reset() {
        reloadState()
    }

    override fun isModified(): Boolean = dirty

    override fun apply() {
        service.loadState(stateSnapshot.deepCopy())
        dirty = false
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    private fun DefaultListModel<SmartEnvProfile>.firstElementOrNull(): SmartEnvProfile? {
        return if (size() == 0) null else firstElement()
    }

    private fun simpleDocumentListener(action: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isFocusable = false
            putClientProperty("JButton.buttonType", "square")
            addActionListener { action() }
        }
    }

    private class ColorSwatchIcon(private val color: Color) : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12

        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            if (g == null) return
            val g2 = g.create() as Graphics2D
            try {
                g2.color = color
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.fillOval(x, y, iconWidth, iconHeight)
            } finally {
                g2.dispose()
            }
        }
    }

    private data class ColorChoice(val label: String, val hex: String) {
        override fun toString(): String = label
    }

    private fun String?.parseColor(): Color? {
        val hex = this?.trim()?.removePrefix("#").orEmpty()
        if (hex.isEmpty()) return null
        return runCatching { ColorUtil.fromHex(hex) }.getOrNull()
    }

    private class FileEnabledColumn : ColumnInfo<SmartEnvFileEntry, Boolean>("") {
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun valueOf(item: SmartEnvFileEntry): Boolean = item.enabled
        override fun isCellEditable(item: SmartEnvFileEntry?) = true
        override fun setValue(item: SmartEnvFileEntry?, value: Boolean) {
            item?.enabled = value
        }
    }

    private class FilePathColumn : ColumnInfo<SmartEnvFileEntry, String>("File") {
        override fun valueOf(item: SmartEnvFileEntry): String = item.path
        override fun isCellEditable(item: SmartEnvFileEntry?) = true
        override fun setValue(item: SmartEnvFileEntry?, value: String) {
            item?.path = value.trim()
        }
    }

    private class FileFormatColumn : ColumnInfo<SmartEnvFileEntry, String>("Format") {
        override fun valueOf(item: SmartEnvFileEntry): String = FileFormatOption.fromEntry(item).label
        override fun isCellEditable(item: SmartEnvFileEntry?) = true
        override fun setValue(item: SmartEnvFileEntry?, value: String) {
            item ?: return
            val option = FileFormatOption.fromLabel(value)
            item.type = option.type
            option.jsonMode?.let { item.jsonMode = it }
        }

        override fun getEditor(item: SmartEnvFileEntry?): DefaultCellEditor {
            val combo = JComboBox(FileFormatOption.labels)
            return DefaultCellEditor(combo)
        }
    }

    private class FileKeyColumn : ColumnInfo<SmartEnvFileEntry, String>("Key") {
        override fun valueOf(item: SmartEnvFileEntry): String = item.mode1Key.orEmpty()
        override fun isCellEditable(item: SmartEnvFileEntry?): Boolean {
            return item?.type == SmartEnvFileType.JSON && item.jsonMode == SmartEnvJsonMode.BLOB
        }

        override fun setValue(item: SmartEnvFileEntry?, value: String) {
            item?.mode1Key = value.trim().ifBlank { null }
        }
    }

    private enum class FileFormatOption(
        val label: String,
        val type: SmartEnvFileType,
        val jsonMode: SmartEnvJsonMode?
    ) {
        AUTO("Auto-detect", SmartEnvFileType.AUTO, null),
        DOTENV("Dotenv (.env)", SmartEnvFileType.DOTENV, null),
        PROPERTIES("Properties", SmartEnvFileType.PROPERTIES, null),
        YAML("YAML", SmartEnvFileType.YAML, null),
        JSON_FLAT("JSON (flatten)", SmartEnvFileType.JSON, SmartEnvJsonMode.FLAT),
        JSON_BLOB("JSON (blob key)", SmartEnvFileType.JSON, SmartEnvJsonMode.BLOB),
        TEXT("Plain text key=value", SmartEnvFileType.TEXT, null);

        companion object {
            val labels: Array<String> = values().map { it.label }.toTypedArray()

            fun fromLabel(label: String): FileFormatOption {
                return values().firstOrNull { it.label == label } ?: AUTO
            }

            fun fromEntry(entry: SmartEnvFileEntry): FileFormatOption {
                return values().firstOrNull {
                    it.type == entry.type && (it.jsonMode == null || it.jsonMode == entry.jsonMode)
                } ?: AUTO
            }
        }
    }
}



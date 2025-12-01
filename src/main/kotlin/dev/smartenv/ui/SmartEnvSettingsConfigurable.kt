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
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import dev.smartenv.engine.SmartEnvFileParseResult
import dev.smartenv.engine.SmartEnvResolver
import dev.smartenv.services.SmartEnvCustomEntry
import dev.smartenv.services.SmartEnvFileEntry
import dev.smartenv.services.SmartEnvFileType
import dev.smartenv.services.SmartEnvJsonMode
import dev.smartenv.services.SmartEnvProfile
import dev.smartenv.services.SmartEnvProfileEntryRef
import dev.smartenv.services.SmartEnvProfileEntryRef.EntryType
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
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import kotlin.jvm.Volatile
import javax.swing.AbstractAction
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
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SmartEnvSettingsConfigurable(private val project: Project) : SearchableConfigurable {
    companion object {
        private const val FOLDER_CONFIRMATION_THRESHOLD = 100
        private val SKIPPED_FOLDER_NAMES = setOf(".git", ".idea", "build", "out")

        @Volatile
        private var forcePreviewOnOpen: Boolean = false

        fun requestPreviewOnNextOpen() {
            forcePreviewOnOpen = true
        }
    }

    private val service by lazy { project.getService(SmartEnvProjectService::class.java)!! }
    private val resolver = SmartEnvResolver()
    private var stateSnapshot = service.getState().deepCopy()
    private var mainPanel: JPanel? = null

    private val profilesModel = DefaultListModel<SmartEnvProfile>()
    private val profilesList = JBList(profilesModel)
    private val entriesTableModel = ListTableModel<ProfileRow>(
        EntryEnabledColumn(),
        EntryTypeColumn(),
        EntryPathColumn(),
        EntryFormatValueColumn(),
        EntryKeyColumn(),
        EntryStatusColumn()
    )
    private val entriesTable = TableView<ProfileRow>(entriesTableModel)

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
    private val parentComboModel = DefaultComboBoxModel<ParentOption>()
    private lateinit var parentCombo: JComboBox<ParentOption>
    private lateinit var showLogsCheckBox: JBCheckBox
    private lateinit var activeProfileLabel: JLabel
    private lateinit var setActiveButton: JButton
    private lateinit var previewPanel: SmartEnvPreviewPanel
    private lateinit var previewContainer: JPanel
    private lateinit var previewToggle: JToggleButton


    private var selectedProfile: SmartEnvProfile? = null
    private var dirty = false
    private var suppressFieldUpdates = false
    private var suppressTableEvents = false
    private var latestFileStatuses: Map<String, SmartEnvFileParseResult> = emptyMap()

    override fun getId(): String = "dev.smartenv.settings"

    override fun getDisplayName(): String = "SmartEnv"

    override fun createComponent(): JComponent {
        if (mainPanel != null) {
            reloadState()
            maybeForcePreviewOnOpen()
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
                    component.icon = display?.hex?.parseColor()?.let { color ->
                        ColorSwatchIcon(color, ColorBadgePalette.borderColorForHex(display.hex, color))
                    }
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

        parentCombo = JComboBox(parentComboModel).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val option = value as? ParentOption
                    val component = super.getListCellRendererComponent(
                        list,
                        option?.label ?: "",
                        index,
                        isSelected,
                        cellHasFocus
                    ) as JLabel
                    return component
                }
            }
            addActionListener {
                if (suppressFieldUpdates) return@addActionListener
                val option = selectedItem as? ParentOption ?: return@addActionListener
                val profile = selectedProfile ?: return@addActionListener
                if (option.profileId == profile.parentId) return@addActionListener
                if (wouldCreateCycle(profile, option.profileId)) {
                    Messages.showWarningDialog(project, "Selecting ${option.label} would create an inheritance loop.", "SmartEnv")
                    updateParentComboOptions(profile)
                    return@addActionListener
                }
                profile.parentId = option.profileId
                markModified()
                refreshPreview()
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

        previewPanel = SmartEnvPreviewPanel(project)
        val centerStack = JPanel()
        centerStack.layout = BoxLayout(centerStack, BoxLayout.Y_AXIS)
        centerStack.border = JBUI.Borders.empty(0, 0, 6, 0)
        centerStack.add(createEntriesPanel())
        centerStack.add(Box.createVerticalStrut(12))
        centerStack.add(createPreviewSection())

        val centerPanel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(6)
            add(createProfileDetailPanel(), BorderLayout.NORTH)
            add(centerStack, BorderLayout.CENTER)
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
        maybeForcePreviewOnOpen()
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
            stateSnapshot.profiles.forEach { profile ->
                if (profile.parentId == candidate.id) {
                    profile.parentId = null
                }
            }
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
            .addLabeledComponent("Inherits From:", parentCombo, 1, false)
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

    private fun createEntriesPanel(): JPanel {
        suppressTableEvents = true
        entriesTableModel.items = mutableListOf()
        suppressTableEvents = false
        entriesTable.setShowGrid(false)
        entriesTable.setRowHeight(24)
        entriesTable.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        entriesTable.tableHeader.reorderingAllowed = false
        entriesTable.columnModel.getColumn(0).maxWidth = 60
        entriesTable.columnModel.getColumn(1).maxWidth = 90
        entriesTable.columnModel.getColumn(5).preferredWidth = 180
        entriesTableModel.addTableModelListener {
            if (suppressTableEvents) return@addTableModelListener
            markModified()
            syncRowsToProfile(selectedProfile)
            refreshPreview()
        }
        registerTableShortcuts(entriesTable) { removeSelectedEntries() }

        val addButton = JButton(AllIcons.General.Add).apply {
            toolTipText = "Add entry"
            isFocusable = false
            putClientProperty("JButton.buttonType", "square")
            addActionListener { showAddEntryMenu(this) }
        }
        val removeButton = iconButton(AllIcons.General.Remove, "Remove selected row(s)") { removeSelectedEntries() }
        val moveUpButton = iconButton(AllIcons.Actions.MoveUp, "Move entry up") { moveSelectedEntry(-1) }
        val moveDownButton = iconButton(AllIcons.Actions.MoveDown, "Move entry down") { moveSelectedEntry(1) }
        val exportButton = iconButton(AllIcons.ToolbarDecorator.Export, "Export resolved preview to JSON") {
            exportResolvedPreview()
        }
        previewToggle = JToggleButton(AllIcons.Actions.Preview).apply {
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
            add(Box.createHorizontalStrut(12))
            add(removeButton)
            add(Box.createHorizontalStrut(12))
            add(moveUpButton)
            add(Box.createHorizontalStrut(4))
            add(moveDownButton)
            add(Box.createHorizontalStrut(12))
            add(exportButton)
            add(Box.createHorizontalStrut(8))
            add(previewToggle)
            add(Box.createHorizontalGlue())
        }

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(8)
            val entriesLabel = JLabel("Files and custom variables").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(entriesLabel)
            add(Box.createVerticalStrut(4))
            add(toolbar)
        }

        val orderingHint = JLabel("Later entries override earlier ones (last wins)").apply {
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }

        return JPanel(BorderLayout()).apply {
            add(topStack, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(JBScrollPane(entriesTable), BorderLayout.CENTER)
                add(orderingHint, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }
    }

    private fun createPreviewSection(): JPanel {
        previewContainer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(previewPanel.component, BorderLayout.CENTER)
            isVisible = false
        }
        return previewContainer
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
        val candidates = collectFolderImportCandidates(folder)
        if (candidates.isEmpty()) return
        if (candidates.size > FOLDER_CONFIRMATION_THRESHOLD) {
            val response = Messages.showYesNoDialog(
                project,
                "About to import ${candidates.size} files from '${folder.name}'. Continue?",
                "SmartEnv",
                "Import All",
                "Cancel",
                null
            )
            if (response != Messages.YES) {
                return
            }
        }
        val localFs = LocalFileSystem.getInstance()
        candidates.forEach { path ->
            localFs.refreshAndFindFileByPath(path.toString())
                ?.let { addFileEntry(it) }
        }
    }

    private fun collectFolderImportCandidates(folder: VirtualFile): List<Path> {
        val files = mutableListOf<Path>()
        try {
            Files.walkFileTree(folder.toNioPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    val name = dir.fileName?.toString()?.lowercase(Locale.ENGLISH)
                    return if (name != null && SKIPPED_FOLDER_NAMES.contains(name)) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    if (Files.isRegularFile(file)) {
                        files.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
        return files
    }

    private fun addFileEntry(file: VirtualFile) {
        val profile = selectedProfile ?: return
        val relativePath = FileUtil.getRelativePath(project.basePath ?: file.path, file.path, '/') ?: file.path
        val inferredFormat = inferFormat(file.path)
        val entry = SmartEnvFileEntry(
            id = UUID.randomUUID().toString(),
            path = relativePath,
            enabled = true,
            type = inferredFormat.type,
            jsonMode = inferredFormat.jsonMode ?: SmartEnvJsonMode.FLAT,
            order = profile.files.size
        )
        val rows = entriesTableModel.items.toMutableList().apply {
            add(ProfileRow.FileRow(entry))
        }
        suppressTableEvents = true
        entriesTableModel.items = rows
        suppressTableEvents = false
        val selection = rows.lastIndex
        entriesTable.selectionModel.setSelectionInterval(selection, selection)
        syncRowsToProfile(profile)
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

    private fun addCustomEntryRow() {
        val profile = selectedProfile ?: return
        val entry = SmartEnvCustomEntry(
            id = UUID.randomUUID().toString(),
            key = "",
            value = "",
            enabled = true
        )
        val rows = entriesTableModel.items.toMutableList().apply {
            add(ProfileRow.CustomRow(entry))
        }
        suppressTableEvents = true
        entriesTableModel.items = rows
        suppressTableEvents = false
        val newIndex = rows.lastIndex
        entriesTable.selectionModel.setSelectionInterval(newIndex, newIndex)
        entriesTable.editCellAt(newIndex, 2)
        syncRowsToProfile(profile)
        markModified()
    }

    private fun removeSelectedEntries() {
        val profile = selectedProfile ?: return
        val selectedRows = entriesTable.selectedRows
        if (selectedRows.isEmpty()) return
        val rows = entriesTableModel.items.toMutableList()
        selectedRows.map { entriesTable.convertRowIndexToModel(it) }
            .distinct()
            .sortedDescending()
            .forEach { index ->
                if (index in rows.indices) {
                    rows.removeAt(index)
                }
            }
        suppressTableEvents = true
        entriesTableModel.items = rows
        suppressTableEvents = false
        syncRowsToProfile(profile)
        markModified()
        refreshPreview()
    }

    private fun registerTableShortcuts(table: TableView<*>, deleteAction: () -> Unit) {
        val deleteKey = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
        val selectAllKey = KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)
        val inputMap = table.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = table.actionMap
        inputMap.put(deleteKey, "smartenv.delete")
        actionMap.put("smartenv.delete", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = deleteAction()
        })
        inputMap.put(selectAllKey, "smartenv.selectAll")
        actionMap.put("smartenv.selectAll", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                table.selectAll()
            }
        })
    }

    private fun updatePreviewVisibility(force: Boolean? = null) {
        if (!this::previewContainer.isInitialized) return
        val targetVisible = force ?: (this::previewToggle.isInitialized && previewToggle.isSelected)
        previewContainer.isVisible = targetVisible
        previewToggle.takeIf { this::previewToggle.isInitialized }?.isSelected = targetVisible
    }

    private fun maybeForcePreviewOnOpen() {
        if (forcePreviewOnOpen) {
            forcePreviewOnOpen = false
            updatePreviewVisibility(true)
        }
    }

    private fun statusForEntry(entry: SmartEnvFileEntry): String {
        val parseResult = latestFileStatuses[entry.id] ?: return ""
        if (!parseResult.success) {
            return parseResult.note ?: "Unavailable"
        }
        val keys = parseResult.values.size
        return if (keys > 0) {
            "OK ($keys key${if (keys == 1) "" else "s"})"
        } else {
            "Parsed (0 keys)"
        }
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

    private fun showAddEntryMenu(invoker: Component) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Add file or folder").apply {
            addActionListener { addPathEntry() }
        })
        menu.add(JMenuItem("Add custom variable").apply {
            addActionListener { addCustomEntryRow() }
        })
        menu.show(invoker, 0, invoker.height)
    }

    private fun ensureEntryLayout(profile: SmartEnvProfile) {
        profile.files.forEach { it.ensureId() }
        profile.customEntries.forEach { it.ensureId() }
        val fileMap = profile.files.associateBy { it.id }
        val customMap = profile.customEntries.associateBy { it.id }
        val normalized = mutableListOf<SmartEnvProfileEntryRef>()
        val seenFiles = mutableSetOf<String>()
        val seenCustom = mutableSetOf<String>()
        profile.layout.forEach { entry ->
            when (entry.type) {
                EntryType.FILE -> if (fileMap.containsKey(entry.refId)) {
                    normalized.add(entry)
                    seenFiles.add(entry.refId)
                }
                EntryType.CUSTOM -> if (customMap.containsKey(entry.refId)) {
                    normalized.add(entry)
                    seenCustom.add(entry.refId)
                }
            }
        }
        profile.files.sortedBy { it.order }.forEach { file ->
            if (file.id !in seenFiles) {
                normalized.add(SmartEnvProfileEntryRef(EntryType.FILE, file.id))
            }
        }
        profile.customEntries.forEach { custom ->
            if (custom.id !in seenCustom) {
                normalized.add(SmartEnvProfileEntryRef(EntryType.CUSTOM, custom.id))
            }
        }
        profile.layout = normalized
    }

    private fun buildRows(profile: SmartEnvProfile): MutableList<ProfileRow> {
        ensureEntryLayout(profile)
        val fileMap = profile.files.associateBy { it.id }
        val customMap = profile.customEntries.associateBy { it.id }
        val rows = mutableListOf<ProfileRow>()
        profile.layout.forEach { entry ->
            when (entry.type) {
                EntryType.FILE -> fileMap[entry.refId]?.let { rows.add(ProfileRow.FileRow(it)) }
                EntryType.CUSTOM -> customMap[entry.refId]?.let { rows.add(ProfileRow.CustomRow(it)) }
            }
        }
        return rows
    }

    private fun syncRowsToProfile(profile: SmartEnvProfile?) {
        profile ?: return
        val files = mutableListOf<SmartEnvFileEntry>()
        val custom = mutableListOf<SmartEnvCustomEntry>()
        val layout = mutableListOf<SmartEnvProfileEntryRef>()
        var order = 0
        entriesTableModel.items.forEach { row ->
            when (row) {
                is ProfileRow.FileRow -> {
                    row.entry.ensureId()
                    row.entry.order = order++
                    files.add(row.entry)
                    layout.add(SmartEnvProfileEntryRef(EntryType.FILE, row.entry.id))
                }
                is ProfileRow.CustomRow -> {
                    custom.add(row.entry)
                    layout.add(SmartEnvProfileEntryRef(EntryType.CUSTOM, row.entry.id))
                }
            }
        }
        profile.files = files
        profile.customEntries = custom
        profile.layout = layout
    }

    private fun updateParentComboOptions(profile: SmartEnvProfile?) {
        if (!this::parentCombo.isInitialized) return
        val options = mutableListOf(ParentOption(null, "No parent"))
        stateSnapshot.profiles
            .filter { candidate ->
                candidate.id != profile?.id && !wouldCreateCycle(profile, candidate.id)
            }
            .mapTo(options) { candidate ->
                ParentOption(candidate.id, candidate.name.ifBlank { candidate.id })
            }
        parentComboModel.removeAllElements()
        options.forEach { parentComboModel.addElement(it) }
        val selected = options.firstOrNull { it.profileId == profile?.parentId } ?: options.first()
        parentCombo.selectedItem = selected
    }

    private fun wouldCreateCycle(profile: SmartEnvProfile?, candidateParentId: String?): Boolean {
        if (profile == null || candidateParentId.isNullOrBlank()) return false
        var currentId: String? = candidateParentId
        val seen = mutableSetOf<String>()
        while (!currentId.isNullOrBlank()) {
            if (!seen.add(currentId)) {
                return true
            }
            if (currentId == profile.id) {
                return true
            }
            val next = stateSnapshot.findProfileById(currentId) ?: break
            currentId = next.parentId
        }
        return false
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
        if (this::parentCombo.isInitialized) {
            parentCombo.isEnabled = profile != null
            updateParentComboOptions(profile)
        }
        selectColorChoice(profile?.color.orEmpty())
        suppressFieldUpdates = false
        suppressTableEvents = true
        entriesTableModel.items = profile?.let { buildRows(it) } ?: mutableListOf()
        suppressTableEvents = false
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
        val viewIndex = entriesTable.selectedRow
        if (viewIndex < 0) return
        val modelIndex = entriesTable.convertRowIndexToModel(viewIndex)
        val rows = entriesTableModel.items.toMutableList()
        val targetIndex = modelIndex + delta
        if (targetIndex < 0 || targetIndex >= rows.size) return
        rows.swap(modelIndex, targetIndex)
        suppressTableEvents = true
        entriesTableModel.items = rows
        suppressTableEvents = false
        entriesTable.selectionModel.setSelectionInterval(targetIndex, targetIndex)
        syncRowsToProfile(profile)
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
            previewPanel.setResolution(null)
            latestFileStatuses = emptyMap()
            entriesTable.repaint()
            return
        }
        val result = resolver.resolve(project, stateSnapshot, profile)
        latestFileStatuses = result.fileStatuses
        previewPanel.setResolution(result)
        entriesTable.repaint()
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
        stateSnapshot.profiles.forEach {
            ensureEntryLayout(it)
            profilesModel.addElement(it)
        }
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

    private class ColorSwatchIcon(private val fill: Color, private val border: Color) : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12

        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            if (g == null) return
            val g2 = g.create() as Graphics2D
            try {
                g2.color = fill
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.fillOval(x, y, iconWidth, iconHeight)
                g2.color = border
                g2.drawOval(x, y, iconWidth - 1, iconHeight - 1)
            } finally {
                g2.dispose()
            }
        }
    }

    private data class ColorChoice(val label: String, val hex: String) {
        override fun toString(): String = label
    }

    private data class ParentOption(val profileId: String?, val label: String) {
        override fun toString(): String = label
    }

    private fun String?.parseColor(): Color? {
        val hex = this?.trim()?.removePrefix("#").orEmpty()
        if (hex.isEmpty()) return null
        return runCatching { ColorUtil.fromHex(hex) }.getOrNull()
    }

    private fun SmartEnvFileEntry.ensureId() {
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
        }
    }

    private fun SmartEnvCustomEntry.ensureId() {
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
        }
    }

    private class EntryEnabledColumn : ColumnInfo<ProfileRow, Boolean>("") {
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun valueOf(item: ProfileRow): Boolean = item.enabled
        override fun isCellEditable(item: ProfileRow?) = true
        override fun setValue(item: ProfileRow?, value: Boolean) {
            item?.enabled = value
        }
    }

    private class EntryTypeColumn : ColumnInfo<ProfileRow, String>("Type") {
        override fun valueOf(item: ProfileRow): String = when (item) {
            is ProfileRow.FileRow -> "File"
            is ProfileRow.CustomRow -> "Custom"
        }
    }

    private class EntryPathColumn : ColumnInfo<ProfileRow, String>("Path / Key") {
        override fun valueOf(item: ProfileRow): String = when (item) {
            is ProfileRow.FileRow -> item.entry.path
            is ProfileRow.CustomRow -> item.entry.key
        }

        override fun isCellEditable(item: ProfileRow?) = item != null

        override fun setValue(item: ProfileRow?, value: String) {
            val trimmed = value.trim()
            when (item) {
                is ProfileRow.FileRow -> item.entry.path = trimmed
                is ProfileRow.CustomRow -> item.entry.key = trimmed
                else -> {}
            }
        }
    }

    private class EntryFormatValueColumn : ColumnInfo<ProfileRow, String>("Format / Value") {
        private val formatEditor = DefaultCellEditor(JComboBox(FileFormatOption.labels))
        private val valueEditor = DefaultCellEditor(JTextField())

        override fun valueOf(item: ProfileRow): String = when (item) {
            is ProfileRow.FileRow -> FileFormatOption.fromEntry(item.entry).label
            is ProfileRow.CustomRow -> item.entry.value
        }

        override fun isCellEditable(item: ProfileRow?) = item != null

        override fun setValue(item: ProfileRow?, value: String) {
            when (item) {
                is ProfileRow.FileRow -> {
                    val option = FileFormatOption.fromLabel(value)
                    item.entry.type = option.type
                    option.jsonMode?.let { item.entry.jsonMode = it }
                }
                is ProfileRow.CustomRow -> item.entry.value = value
                else -> {}
            }
        }

        override fun getEditor(item: ProfileRow?): DefaultCellEditor {
            return when (item) {
                is ProfileRow.FileRow -> formatEditor
                is ProfileRow.CustomRow -> valueEditor
                else -> formatEditor
            }
        }
    }

    private class EntryKeyColumn : ColumnInfo<ProfileRow, String>("Blob Key") {
        override fun valueOf(item: ProfileRow): String = when (item) {
            is ProfileRow.FileRow -> item.entry.mode1Key.orEmpty()
            is ProfileRow.CustomRow -> ""
        }

        override fun isCellEditable(item: ProfileRow?): Boolean {
            return item is ProfileRow.FileRow &&
                item.entry.type == SmartEnvFileType.JSON &&
                item.entry.jsonMode == SmartEnvJsonMode.BLOB
        }

        override fun setValue(item: ProfileRow?, value: String) {
            if (item is ProfileRow.FileRow) {
                item.entry.mode1Key = value.trim().ifBlank { null }
            }
        }
    }

    private inner class EntryStatusColumn : ColumnInfo<ProfileRow, String>("Status") {
        override fun valueOf(item: ProfileRow): String = when (item) {
            is ProfileRow.FileRow -> statusForEntry(item.entry)
            else -> ""
        }
    }

    private sealed class ProfileRow {
        abstract var enabled: Boolean

        class FileRow(val entry: SmartEnvFileEntry) : ProfileRow() {
            override var enabled: Boolean
                get() = entry.enabled
                set(value) {
                    entry.enabled = value
                }
        }

        class CustomRow(val entry: SmartEnvCustomEntry) : ProfileRow() {
            override var enabled: Boolean
                get() = entry.enabled
                set(value) {
                    entry.enabled = value
                }
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



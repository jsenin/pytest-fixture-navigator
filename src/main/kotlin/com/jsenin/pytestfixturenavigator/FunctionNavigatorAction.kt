package com.jsenin.pytestfixturenavigator

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// ── Data ─────────────────────────────────────────────────────────────────────

data class FixtureEntry(
    val name: String,
    val filePath: String,
    val line: Int,
    val absolutePath: String
) {
    override fun toString() = "$name  —  $filePath:${line + 1}"
}

// ── Cache + file watcher ──────────────────────────────────────────────────────

private val fixtureCache   = ConcurrentHashMap<Project, List<FixtureEntry>>()
private val watchedProjects = ConcurrentHashMap.newKeySet<Project>()

private fun isFixtureFile(name: String) = name.startsWith("test_") || name == "conftest.py"

private fun watchProject(project: Project) {
    if (!watchedProjects.add(project)) return
    project.messageBus.connect().subscribe(
        VirtualFileManager.VFS_CHANGES,
        object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val relevant = events.any { isFixtureFile(it.file?.name ?: it.path.substringAfterLast('/')) }
                if (!relevant || !fixtureCache.containsKey(project)) return
                fixtureCache.remove(project)
                object : Task.Backgroundable(project, "Updating fixture index…", false) {
                    override fun run(indicator: ProgressIndicator) {
                        val fixtures = ReadAction.compute<List<FixtureEntry>, Exception> {
                            collectFixtures(project)
                        }
                        fixtureCache[project] = fixtures
                    }
                }.queue()
            }
        }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun PyFunction.isPytestFixture(): Boolean =
    decoratorList?.decorators?.any { decorator: PyDecorator ->
        val text = decorator.text.trimStart('@')
        text == "pytest.fixture" || text.startsWith("pytest.fixture(")
    } == true

@Suppress("EXPERIMENTAL_API_USAGE")
private fun collectFixtures(project: Project): List<FixtureEntry> {
    val result = mutableListOf<FixtureEntry>()
    val scope = GlobalSearchScope.projectScope(project)
    val psiManager = PsiManager.getInstance(project)
    val basePath = project.basePath ?: ""

    FilenameIndex.getAllFilesByExt(project, "py", scope)
        .filter { it.name.startsWith("test_") || it.name == "conftest.py" }
        .forEach { vFile ->
            val psiFile = psiManager.findFile(vFile) as? PyFile ?: return@forEach
            val relativePath = vFile.path.removePrefix(basePath).trimStart('/')
            val doc = psiFile.viewProvider.document ?: return@forEach

            fun add(fn: PyFunction) {
                result += FixtureEntry(
                    name = fn.name ?: "<anonymous>",
                    filePath = relativePath,
                    line = doc.getLineNumber(fn.textOffset),
                    absolutePath = vFile.path
                )
            }

            psiFile.topLevelFunctions.filter { it.isPytestFixture() }.forEach { add(it) }
            psiFile.topLevelClasses.forEach { cls ->
                cls.methods.filter { it.isPytestFixture() }.forEach { add(it) }
            }
        }

    return result.sortedBy { it.name.lowercase() }
}

private fun openNavigator(project: Project, fixtures: List<FixtureEntry>, initialNameFilter: String? = null, initialPathFilter: String? = null) {
    if (fixtures.isEmpty()) {
        com.intellij.openapi.ui.Messages.showInfoMessage(
            project,
            "No @pytest.fixture functions found in test_*.py or conftest.py files.",
            "Pytest Fixture Navigator"
        )
        return
    }
    FixtureNavigatorDialog(project, fixtures, initialNameFilter, initialPathFilter).show()
}

private fun openNavigatorCached(project: Project, initialNameFilter: String? = null, initialPathFilter: String? = null) {
    watchProject(project)
    val cached = fixtureCache[project]
    if (cached != null) {
        openNavigator(project, cached, initialNameFilter, initialPathFilter)
        return
    }
    object : Task.Backgroundable(project, "Scanning fixtures…", false) {
        override fun run(indicator: ProgressIndicator) {
            val fixtures = ReadAction.compute<List<FixtureEntry>, Exception> {
                collectFixtures(project)
            }
            fixtureCache[project] = fixtures
            ApplicationManager.getApplication().invokeLater {
                openNavigator(project, fixtures, initialNameFilter, initialPathFilter)
            }
        }
    }.queue()
}

// ── Action (menu / shortcut) ──────────────────────────────────────────────────

class FunctionNavigatorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedText = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim()
        openNavigatorCached(project, initialNameFilter = selectedText?.ifEmpty { null })
    }
}

// ── Action (project tree right-click) ─────────────────────────────────────────

class FixtureNavigatorTreeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected: VirtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val rootPath = if (selected.isDirectory) selected.path else selected.parent?.path
        val basePath = project.basePath ?: ""
        val relativePrefix = rootPath?.removePrefix(basePath)?.trimStart('/')
        openNavigatorCached(project, initialPathFilter = relativePrefix)
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }
}

// ── Dialog ────────────────────────────────────────────────────────────────────

class FixtureNavigatorDialog(
    private val project: Project,
    private val allEntries: List<FixtureEntry>,
    initialNameFilter: String? = null,
    initialPathFilter: String? = null
) : DialogWrapper(project, true) {

    private val nameField = JBTextField().apply {
        emptyText.text = "Filter by fixture name…"
        initialNameFilter?.let { text = it }
    }
    private val pathField = JBTextField().apply {
        emptyText.text = "Filter by path…"
        initialPathFilter?.let { text = it }
    }
    private val statusLabel = JLabel(" ${allEntries.size} / ${allEntries.size} fixtures").apply {
        foreground = java.awt.Color.GRAY
    }
    private val filterTimer = Timer(150) { populateList() }.apply { isRepeats = false }
    private var pendingPreviewEntry: FixtureEntry? = null
    private val previewTimer = Timer(200) { updatePreview(pendingPreviewEntry) }.apply { isRepeats = false }
    private val regexButton = JToggleButton(".*").apply {
        toolTipText = "Enable regex mode"
        font = font.deriveFont(java.awt.Font.BOLD, 11f)
        preferredSize = Dimension(32, 0)
        addActionListener { populateList() }
    }
    private val namePanel = JPanel(BorderLayout(4, 0)).apply {
        add(nameField, BorderLayout.CENTER)
        add(regexButton, BorderLayout.EAST)
    }
    private val pathPanel = JPanel(BorderLayout(4, 0)).apply {
        val browseButton = JButton(AllIcons.Nodes.Folder).apply {
            toolTipText = "Browse for folder…"
            preferredSize = Dimension(28, 0)
            addActionListener {
                val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                    .withTitle("Select Root Folder")
                val chosen = FileChooser.chooseFile(descriptor, project, null)
                if (chosen != null) {
                    val base = project.basePath ?: ""
                    val rel  = chosen.path.removePrefix(base).trimStart('/')
                    pathField.text = if (rel.isEmpty()) "" else rel
                }
            }
        }
        add(pathField, BorderLayout.CENTER)
        add(browseButton, BorderLayout.EAST)
    }
    private var sortByPath = false
    private val cellRenderer = FixtureCellRenderer()
    private val listModel  = DefaultListModel<FixtureEntry>()
    private val resultList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer  = this@FixtureNavigatorDialog.cellRenderer
    }
    private val previewPanel = JPanel(BorderLayout())
    private var previewEditor: Editor? = null

    init {
        title = "Pytest Fixture Navigator"
        init()
        populateList()
        if (listModel.size > 0) {
            resultList.selectedIndex = 0
            updatePreview(listModel.getElementAt(0))
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(800, 700)

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = filterTimer.restart()
            override fun removeUpdate(e: DocumentEvent)  = filterTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = filterTimer.restart()
        }
        nameField.document.addDocumentListener(docListener)
        pathField.document.addDocumentListener(docListener)

        val keyNav = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN  -> moveSelection(+1)
                    KeyEvent.VK_UP    -> moveSelection(-1)
                    KeyEvent.VK_ENTER,
                    KeyEvent.VK_F4    -> navigateToSelected()
                }
            }
        }
        nameField.addKeyListener(keyNav)
        pathField.addKeyListener(keyNav)

        resultList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_F4) navigateToSelected()
            }
        })

        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })

        resultList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                pendingPreviewEntry = resultList.selectedValue
                previewTimer.restart()
            }
        }

        val searchPanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(namePanel)
            add(pathPanel)
        }

        val sortByNameBtn = JToggleButton("Name", true).apply { putClientProperty("JButton.buttonType", "segmented-first") }
        val sortByPathBtn = JToggleButton("Path", false).apply { putClientProperty("JButton.buttonType", "segmented-last") }
        ButtonGroup().apply { add(sortByNameBtn); add(sortByPathBtn) }
        val sortListener = ActionListener {
            sortByPath = sortByPathBtn.isSelected
            cellRenderer.sortByPath = sortByPath
            populateList()
            resultList.repaint()
        }
        sortByNameBtn.addActionListener(sortListener)
        sortByPathBtn.addActionListener(sortListener)

        val bottomBar = JPanel(BorderLayout(8, 0)).apply {
            add(statusLabel, BorderLayout.WEST)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("Sort: ").apply { foreground = java.awt.Color.GRAY })
                add(sortByNameBtn)
                add(sortByPathBtn)
            }, BorderLayout.EAST)
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(resultList), previewPanel).apply {
            dividerLocation = 280
            resizeWeight = 0.4
        }

        panel.add(searchPanel, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        panel.add(bottomBar, BorderLayout.SOUTH)
        return panel
    }

    override fun getPreferredFocusedComponent() = nameField

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun dispose() {
        filterTimer.stop()
        previewTimer.stop()
        previewEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        super.dispose()
    }

    private fun updatePreview(entry: FixtureEntry?) {
        previewEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        previewEditor = null
        previewPanel.removeAll()

        if (entry != null) {
            val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(entry.absolutePath)
            val document = vFile?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (document != null) {
                val editor = EditorFactory.getInstance().createViewer(document, project)
                editor.settings.apply {
                    isLineNumbersShown = true
                    isFoldingOutlineShown = false
                }
                previewEditor = editor
                previewPanel.add(editor.component, BorderLayout.CENTER)
                previewPanel.revalidate()
                previewPanel.repaint()
                SwingUtilities.invokeLater {
                    editor.scrollingModel.scrollTo(LogicalPosition(entry.line, 0), ScrollType.CENTER)
                }
                return
            }
        }

        previewPanel.revalidate()
        previewPanel.repaint()
    }

    private fun populateList() {
        val nameText = nameField.text.trim()
        val path = pathField.text.trim().lowercase()
        val nameRegex = if (regexButton.isSelected && nameText.isNotEmpty()) {
            runCatching { Regex(nameText, RegexOption.IGNORE_CASE) }.getOrNull()
        } else null
        val filtered = allEntries
            .filter {
                val nameMatch = when {
                    nameText.isEmpty() -> true
                    nameRegex != null  -> nameRegex.containsMatchIn(it.name)
                    else               -> it.name.lowercase().contains(nameText.lowercase())
                }
                nameMatch && (path.isEmpty() || it.filePath.lowercase().contains(path))
            }
            .sortedBy { if (sortByPath) it.filePath.lowercase() else it.name.lowercase() }
        listModel.clear()
        if (filtered.isNotEmpty()) {
            listModel.addAll(filtered)
            resultList.selectedIndex = 0
        }
        statusLabel.text = " ${filtered.size} / ${allEntries.size} fixtures"
    }

    private fun moveSelection(delta: Int) {
        val next = (resultList.selectedIndex + delta).coerceIn(0, listModel.size - 1)
        resultList.selectedIndex = next
        resultList.ensureIndexIsVisible(next)
    }

    private fun navigateToSelected() {
        val entries = resultList.selectedValuesList.ifEmpty { return }
        val fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val editorManager = FileEditorManager.getInstance(project)
        entries.forEach { entry ->
            val vFile = fs.findFileByPath(entry.absolutePath) ?: return@forEach
            editorManager.openTextEditor(OpenFileDescriptor(project, vFile, entry.line, 0), true)
        }
        close(OK_EXIT_CODE)
    }
}

// ── Cell renderer ─────────────────────────────────────────────────────────────

class FixtureCellRenderer : DefaultListCellRenderer() {
    var sortByPath = false

    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): java.awt.Component {
        val entry = value as? FixtureEntry
        val html  = entry?.let {
            if (sortByPath)
                "<html><font color='gray'>${it.filePath}:${it.line + 1}</font>&nbsp;&nbsp;<b>${it.name}</b></html>"
            else
                "<html><b>${it.name}</b>&nbsp;&nbsp;<font color='gray'>${it.filePath}:${it.line + 1}</font></html>"
        }
        return super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus)
    }
}

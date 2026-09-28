package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextComponentAccessors
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TextFieldWithHistoryWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer
import io.github.shizzhang0.mavensettingstemplates.core.Template
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Edits the Maven fields of one template; also reused for per-project custom values. */
internal class TemplateEditor(project: Project?, showName: Boolean) {
    val nameField = JBTextField()

    private val bundledTitle = Presentation.homeKind(MavenHomeKind.BUNDLED_3)
    private val wrapperTitle = Presentation.homeKind(MavenHomeKind.WRAPPER)

    /**
     * Works like the IDE's own Maven home field: pick a built-in home or a detected installation from the list, or
     * type or browse to a path. The text maps back to a [MavenHomeKind] in [homeSelection].
     */
    private val homeField = TextFieldWithHistoryWithBrowseButton().apply {
        childComponent.setHistorySize(-1)
        childComponent.history = listOf(bundledTitle, wrapperTitle)
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir().withTitle(MstBundle.message("field.mavenHome").removeSuffix(":")),
            TextComponentAccessors.TEXT_FIELD_WITH_HISTORY_WHOLE_TEXT,
        )
    }
    /** Version of the chosen home (or a warning that it is not one), shown under the field like the IDE does. */
    private val homeInfoLabel = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
    }
    /** Global settings file of the chosen home; a [PathLabel] so a long path cannot widen the page. */
    private val globalSettingsLabel = PathLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
    }
    private val homeInfoPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(homeInfoLabel, BorderLayout.NORTH)
        add(globalSettingsLabel, BorderLayout.CENTER)
    }
    private lateinit var homeInfoRow: Row
    private var homeInfoRequest = 0

    /** Maven resolves paths for this project; the default project when the page has no project. */
    private val contextProject: Project = project ?: ProjectManager.getInstance().defaultProject

    private val userSettingsField = pathField(project, FileChooserDescriptorFactory.singleFile(), "field.userSettings", ".m2/settings.xml")
    private val localRepositoryField = pathField(project, FileChooserDescriptorFactory.singleDir(), "field.localRepository", ".m2/repository")

    /** Missing paths are only a warning; saving is never blocked (design §8, §11). */
    private val missingPathsLabel = JBLabel().apply {
        icon = AllIcons.General.Warning
        isVisible = false
    }

    val component: JComponent = panel {
        if (showName) {
            row(MstBundle.message("field.name")) { cell(nameField).align(AlignX.FILL) }
        }
        row(MstBundle.message("field.mavenHome")) { cell(homeField).align(AlignX.FILL) }
        homeInfoRow = row("") { cell(homeInfoPanel).align(AlignX.FILL) }.visible(false)
        row(MstBundle.message("field.userSettings")) { cell(userSettingsField).align(AlignX.FILL) }
        row(MstBundle.message("field.localRepository")) { cell(localRepositoryField).align(AlignX.FILL) }
        row { cell(missingPathsLabel) }
        row { comment(MstBundle.message("editor.comment", PathNormalizer.USER_HOME_VAR)) }
    }

    init {
        homeField.childComponent.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateMissingPaths()
                updateHomeInfo()
            }
        })
        userSettingsField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateMissingPaths()
                updateHomeInfo()
            }
        })
        localRepositoryField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateMissingPaths()
        })
        loadDetectedHomes()
    }

    fun load(template: Template) {
        nameField.text = template.name
        homeField.childComponent.text = when (template.mavenHomeKind) {
            MavenHomeKind.BUNDLED_3 -> bundledTitle
            MavenHomeKind.WRAPPER -> wrapperTitle
            MavenHomeKind.CUSTOM, MavenHomeKind.OTHER -> template.mavenHomePath
        }
        userSettingsField.text = template.userSettingsFile
        localRepositoryField.text = template.localRepository
    }

    fun saveTo(template: Template) {
        template.name = nameField.text.trim()
        val (kind, path) = homeSelection()
        template.mavenHomeKind = kind
        // Only a custom home has a path. Other kinds keep whatever was stored, so that viewing a template never
        // changes it and marks the page as modified.
        if (kind == MavenHomeKind.CUSTOM) template.mavenHomePath = path
        template.userSettingsFile = userSettingsField.text.trim()
        template.localRepository = localRepositoryField.text.trim()
    }

    fun setEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        homeField.isEnabled = enabled
        userSettingsField.isEnabled = enabled
        localRepositoryField.isEnabled = enabled
    }

    /** The home kind and, for a custom home, the path as typed (may contain `${user.home}`). Empty means bundled. */
    private fun homeSelection(): Pair<MavenHomeKind, String> = when (val text = homeField.childComponent.text.trim()) {
        "", bundledTitle -> MavenHomeKind.BUNDLED_3 to ""
        wrapperTitle -> MavenHomeKind.WRAPPER to ""
        else -> MavenHomeKind.CUSTOM to text
    }

    /** Adds the Maven installations the IDE detects to the list, after the built-in homes. */
    private fun loadDetectedHomes() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val detected = try {
                MavenSettingsAccess.detectedMavenHomes(contextProject)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Could not detect Maven installations", e)
                emptyList()
            }
            if (detected.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                val field = homeField.childComponent
                val text = field.text
                field.history = (listOf(bundledTitle, wrapperTitle) + detected).distinct()
                field.text = text
            }, ModalityState.any())
        }
    }

    /**
     * Resolves, off the EDT, what the IDE derives from the home and user settings file: the Maven version and global
     * settings file (shown under the field, or a warning for a folder that is not a Maven home) and the local
     * repository used when that field is left empty (shown as its hint).
     */
    private fun updateHomeInfo() {
        val request = ++homeInfoRequest
        val (kind, rawHome) = homeSelection()
        val home = FileUtil.toSystemDependentName(PathNormalizer.expand(rawHome))
        val userSettings = userSettingsField.text.trim().let { if (it.isEmpty()) it else FileUtil.toSystemDependentName(PathNormalizer.expand(it)) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = try {
                MavenSettingsAccess.homeInfo(contextProject, kind, home, userSettings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Could not resolve Maven home $home", e)
                null
            }
            // A missing folder is already reported by missingPathsLabel; only an existing non-Maven folder is flagged here.
            val notMavenHome = info?.version == null && kind == MavenHomeKind.CUSTOM && File(home).isDirectory
            ApplicationManager.getApplication().invokeLater({
                if (request != homeInfoRequest) return@invokeLater
                showHomeInfo(info, notMavenHome)
            }, ModalityState.any())
        }
    }

    private fun showHomeInfo(info: MavenSettingsAccess.HomeInfo?, notMavenHome: Boolean) {
        val version = info?.version
        homeInfoLabel.icon = if (notMavenHome) AllIcons.General.Warning else null
        homeInfoLabel.text = when {
            version != null -> MstBundle.message("field.mavenHome.version", version)
            notMavenHome -> MstBundle.message("field.mavenHome.invalid")
            else -> ""
        }
        homeInfoLabel.isVisible = version != null || notMavenHome
        val globalSettings = info?.globalSettingsFile
        globalSettings?.let { globalSettingsLabel.setPath(MstBundle.message("field.mavenHome.globalSettings") + " ", it) }
        globalSettingsLabel.isVisible = globalSettings != null
        homeInfoRow.visible(homeInfoLabel.isVisible || globalSettingsLabel.isVisible)
        info?.let { setIdeDefaultHint(localRepositoryField, it.localRepository) }
    }

    private fun updateMissingPaths() {
        val (kind, homePath) = homeSelection()
        val candidates = buildList {
            if (kind == MavenHomeKind.CUSTOM) add(homePath)
            add(userSettingsField.text)
            add(localRepositoryField.text)
        }
        val missing = candidates.filter { it.isNotBlank() }
            .map { FileUtil.toSystemDependentName(PathNormalizer.expand(it)) }
            .filterNot { File(it).exists() }
        missingPathsLabel.text = MstBundle.message("editor.missingPaths", missing.joinToString(", "))
        missingPathsLabel.isVisible = missing.isNotEmpty()
    }

    private fun pathField(
        project: Project?,
        descriptor: FileChooserDescriptor,
        titleKey: String,
        defaultUnderHome: String?,
    ): TextFieldWithBrowseButton = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, descriptor.withTitle(MstBundle.message(titleKey).removeSuffix(":")))
        if (defaultUnderHome != null) {
            setIdeDefaultHint(this, System.getProperty("user.home") + "/" + defaultUnderHome)
        }
    }

    private fun setIdeDefaultHint(field: TextFieldWithBrowseButton, path: String) {
        // The default constructor creates an ExtendableTextField, which is a JBTextField (verified via javap).
        (field.textField as? JBTextField)?.emptyText?.text =
            MstBundle.message("field.hint.ideDefault", FileUtil.toSystemDependentName(path))
        field.textField.repaint()
    }

    private companion object {
        val LOG = logger<TemplateEditor>()
    }
}

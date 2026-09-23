package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Edits the Maven fields of one template; also reused for per-project custom values. */
internal class TemplateEditor(project: Project?, showName: Boolean) {
    val nameField = JBTextField()

    private val homeKindCombo = ComboBox(
        CollectionComboBoxModel(listOf(MavenHomeKind.BUNDLED_3, MavenHomeKind.WRAPPER, MavenHomeKind.CUSTOM)),
    ).apply {
        renderer = textRenderer(Presentation::homeKind)
    }
    private val homePathField = pathField(project, FileChooserDescriptorFactory.singleDir(), "field.mavenHome", null)
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
        // One cell for both, otherwise the combo shares a grid column with the full-width fields and stretches.
        row(MstBundle.message("field.mavenHome")) {
            cell(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(homeKindCombo, BorderLayout.WEST)
                add(homePathField, BorderLayout.CENTER)
            }).align(AlignX.FILL)
        }
        row(MstBundle.message("field.userSettings")) { cell(userSettingsField).align(AlignX.FILL) }
        row(MstBundle.message("field.localRepository")) { cell(localRepositoryField).align(AlignX.FILL) }
        row { cell(missingPathsLabel) }
        row { comment(MstBundle.message("editor.comment", PathNormalizer.USER_HOME_VAR)) }
    }

    init {
        homeKindCombo.addActionListener {
            updateHomePathState()
            updateMissingPaths()
        }
        listOf(homePathField, userSettingsField, localRepositoryField).forEach { field ->
            field.textField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = updateMissingPaths()
            })
        }
        updateHomePathState()
    }

    fun load(template: Template) {
        nameField.text = template.name
        homeKindCombo.selectedItem = template.mavenHomeKind
        homePathField.text = template.mavenHomePath
        userSettingsField.text = template.userSettingsFile
        localRepositoryField.text = template.localRepository
        updateHomePathState()
    }

    fun saveTo(template: Template) {
        template.name = nameField.text.trim()
        template.mavenHomeKind = homeKindCombo.selectedItem as? MavenHomeKind ?: MavenHomeKind.BUNDLED_3
        template.mavenHomePath = homePathField.text.trim()
        template.userSettingsFile = userSettingsField.text.trim()
        template.localRepository = localRepositoryField.text.trim()
    }

    fun setEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        homeKindCombo.isEnabled = enabled
        userSettingsField.isEnabled = enabled
        localRepositoryField.isEnabled = enabled
        updateHomePathState()
    }

    private fun updateHomePathState() {
        homePathField.isEnabled = homeKindCombo.isEnabled && homeKindCombo.selectedItem == MavenHomeKind.CUSTOM
    }

    private fun updateMissingPaths() {
        val candidates = buildList {
            if (homeKindCombo.selectedItem == MavenHomeKind.CUSTOM) add(homePathField.text)
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
            val defaultPath = FileUtil.toSystemDependentName(System.getProperty("user.home") + "/" + defaultUnderHome)
            // The default constructor creates an ExtendableTextField, which is a JBTextField (verified via javap).
            (textField as? JBTextField)?.emptyText?.text = MstBundle.message("field.hint.ideDefault", defaultPath)
        }
    }
}

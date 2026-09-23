package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.Template
import javax.swing.JComponent

/** The "Current Project" block. Not shown for the default project ("Settings for New Projects"). */
internal class CurrentProjectPanel(
    project: Project,
    private val templates: () -> List<Template>,
    /** Values the project would get from rules / the default template; pre-fills a first "Custom values" edit. */
    private val inheritedValues: () -> Template?,
    private val onChanged: () -> Unit,
) {
    private val followRadio = JBRadioButton(MstBundle.message("current.follow"))
    private val templateRadio = JBRadioButton(MstBundle.message("current.template"))
    private val customRadio = JBRadioButton(MstBundle.message("current.custom"))
    private val notManagedRadio = JBRadioButton(MstBundle.message("current.notManaged"))
    private val radios = listOf(followRadio, templateRadio, customRadio, notManagedRadio)
    private val templateCombo = ComboBox<Template>().apply {
        renderer = textRenderer(Template::name)
    }
    private val customEditor = TemplateEditor(project, showName = false)
    private val effectiveLabel = JBLabel()
    private var customValues = Template()
    /** False until custom values exist: saved in the binding, or pre-filled on the first switch to "Custom values". */
    private var hasCustomValues = false
    private lateinit var customRow: Row

    val component: JComponent = panel {
        row { label(FileUtil.toSystemDependentName(project.basePath.orEmpty())) }
        // The DSL rejects radio buttons outside buttonsGroup and creates the ButtonGroup itself.
        buttonsGroup {
            row { cell(followRadio) }
            row {
                cell(templateRadio)
                cell(templateCombo)
            }
            row { cell(customRadio) }
            customRow = row { cell(customEditor.component).align(AlignX.FILL) }
            row { cell(notManagedRadio) }
        }
        row { cell(effectiveLabel) }
    }

    init {
        radios.forEach { radio ->
            radio.addActionListener {
                if (radio === customRadio) prefillCustomValues()
                updateEnabledState()
                onChanged()
            }
        }
        templateCombo.addActionListener { onChanged() }
    }

    fun reset(binding: Binding?) {
        val saved = binding?.custom
        hasCustomValues = saved != null
        customValues = saved?.copy() ?: Template()
        customEditor.load(customValues)
        refreshTemplates(binding?.templateId)
        val selected = when (binding?.mode) {
            null -> followRadio
            BindingMode.TEMPLATE -> templateRadio
            BindingMode.CUSTOM -> customRadio
            BindingMode.NOT_MANAGED -> notManagedRadio
        }
        selected.isSelected = true
        updateEnabledState()
    }

    /** The binding being edited; null means "follow folder rules". */
    fun currentBinding(): Binding? = when {
        templateRadio.isSelected -> Binding(BindingMode.TEMPLATE, templateId = (templateCombo.selectedItem as? Template)?.id)
        customRadio.isSelected -> {
            customEditor.saveTo(customValues)
            Binding(BindingMode.CUSTOM, custom = customValues.copy())
        }
        notManagedRadio.isSelected -> Binding(BindingMode.NOT_MANAGED)
        else -> null
    }

    /** Rebuilds the template list after templates were added, removed or renamed. */
    fun refreshTemplates(selectedId: String? = (templateCombo.selectedItem as? Template)?.id) {
        val list = templates()
        templateCombo.model = CollectionComboBoxModel(list, list.firstOrNull { it.id == selectedId })
    }

    /** One line per entry, so long paths never widen the whole settings page. */
    fun setEffective(lines: List<String>) {
        effectiveLabel.text = lines.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
    }

    /** An empty form here would silently mean "IDE defaults" on OK, so start from what is in effect instead. */
    private fun prefillCustomValues() {
        if (hasCustomValues) return
        hasCustomValues = true
        customValues = inheritedValues()?.copy(name = "") ?: Template()
        customEditor.load(customValues)
    }

    private fun updateEnabledState() {
        templateCombo.isEnabled = templateRadio.isSelected
        customEditor.setEnabled(customRadio.isSelected)
        customRow.visible(customRadio.isSelected)
    }
}

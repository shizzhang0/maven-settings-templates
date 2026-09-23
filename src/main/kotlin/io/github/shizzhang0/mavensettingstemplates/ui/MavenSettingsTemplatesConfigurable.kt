package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.apply.SettingsAppliedHandler
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.SettingsResolver
import io.github.shizzhang0.mavensettingstemplates.core.Template
import io.github.shizzhang0.mavensettingstemplates.core.TemplatesConfig
import io.github.shizzhang0.mavensettingstemplates.settings.ProjectRecords
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings
import javax.swing.JComponent

/** Build Tools > Maven > Settings Templates (design §8). A project-level page editing application-level data. */
class MavenSettingsTemplatesConfigurable(private val project: Project) : Configurable {

    private var templatesPanel: TemplatesPanel? = null
    private var rulesPanel: RulesPanel? = null
    private var recordsPanel: ProjectRecordsPanel? = null
    private var currentPanel: CurrentProjectPanel? = null

    /** Base path of the current project; null for the default project ("Settings for New Projects"). */
    private val projectPath: String? = project.basePath?.takeUnless { project.isDefault }

    override fun getDisplayName(): String = MstBundle.message("configurable.displayName")

    override fun createComponent(): JComponent {
        val templates = TemplatesPanel(project, ::countReferences, ::onTemplatesChanged)
        val rules = RulesPanel(project) { templates.currentTemplates() }
        val records = ProjectRecordsPanel { templates.currentTemplates() }
        val current = projectPath?.let {
            CurrentProjectPanel(project, { templates.currentTemplates() }, ::inheritedTemplate, ::refreshEffective)
        }
        templatesPanel = templates
        rulesPanel = rules
        recordsPanel = records
        currentPanel = current
        return panel {
            if (current != null) {
                group(MstBundle.message("section.current")) {
                    row { cell(current.component).align(AlignX.FILL) }
                }
            }
            group(MstBundle.message("section.templates")) {
                row { cell(templates.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
            group(MstBundle.message("section.rules")) {
                row { cell(rules.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
            group(MstBundle.message("section.records")) {
                row { cell(records.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
        }
    }

    override fun isModified(): Boolean {
        val templates = templatesPanel ?: return false
        if (pendingConfig(templates) != TemplatesSettings.getInstance().snapshot()) return diag("isModified: templates/rules")
        if (recordsPanel?.removedKeys().orEmpty().isNotEmpty()) return diag("isModified: record removals")
        val current = currentPanel ?: return false
        val binding = current.currentBinding()
        val persisted = persistedBinding()
        return if (binding != persisted) diag("isModified: binding $binding vs persisted $persisted") else false
    }

    override fun apply() {
        val templates = templatesPanel ?: return
        // TEMPORARY diagnostics for the lost-edit investigation (MST-DIAG); remove once fixed.
        val pending = pendingConfig(templates)
        diag("apply: " + pending.templates.joinToString { "${it.name}=${it.localRepository}" })
        TemplatesSettings.getInstance().replace(pending)
        val records = ProjectRecords.getInstance()
        records.remove(recordsPanel?.removedKeys().orEmpty())
        val current = currentPanel
        val path = projectPath
        if (current != null && path != null) {
            val binding = current.currentBinding()
            val persisted = persistedBinding()
            diag("apply binding: $binding (persisted $persisted)")
            if (binding != persisted) {
                records.update(ProjectRecords.keyOf(path), path) { it.binding = binding }
            }
        }
        reset()
        SettingsAppliedHandler.onApplied()
    }

    private fun diag(message: String): Boolean {
        thisLogger().info("MST-DIAG #${System.identityHashCode(this)} $message")
        return true
    }

    override fun reset() {
        diag("reset: persisted binding ${persistedBinding()}")
        val config = TemplatesSettings.getInstance().snapshot()
        templatesPanel?.reset(config.templates, config.defaultTemplateId)
        rulesPanel?.reset(config.rules)
        recordsPanel?.reset(ProjectRecords.getInstance().all())
        currentPanel?.reset(persistedBinding())
        refreshEffective()
    }

    override fun disposeUIResources() {
        templatesPanel = null
        rulesPanel = null
        recordsPanel = null
        currentPanel = null
    }

    private fun pendingConfig(templates: TemplatesPanel): TemplatesConfig = TemplatesConfig(
        templates.currentTemplates().toMutableList(),
        templates.defaultTemplateId,
        rulesPanel?.currentRules().orEmpty().toMutableList(),
    )

    private fun persistedBinding(): Binding? =
        projectPath?.let { ProjectRecords.getInstance().get(ProjectRecords.keyOf(it))?.binding }

    private fun countReferences(templateId: String): Pair<Int, Int> {
        val rules = rulesPanel?.countReferences(templateId) ?: 0
        val projects = ProjectRecords.getInstance().all().count { (_, record) ->
            record.binding?.mode == BindingMode.TEMPLATE && record.binding?.templateId == templateId
        }
        return rules to projects
    }

    /** The template this project follows without a binding: its deepest folder rule, else the default template. */
    private fun inheritedTemplate(): Template? {
        val templates = templatesPanel ?: return null
        val path = projectPath ?: return null
        val config = pendingConfig(templates)
        return SettingsResolver(config).matchingRule(path)?.second ?: config.template(config.defaultTemplateId)
    }

    private fun onTemplatesChanged() {
        rulesPanel?.refresh()
        recordsPanel?.refresh()
        currentPanel?.refreshTemplates()
        refreshEffective()
    }

    private fun refreshEffective() {
        val current = currentPanel ?: return
        val templates = templatesPanel ?: return
        val path = projectPath ?: return
        val resolved = SettingsResolver(pendingConfig(templates)).resolve(path, current.currentBinding())
        current.setEffective(
            if (resolved == null) {
                listOf(MstBundle.message("current.effective.none"))
            } else {
                listOf(MstBundle.message("current.effective", Presentation.describe(resolved.source))) +
                    Presentation.summaryLines(resolved.values)
            },
        )
    }
}

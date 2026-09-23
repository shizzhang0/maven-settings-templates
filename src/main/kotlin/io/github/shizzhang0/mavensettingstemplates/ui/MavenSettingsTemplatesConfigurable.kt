package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.TemplatesConfig
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings
import javax.swing.JComponent

/** Build Tools > Maven > Settings Templates (design §8). Task 5 adds the remaining sections. */
class MavenSettingsTemplatesConfigurable(private val project: Project) : Configurable {

    private var templatesPanel: TemplatesPanel? = null

    override fun getDisplayName(): String = MstBundle.message("configurable.displayName")

    override fun createComponent(): JComponent {
        val templates = TemplatesPanel(project, countReferences = { 0 to 0 }, onChanged = {})
        templatesPanel = templates
        return panel {
            group(MstBundle.message("section.templates")) {
                row { cell(templates.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
        }
    }

    override fun isModified(): Boolean = pendingConfig() != TemplatesSettings.getInstance().snapshot()

    override fun apply() {
        TemplatesSettings.getInstance().replace(pendingConfig())
    }

    override fun reset() {
        val config = TemplatesSettings.getInstance().snapshot()
        templatesPanel?.reset(config.templates, config.defaultTemplateId)
    }

    override fun disposeUIResources() {
        templatesPanel = null
    }

    private fun pendingConfig(): TemplatesConfig {
        val persisted = TemplatesSettings.getInstance().snapshot()
        val templates = templatesPanel ?: return persisted
        return persisted.copy(templates = templates.currentTemplates().toMutableList(), defaultTemplateId = templates.defaultTemplateId)
    }
}

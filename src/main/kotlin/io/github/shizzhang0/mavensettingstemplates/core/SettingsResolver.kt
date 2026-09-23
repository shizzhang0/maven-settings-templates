package io.github.shizzhang0.mavensettingstemplates.core

/** Where resolved values came from; drives UI and notification text. */
sealed interface Source {
    data class ProjectTemplate(val templateName: String) : Source
    data object ProjectCustom : Source
    data class Rule(val folder: String, val templateName: String) : Source
    data class DefaultTemplate(val templateName: String) : Source
}

data class Resolved(val values: MavenValues, val source: Source)

/** Design §5.1: project binding > deepest folder rule > default template > leave alone. */
class SettingsResolver(private val config: TemplatesConfig) {

    /** Returns null when the plugin must leave the project alone. */
    fun resolve(projectPath: String, binding: Binding?): Resolved? {
        if (binding != null) {
            when (binding.mode) {
                BindingMode.NOT_MANAGED -> return null
                BindingMode.CUSTOM -> binding.custom?.let { return Resolved(it.toValues(), Source.ProjectCustom) }
                BindingMode.TEMPLATE -> config.template(binding.templateId)?.let {
                    return Resolved(it.toValues(), Source.ProjectTemplate(it.name))
                }
            }
        }
        matchingRule(projectPath)?.let { (rule, template) ->
            return Resolved(template.toValues(), Source.Rule(rule.folder, template.name))
        }
        config.template(config.defaultTemplateId)?.let {
            return Resolved(it.toValues(), Source.DefaultTemplate(it.name))
        }
        return null
    }

    /** The deepest enabled rule containing [projectPath] whose template still exists. */
    fun matchingRule(projectPath: String): Pair<FolderRule, Template>? =
        config.rules.asSequence()
            .filter { it.enabled && PathNormalizer.isUnder(projectPath, it.folder) }
            .mapNotNull { rule -> config.template(rule.templateId)?.let { rule to it } }
            .maxByOrNull { (rule, _) -> PathNormalizer.normalize(rule.folder).length }
}

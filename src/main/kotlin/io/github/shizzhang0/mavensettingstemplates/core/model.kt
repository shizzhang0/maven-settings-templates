package io.github.shizzhang0.mavensettingstemplates.core

import java.util.UUID

/** A named set of Maven settings. Paths hold raw user input; `${user.home}` is expanded by [toValues]. */
data class Template(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var mavenHomeKind: MavenHomeKind = MavenHomeKind.BUNDLED_3,
    var mavenHomePath: String = "",
    var userSettingsFile: String = "",
    var localRepository: String = "",
) {
    fun toValues(): MavenValues = MavenValues(
        homeKind = mavenHomeKind,
        homePath = if (mavenHomeKind == MavenHomeKind.CUSTOM) PathNormalizer.expand(mavenHomePath) else "",
        userSettingsFile = PathNormalizer.expand(userSettingsFile),
        localRepository = PathNormalizer.expand(localRepository),
    )

    companion object {
        /** Used by "Save as project custom". [values] must not use [MavenHomeKind.OTHER]. */
        fun fromValues(values: MavenValues): Template = Template(
            mavenHomeKind = values.homeKind,
            mavenHomePath = values.homePath,
            userSettingsFile = values.userSettingsFile,
            localRepository = values.localRepository,
        )
    }
}

/** Every project under [folder] uses [templateId]; the deepest matching folder wins (design D4/D5). */
data class FolderRule(
    var folder: String = "",
    var templateId: String = "",
    var enabled: Boolean = true,
)

enum class BindingMode { TEMPLATE, CUSTOM, NOT_MANAGED }

/** A per-project override. A project without one follows folder rules. */
data class Binding(
    var mode: BindingMode = BindingMode.NOT_MANAGED,
    var templateId: String? = null,
    var custom: Template? = null,
) {
    fun deepCopy(): Binding = copy(custom = custom?.copy())
}

/** Everything the user configures; persisted by TemplatesSettings. */
data class TemplatesConfig(
    var templates: MutableList<Template> = mutableListOf(),
    var defaultTemplateId: String? = null,
    var rules: MutableList<FolderRule> = mutableListOf(),
) {
    fun template(id: String?): Template? = id?.let { wanted -> templates.firstOrNull { it.id == wanted } }

    fun deepCopy(): TemplatesConfig = TemplatesConfig(
        templates.mapTo(mutableListOf()) { it.copy() },
        defaultTemplateId,
        rules.mapTo(mutableListOf()) { it.copy() },
    )
}

/** What the plugin knows about one project; persisted by ProjectRecords under the normalized path. */
data class ProjectRecord(
    /** The project base path as the IDE reported it; shown in the UI. */
    var path: String = "",
    var binding: Binding? = null,
    /** Values the plugin last wrote (already expanded); null means never applied. */
    var lastApplied: MavenValues? = null,
) {
    fun deepCopy(): ProjectRecord = copy(binding = binding?.deepCopy(), lastApplied = lastApplied?.copy())
}

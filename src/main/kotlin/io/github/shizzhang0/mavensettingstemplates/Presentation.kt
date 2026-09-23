package io.github.shizzhang0.mavensettingstemplates

import com.intellij.openapi.util.io.FileUtil
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.core.Source
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess

/** User-facing text for resolved sources and values, shared by the settings page and notifications. */
internal object Presentation {
    fun describe(source: Source): String = when (source) {
        is Source.ProjectTemplate -> MstBundle.message("source.projectTemplate", source.templateName)
        Source.ProjectCustom -> MstBundle.message("source.projectCustom")
        is Source.Rule -> MstBundle.message("source.rule", source.templateName, FileUtil.toSystemDependentName(source.folder))
        is Source.DefaultTemplate -> MstBundle.message("source.default", source.templateName)
    }

    fun homeKind(kind: MavenHomeKind): String =
        MavenSettingsAccess.homeKindTitle(kind) ?: MstBundle.message("home.custom")

    fun home(values: MavenValues): String = when (values.homeKind) {
        MavenHomeKind.CUSTOM, MavenHomeKind.OTHER -> values.homePath
        MavenHomeKind.BUNDLED_3, MavenHomeKind.WRAPPER -> homeKind(values.homeKind)
    }

    fun path(value: String): String = value.ifEmpty { MstBundle.message("value.ideDefault") }

    fun summary(values: MavenValues): String =
        MstBundle.message("values.summary", home(values), path(values.userSettingsFile), path(values.localRepository))
}

package io.github.shizzhang0.mavensettingstemplates.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec
import org.jetbrains.idea.maven.project.BundledMaven3
import org.jetbrains.idea.maven.project.MavenGeneralSettings
import org.jetbrains.idea.maven.project.MavenHomeType
import org.jetbrains.idea.maven.project.MavenInSpecificPath
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent
import org.jetbrains.idea.maven.project.MavenWrapper

/**
 * The only file that touches the Maven plugin API. Every call was verified with javap against
 * IntelliJ IDEA 2026.2.3 (build 262.10968.63); see design §3.
 */
object MavenSettingsAccess {

    /** Raw IDE values kept in memory so Undo can restore even home types this plugin cannot model. */
    class RawSnapshot internal constructor(
        internal val homeType: MavenHomeType,
        internal val userSettingsFile: String,
        internal val localRepository: String,
    )

    /** The settings object is replaced when workspace.xml is reloaded (design §3.5). */
    private fun generalSettings(project: Project): MavenGeneralSettings =
        MavenWorkspaceSettingsComponent.getInstance(project).settings.generalSettings

    /** Identity of the current settings object, used to detect replacement. */
    fun settingsIdentity(project: Project): Any = generalSettings(project)

    fun read(project: Project): MavenValues {
        val settings = generalSettings(project)
        val (kind, path) = when (val home = settings.mavenHomeType) {
            BundledMaven3 -> MavenHomeKind.BUNDLED_3 to ""
            MavenWrapper -> MavenHomeKind.WRAPPER to ""
            is MavenInSpecificPath -> MavenHomeKind.CUSTOM to home.mavenHome
            else -> MavenHomeKind.OTHER to home.title
        }
        return MavenValues(kind, path, settings.userSettingsFile.orEmpty(), settings.localRepository.orEmpty())
    }

    fun snapshot(project: Project): RawSnapshot {
        val settings = generalSettings(project)
        return RawSnapshot(settings.mavenHomeType, settings.userSettingsFile.orEmpty(), settings.localRepository.orEmpty())
    }

    /** Writes [values] on the caller's thread; the batch fires a single `changed()`. */
    fun write(project: Project, values: MavenValues) {
        val home = when (values.homeKind) {
            MavenHomeKind.BUNDLED_3 -> BundledMaven3
            MavenHomeKind.WRAPPER -> MavenWrapper
            MavenHomeKind.CUSTOM -> MavenInSpecificPath(values.homePath)
            MavenHomeKind.OTHER -> error("OTHER home types are never written")
        }
        writeRaw(project, home, values.userSettingsFile, values.localRepository)
    }

    fun restore(project: Project, snapshot: RawSnapshot) {
        writeRaw(project, snapshot.homeType, snapshot.userSettingsFile, snapshot.localRepository)
    }

    private fun writeRaw(project: Project, home: MavenHomeType, userSettingsFile: String, localRepository: String) {
        val settings = generalSettings(project)
        settings.beginUpdate()
        try {
            settings.mavenHomeType = home
            // Explicit setters: Kotlin exposes these two as read-only properties (getter/setter types differ).
            settings.setUserSettingsFile(userSettingsFile)
            settings.setLocalRepository(localRepository)
        } finally {
            settings.endUpdate()
        }
    }

    /** Refreshes Maven's cached effective paths and, for Maven projects, schedules a full sync. Blocking: call off the EDT. */
    fun sync(project: Project) {
        MavenSettingsCache.getInstance(project).reload()
        val manager = MavenProjectsManager.getInstance(project)
        if (manager.isMavenizedProject) {
            manager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("Maven Settings Templates"))
        }
    }

    /** Listens to the current settings object until [parent] is disposed; returns that object's identity. */
    fun listen(project: Project, parent: Disposable, onChange: () -> Unit): Any {
        val settings = generalSettings(project)
        settings.addListener(MavenGeneralSettings.Listener { onChange() }, parent)
        return settings
    }

    /** The IDE's own label for a home kind, e.g. "Bundled (Maven 3)"; null for kinds the IDE does not name. */
    fun homeKindTitle(kind: MavenHomeKind): String? = when (kind) {
        MavenHomeKind.BUNDLED_3 -> BundledMaven3.title
        MavenHomeKind.WRAPPER -> MavenWrapper.title
        MavenHomeKind.CUSTOM, MavenHomeKind.OTHER -> null
    }
}

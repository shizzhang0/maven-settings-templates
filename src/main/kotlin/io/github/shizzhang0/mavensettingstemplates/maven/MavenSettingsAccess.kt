package io.github.shizzhang0.mavensettingstemplates.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import org.jetbrains.idea.maven.project.BundledMaven3
import org.jetbrains.idea.maven.project.MavenGeneralSettings
import org.jetbrains.idea.maven.project.MavenHomeType
import org.jetbrains.idea.maven.project.MavenInSpecificPath
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent
import org.jetbrains.idea.maven.project.MavenWrapper
import org.jetbrains.idea.maven.project.StaticResolvedMavenHomeType
import org.jetbrains.idea.maven.utils.MavenUtil
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

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
        return MavenValues(kind, path, settings.userSettingsFile.orEmpty(), localRepository(settings))
    }

    fun snapshot(project: Project): RawSnapshot {
        val settings = generalSettings(project)
        return RawSnapshot(settings.mavenHomeType, settings.userSettingsFile.orEmpty(), localRepository(settings))
    }

    /**
     * `getLocalRepository()` is `@ApiStatus.Internal` (the setter is public), so read the value from the settings'
     * persisted form instead: the same `<option name="localRepository">` that workspace.xml stores. Defaults
     * (empty) are omitted by the serializer, hence `orEmpty()`.
     */
    private fun localRepository(settings: MavenGeneralSettings): String =
        XmlSerializer.serialize(settings).getChildren("option")
            .firstOrNull { it.getAttributeValue("name") == "localRepository" }
            ?.getAttributeValue("value")
            .orEmpty()

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
            // Public full sync (scheduleUpdateAllMavenProjects/MavenSyncSpec are experimental). The isMavenizedProject
            // guard matters: for a non-Maven project this method would import every pom.xml it finds.
            manager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
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

    /**
     * Maven installations the IDE finds through M2_HOME, MAVEN_HOME and PATH, the same list its own Maven home field
     * offers. Blocking (reads the login shell environment): call off the EDT.
     */
    fun detectedMavenHomes(project: Project): List<String> =
        MavenUtil.getSystemMavenHomeVariants(project).filterIsInstance<MavenInSpecificPath>().map { it.mavenHome }

    /**
     * What the IDE derives from a Maven home and user settings file.
     *
     * @property version the Maven version; null for the wrapper (each project pins its own) and for a folder that is
     *   not a Maven home
     * @property globalSettingsFile `<home>/conf/settings.xml`, which Maven reads as its global settings; null when the
     *   file does not exist or the home is the wrapper
     * @property localRepository the local repository used when the template leaves it empty
     * @property defaultUserSettingsFile the user settings file used when the template leaves it empty
     *   (`~/.m2/settings.xml`); when it does not exist, only the global settings apply
     */
    class HomeInfo(
        val version: String?,
        val globalSettingsFile: String?,
        val localRepository: String,
        val defaultUserSettingsFile: String,
        val defaultUserSettingsExists: Boolean,
    )

    /**
     * Resolves [HomeInfo] the way the IDE does. [homePath] and [userSettingsFile] must already be expanded; an empty
     * [userSettingsFile] means the IDE default. Reads files and may block: call off the EDT.
     */
    fun homeInfo(project: Project, kind: MavenHomeKind, homePath: String, userSettingsFile: String): HomeInfo {
        val home: StaticResolvedMavenHomeType? = when (kind) {
            MavenHomeKind.BUNDLED_3 -> BundledMaven3
            MavenHomeKind.CUSTOM -> toPath(homePath)?.takeIf(MavenUtil::isValidMavenHome)?.let { MavenInSpecificPath(homePath) }
            MavenHomeKind.WRAPPER, MavenHomeKind.OTHER -> null
        }
        val version = home?.let(MavenUtil::getMavenVersion)
        val globalSettings = home?.let(MavenUtil::resolveGlobalSettingsFile)?.takeIf(Files::isRegularFile)?.toString()
        // Empty override: resolve from the user settings, then the global settings, then ~/.m2/repository. Like the
        // IDE, a home without its own settings (the wrapper, an invalid path) falls back to the bundled Maven.
        val localRepository = MavenUtil.resolveLocalRepository(project, "", home ?: BundledMaven3, userSettingsFile).toString()
        val defaultUserSettings = MavenUtil.resolveUserSettingsPath("", project)
        return HomeInfo(version, globalSettings, localRepository, defaultUserSettings.toString(), Files.isRegularFile(defaultUserSettings))
    }

    private fun toPath(path: String): Path? =
        if (path.isBlank()) null else try { Path.of(path) } catch (_: InvalidPathException) { null }
}

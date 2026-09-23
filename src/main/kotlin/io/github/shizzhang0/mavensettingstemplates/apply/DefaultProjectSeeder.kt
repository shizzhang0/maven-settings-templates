package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess
import io.github.shizzhang0.mavensettingstemplates.settings.ProjectRecords
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings

/** Design §7.1: seed the default project with the default template so new projects inherit it. */
object DefaultProjectSeeder {
    private val LOG = logger<DefaultProjectSeeder>()

    @Volatile
    private var cachedBaseline: MavenValues? = null

    fun seed() {
        try {
            val defaultProject = ProjectManager.getInstance().defaultProject
            val config = TemplatesSettings.getInstance().snapshot()
            val values = config.template(config.defaultTemplateId)?.toValues()
            if (values != null && !MavenSettingsAccess.read(defaultProject).sameAs(values)) {
                MavenSettingsAccess.write(defaultProject, values)
            }
            cachedBaseline = MavenSettingsAccess.read(defaultProject)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Could not seed the default project; relying on the startup activity only (design §11)", e)
            cachedBaseline = null
        }
    }

    /**
     * What a project gets when its `.idea` is (re)created (design §5.2 baseline). O(1). A never-seen project
     * inherits the default project's values; a project the IDE has opened before gets plain IDE defaults instead.
     */
    fun baselines(): List<MavenValues> = listOfNotNull(cachedBaseline, MavenValues())
}

class SeedDefaultProjectListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        // Load both stores now so the OPEN path does no disk I/O before its write (design §7.2).
        ProjectRecords.getInstance()
        DefaultProjectSeeder.seed()
    }
}

package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.shizzhang0.mavensettingstemplates.core.Trigger

/**
 * Registered with order="first". The platform launches post-startup activities concurrently, so this only
 * starts first; evaluate() therefore writes before any suspension to win the race with Maven (design §7.2).
 */
class MavenSettingsTemplatesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ProjectEvaluator.getInstance(project).evaluate(Trigger.OPEN)
        DriftWatcher.getInstance(project).attach()
    }
}

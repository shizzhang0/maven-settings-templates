package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import io.github.shizzhang0.mavensettingstemplates.core.Trigger

/** Design §7.3: after Apply/OK, reseed the default project and re-evaluate every open project. */
object SettingsAppliedHandler {
    fun onApplied() {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            DefaultProjectSeeder.seed()
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                ProjectEvaluator.getInstance(project).evaluate(Trigger.SETTINGS_APPLIED)
                // Projects that were open when the plugin was installed never ran the startup activity.
                DriftWatcher.getInstance(project).attach()
            }
        })
    }
}

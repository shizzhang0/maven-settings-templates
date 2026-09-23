package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.Alarm
import io.github.shizzhang0.mavensettingstemplates.core.Trigger
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess

/** Design §7.4: debounced drift detection that survives the settings object being replaced. */
@Service(Service.Level.PROJECT)
class DriftWatcher(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var attachedTo: Any? = null
    private var listenerScope: Disposable? = null

    @Synchronized
    fun attach() {
        if (project.isDisposed || MavenSettingsAccess.settingsIdentity(project) === attachedTo) return
        listenerScope?.let { Disposer.dispose(it) }
        val scope = Disposer.newDisposable(this, "MavenSettingsTemplates drift listener")
        attachedTo = MavenSettingsAccess.listen(project, scope, ::onSettingsChanged)
        listenerScope = scope
    }

    /** workspace.xml reloads replace the settings object and silently drop our listener (design §3.5). */
    fun reattachIfReplaced() {
        val replaced = synchronized(this) {
            !project.isDisposed && attachedTo != null && MavenSettingsAccess.settingsIdentity(project) !== attachedTo
        }
        if (replaced) {
            attach()
            scheduleEvaluation()
        }
    }

    private fun onSettingsChanged() {
        if (ProjectEvaluator.getInstance(project).isSelfWriting()) return
        scheduleEvaluation()
    }

    private fun scheduleEvaluation() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed) ProjectEvaluator.getInstance(project).evaluate(Trigger.CHANGED_AT_RUNTIME)
        }, DEBOUNCE_MS)
    }

    override fun dispose() = Unit

    companion object {
        private const val DEBOUNCE_MS = 500

        fun getInstance(project: Project): DriftWatcher = project.service()
    }
}

/** External `.idea` edits usually land while the IDE is in the background; check when it regains focus. */
class ReattachOnActivationListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        for (project in ProjectManager.getInstance().openProjects) {
            project.serviceIfCreated<DriftWatcher>()?.reattachIfReplaced()
        }
    }
}

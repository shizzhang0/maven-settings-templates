package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.notification.Notification
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.Decision
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.core.SettingsResolver
import io.github.shizzhang0.mavensettingstemplates.core.Template
import io.github.shizzhang0.mavensettingstemplates.core.Trigger
import io.github.shizzhang0.mavensettingstemplates.core.decide
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess
import io.github.shizzhang0.mavensettingstemplates.notify.MstNotifications
import io.github.shizzhang0.mavensettingstemplates.settings.ProjectRecords
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings
import java.util.concurrent.atomic.AtomicInteger

/** Design §5.2-§5.4 for one project. Every entry point is serialized on this instance. */
@Service(Service.Level.PROJECT)
class ProjectEvaluator(private val project: Project) {

    private val selfWriteDepth = AtomicInteger()
    private var ignored: MavenValues? = null // session only (design D8)
    private var pendingDrift: Notification? = null

    @Volatile
    private var errorReported = false

    /** True while this plugin writes, so DriftWatcher can skip its own events (design §5.4). */
    fun isSelfWriting(): Boolean = selfWriteDepth.get() > 0

    /**
     * On [Trigger.OPEN] this runs on the startup activity's thread and must reach [write] without suspending,
     * switching threads or doing I/O: it races Maven's own startup activity (design §7.2).
     */
    @Synchronized
    fun evaluate(trigger: Trigger) = guarded {
        val path = project.basePath ?: return@guarded
        val key = ProjectRecords.keyOf(path)
        val records = ProjectRecords.getInstance()
        val record = records.get(key)
        val resolved = SettingsResolver(TemplatesSettings.getInstance().snapshot()).resolve(path, record?.binding)
        if (resolved == null) {
            expirePendingDrift()
            ignored = null
            return@guarded
        }
        val target = resolved.values
        val current = MavenSettingsAccess.read(project)
        val decision = decide(target, current, record?.lastApplied, DefaultProjectSeeder.baselines(), ignored, trigger)
        when (decision) {
            Decision.NONE -> expirePendingDrift()
            Decision.RECORD -> {
                expirePendingDrift()
                records.update(key, path) { it.lastApplied = target }
            }
            Decision.FIRST_APPLY -> {
                val before = MavenSettingsAccess.snapshot(project)
                write(target)
                records.update(key, path) { it.lastApplied = target }
                requestSync()
                MstNotifications.applied(project, Presentation.describe(resolved.source)) { undo(key, path, before) }
            }
            Decision.FOLLOW -> {
                write(target)
                expirePendingDrift()
                records.update(key, path) { it.lastApplied = target }
                requestSync()
            }
            Decision.NOTIFY_DRIFT -> {
                expirePendingDrift()
                pendingDrift = MstNotifications.drift(
                    project = project,
                    source = Presentation.describe(resolved.source),
                    target = target,
                    current = current,
                    canSaveAsCustom = current.homeKind != MavenHomeKind.OTHER,
                    onRestore = ::restoreTemplateValues,
                    onSaveAsCustom = { saveAsCustom(key, path) },
                    onIgnore = ::ignoreCurrentValues,
                )
            }
        }
        // Logged after the write on purpose; used to measure the startup race (design §12 #2).
        LOG.info("$trigger ${project.name}: $decision via ${resolved.source}")
    }

    /** Undo of a first apply: restore the old values and stop managing this project (design §5.3). */
    @Synchronized
    private fun undo(key: String, path: String, before: MavenSettingsAccess.RawSnapshot) = guarded {
        if (project.isDisposed) return@guarded
        ProjectRecords.getInstance().update(key, path) {
            it.binding = Binding(BindingMode.NOT_MANAGED)
            it.lastApplied = null
        }
        selfWrite { MavenSettingsAccess.restore(project, before) }
        requestSync()
    }

    @Synchronized
    private fun restoreTemplateValues() = guarded {
        if (project.isDisposed) return@guarded
        val path = project.basePath ?: return@guarded
        val key = ProjectRecords.keyOf(path)
        val records = ProjectRecords.getInstance()
        val resolved = SettingsResolver(TemplatesSettings.getInstance().snapshot())
            .resolve(path, records.get(key)?.binding) ?: return@guarded
        write(resolved.values)
        records.update(key, path) { it.lastApplied = resolved.values }
        ignored = null
        requestSync()
    }

    @Synchronized
    private fun saveAsCustom(key: String, path: String) = guarded {
        if (project.isDisposed) return@guarded
        val current = MavenSettingsAccess.read(project)
        if (current.homeKind == MavenHomeKind.OTHER) return@guarded
        ProjectRecords.getInstance().update(key, path) {
            it.binding = Binding(BindingMode.CUSTOM, custom = Template.fromValues(current))
            it.lastApplied = current
        }
        ignored = null
    }

    @Synchronized
    private fun ignoreCurrentValues() = guarded {
        if (!project.isDisposed) ignored = MavenSettingsAccess.read(project)
    }

    private fun write(values: MavenValues) = selfWrite { MavenSettingsAccess.write(project, values) }

    private inline fun selfWrite(block: () -> Unit) {
        selfWriteDepth.incrementAndGet()
        try {
            block()
        } finally {
            selfWriteDepth.decrementAndGet()
        }
    }

    /** `MavenSettingsCache.reload()` blocks, so the sync always runs on a pooled thread. */
    private fun requestSync() {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            if (!project.isDisposed) guarded { MavenSettingsAccess.sync(project) }
        })
    }

    private fun expirePendingDrift() {
        pendingDrift?.expire()
        pendingDrift = null
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Maven Settings Templates failed for ${project.name}", e)
            if (!errorReported) {
                errorReported = true
                MstNotifications.error(project, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private val LOG = logger<ProjectEvaluator>()

        fun getInstance(project: Project): ProjectEvaluator = project.service()
    }
}

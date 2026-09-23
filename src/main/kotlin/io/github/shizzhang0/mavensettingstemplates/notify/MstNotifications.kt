package io.github.shizzhang0.mavensettingstemplates.notify

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer

/** Notification groups are registered in plugin.xml with these ids. */
object MstNotifications {
    private const val DRIFT_GROUP = "MavenSettingsTemplates.Drift"
    private const val INFO_GROUP = "MavenSettingsTemplates.Info"

    /** First apply (design §5.2 case 1): a sticky balloon with Undo (design D7). */
    fun applied(project: Project, source: String, onUndo: () -> Unit) {
        group(INFO_GROUP)
            .createNotification(
                MstBundle.message("notification.title"),
                MstBundle.message("notification.applied", escape(source)),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.undo")) { onUndo() })
            .notify(project)
    }

    /** Drift (design §5.2 case 3): a sticky balloon listing only the fields that differ. */
    fun drift(
        project: Project,
        source: String,
        target: MavenValues,
        current: MavenValues,
        canSaveAsCustom: Boolean,
        onRestore: () -> Unit,
        onSaveAsCustom: () -> Unit,
        onIgnore: () -> Unit,
    ): Notification {
        val notification = group(DRIFT_GROUP).createNotification(
            escape(MstBundle.message("notification.drift.title", source)),
            diffHtml(target, current),
            NotificationType.WARNING,
        )
        notification.addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.restore")) { onRestore() })
        if (canSaveAsCustom) {
            notification.addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.saveCustom")) { onSaveAsCustom() })
        }
        notification.addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.ignore")) { onIgnore() })
        notification.notify(project)
        return notification
    }

    fun error(project: Project, message: String) {
        group(INFO_GROUP)
            .createNotification(MstBundle.message("notification.title"), MstBundle.message("notification.error", escape(message)), NotificationType.ERROR)
            .notify(project)
    }

    private fun group(id: String): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(id)

    /** One line per differing field: "label: template value -> current value". */
    private fun diffHtml(target: MavenValues, current: MavenValues): String {
        val rows = buildList {
            val homeTarget = target.copy(userSettingsFile = "", localRepository = "")
            val homeCurrent = current.copy(userSettingsFile = "", localRepository = "")
            if (!homeTarget.sameAs(homeCurrent)) {
                add(row("field.mavenHome", Presentation.home(target), Presentation.home(current)))
            }
            if (!PathNormalizer.samePath(target.userSettingsFile, current.userSettingsFile)) {
                add(row("field.userSettings", Presentation.path(target.userSettingsFile), Presentation.path(current.userSettingsFile)))
            }
            if (!PathNormalizer.samePath(target.localRepository, current.localRepository)) {
                add(row("field.localRepository", Presentation.path(target.localRepository), Presentation.path(current.localRepository)))
            }
        }
        return rows.joinToString("<br>")
    }

    private fun row(labelKey: String, target: String, current: String): String =
        "<b>${escape(MstBundle.message(labelKey).removeSuffix(":"))}</b>: ${escape(target)} → ${escape(current)}"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}

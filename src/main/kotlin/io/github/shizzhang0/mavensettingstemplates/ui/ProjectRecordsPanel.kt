package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.ProjectRecord
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/** Every project the plugin has seen. Removing a record makes the next open of that project a first apply. */
internal class ProjectRecordsPanel(private val templates: () -> List<Template>) {
    private val rows = mutableListOf<Pair<String, ProjectRecord>>()
    private val pendingRemovals = mutableSetOf<String>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> MstBundle.message("records.column.project")
            1 -> MstBundle.message("records.column.binding")
            else -> MstBundle.message("records.column.status")
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val record = rows[row].second
            return when (column) {
                0 -> projectName(record)
                1 -> describeBinding(record.binding)
                else -> if (File(record.path).exists()) "" else MstBundle.message("records.missing")
            }
        }
    }

    /** Shows the project name; the full path is in the tooltip of the whole row. */
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(event: MouseEvent): String? =
            rows.getOrNull(rowAtPoint(event.point))?.let { FileUtil.toSystemDependentName(it.second.path) }
    }

    /** "2 projects no longer exist. Remove them" under the table, shown only while there are missing projects. */
    private val missingLabel = JBLabel()
    private val removeMissingLink = ActionLink("") { remove(missingKeys()) }
    private val missingRow = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
        add(missingLabel)
        add(removeMissingLink)
        isVisible = false
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(
            ToolbarDecorator.createDecorator(table)
                .disableAddAction()
                .setRemoveAction { remove(table.selectedRows.map { rows[it].first }) }
                .disableUpDownActions()
                .createPanel(),
            BorderLayout.CENTER,
        )
        add(missingRow, BorderLayout.SOUTH)
    }

    fun reset(records: List<Pair<String, ProjectRecord>>) {
        pendingRemovals.clear()
        rows.clear()
        rows += records.sortedWith(compareBy({ projectName(it.second).lowercase() }, { it.second.path.lowercase() }))
        changed()
    }

    fun removedKeys(): Set<String> = pendingRemovals.toSet()

    fun refresh() = changed()

    /** Asks first, saying which per-project settings would be lost. Nothing is saved until OK or Apply. */
    private fun remove(keys: Collection<String>) {
        val removing = rows.filter { it.first in keys }
        if (removing.isEmpty() || !confirmRemoval(removing.map { it.second })) return
        pendingRemovals += keys
        rows.removeAll { it.first in keys }
        changed()
    }

    private fun changed() {
        tableModel.fireTableDataChanged()
        val missing = missingKeys().size
        missingLabel.text = MstBundle.message("records.missing.summary", missing)
        removeMissingLink.text = MstBundle.message("records.missing.remove", missing)
        missingRow.isVisible = missing > 0
    }

    private fun missingKeys(): List<String> = rows.filterNot { File(it.second.path).exists() }.map { it.first }

    private fun confirmRemoval(records: List<ProjectRecord>): Boolean {
        val ownSettings = records.filter { it.binding != null }
        val message = buildString {
            append(MstBundle.message("records.remove.message", records.size))
            if (ownSettings.isNotEmpty()) {
                append("\n\n").append(MstBundle.message("records.remove.ownSettings"))
                ownSettings.forEach { append("\n  • ").append(projectName(it)).append(": ").append(describeBinding(it.binding)) }
            }
            append("\n\n").append(MstBundle.message("records.remove.firstApply"))
        }
        return MessageDialogBuilder.okCancel(MstBundle.message("records.remove.title"), message)
            .yesText(MstBundle.message("records.remove.ok"))
            .asWarning()
            .ask(table)
    }

    private fun projectName(record: ProjectRecord): String =
        FileUtil.toSystemDependentName(record.path).trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
            .ifEmpty { record.path }

    private fun describeBinding(binding: Binding?): String {
        if (binding == null) return MstBundle.message("binding.followRules")
        return when (binding.mode) {
            BindingMode.NOT_MANAGED -> MstBundle.message("binding.notManaged")
            BindingMode.CUSTOM -> MstBundle.message("binding.custom")
            BindingMode.TEMPLATE -> templates().firstOrNull { it.id == binding.templateId }
                ?.let { MstBundle.message("binding.template", it.name) }
                ?: MstBundle.message("binding.missingTemplate")
        }
    }
}

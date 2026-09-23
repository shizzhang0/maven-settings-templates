package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.ProjectRecord
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.io.File
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/** Every project the plugin has seen. Removing a record makes the next open of that project a first apply. */
internal class ProjectRecordsPanel(private val templates: () -> List<Template>) {
    private val rows = mutableListOf<Pair<String, ProjectRecord>>()
    private val pendingRemovals = mutableSetOf<String>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> MstBundle.message("records.column.path")
            1 -> MstBundle.message("records.column.binding")
            else -> MstBundle.message("records.column.status")
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val record = rows[row].second
            return when (column) {
                0 -> FileUtil.toSystemDependentName(record.path)
                1 -> describeBinding(record.binding)
                else -> if (File(record.path).exists()) "" else MstBundle.message("records.missing")
            }
        }
    }

    private val table = JBTable(tableModel)

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .disableAddAction()
        .setRemoveAction { remove(table.selectedRows.map { rows[it].first }) }
        .addExtraAction(DumbAwareAction.create(MstBundle.message("records.removeMissing"), AllIcons.Actions.GC) {
            remove(rows.filterNot { File(it.second.path).exists() }.map { it.first })
        })
        .disableUpDownActions()
        .createPanel()

    fun reset(records: List<Pair<String, ProjectRecord>>) {
        pendingRemovals.clear()
        rows.clear()
        rows += records.sortedBy { it.second.path.lowercase() }
        tableModel.fireTableDataChanged()
    }

    fun removedKeys(): Set<String> = pendingRemovals.toSet()

    fun refresh() = tableModel.fireTableDataChanged()

    private fun remove(keys: Collection<String>) {
        pendingRemovals += keys
        rows.removeAll { it.first in keys }
        tableModel.fireTableDataChanged()
    }

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

package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.FolderRule
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer
import io.github.shizzhang0.mavensettingstemplates.core.Template
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor

/** Folder -> template rules. The deepest matching folder wins, so rows are sorted by path and never reordered. */
internal class RulesPanel(private val project: Project?, private val templates: () -> List<Template>) {
    private val rules = mutableListOf<FolderRule>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount(): Int = rules.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            COLUMN_ENABLED -> MstBundle.message("rules.column.enabled")
            COLUMN_FOLDER -> MstBundle.message("rules.column.folder")
            else -> MstBundle.message("rules.column.template")
        }

        override fun getColumnClass(column: Int): Class<*> =
            if (column == COLUMN_ENABLED) Boolean::class.javaObjectType else Any::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = column != COLUMN_FOLDER

        override fun getValueAt(row: Int, column: Int): Any? = when (column) {
            COLUMN_ENABLED -> rules[row].enabled
            COLUMN_FOLDER -> rules[row].folder
            else -> templates().firstOrNull { it.id == rules[row].templateId }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            when (column) {
                COLUMN_ENABLED -> rules[row].enabled = value as Boolean
                COLUMN_TEMPLATE -> (value as? Template)?.let { rules[row].templateId = it.id }
            }
            fireTableCellUpdated(row, column)
        }
    }

    private val table = object : JBTable(tableModel) {
        override fun getCellEditor(row: Int, column: Int): TableCellEditor =
            if (column == COLUMN_TEMPLATE) DefaultCellEditor(templateCombo()) else super.getCellEditor(row, column)
    }

    val component: JComponent

    init {
        table.columnModel.getColumn(COLUMN_ENABLED).maxWidth = JBUI.scale(70)
        table.columnModel.getColumn(COLUMN_TEMPLATE).cellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val template = value as? Template
                if (template != null) {
                    append(template.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    append(MstBundle.message("rules.missingTemplate"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
        component = ToolbarDecorator.createDecorator(table)
            .setAddAction { addRule() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
    }

    fun reset(newRules: List<FolderRule>) {
        stopEditing()
        rules.clear()
        rules += newRules.map { it.copy() }
        sortRules()
        tableModel.fireTableDataChanged()
    }

    fun currentRules(): List<FolderRule> {
        stopEditing()
        return rules.map { it.copy() }
    }

    fun countReferences(templateId: String): Int = rules.count { it.templateId == templateId }

    /** Repaints template names after templates were renamed, added or removed. */
    fun refresh() {
        stopEditing()
        tableModel.fireTableDataChanged()
    }

    private fun addRule() {
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(MstBundle.message("rules.chooseFolder"))
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val folder = FileUtil.toSystemDependentName(chosen.path)
        if (rules.any { PathNormalizer.samePath(it.folder, folder) }) {
            Messages.showErrorDialog(project, MstBundle.message("rules.duplicate.message", folder), MstBundle.message("rules.duplicate.title"))
            return
        }
        rules += FolderRule(folder = folder, templateId = templates().firstOrNull()?.id.orEmpty())
        sortRules()
        tableModel.fireTableDataChanged()
    }

    private fun removeSelected() {
        stopEditing()
        table.selectedRows.sortedDescending().forEach { rules.removeAt(it) }
        tableModel.fireTableDataChanged()
    }

    private fun templateCombo(): ComboBox<Template> =
        ComboBox(CollectionComboBoxModel(templates())).apply {
            renderer = textRenderer(Template::name)
        }

    private fun sortRules() = rules.sortBy { PathNormalizer.normalize(it.folder) }

    private fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    private companion object {
        const val COLUMN_ENABLED = 0
        const val COLUMN_FOLDER = 1
        const val COLUMN_TEMPLATE = 2
    }
}

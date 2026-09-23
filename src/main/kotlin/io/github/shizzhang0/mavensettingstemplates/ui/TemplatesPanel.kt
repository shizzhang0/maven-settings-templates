package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.util.UUID
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Master-detail editor: the template list on the left, a [TemplateEditor] on the right. */
internal class TemplatesPanel(
    private val project: Project?,
    private val countReferences: (templateId: String) -> Pair<Int, Int>,
    private val onChanged: () -> Unit,
) {
    private val model = CollectionListModel<Template>()
    private val list = JBList(model)
    private val editor = TemplateEditor(project, showName = true)
    private var editing: Template? = null
    private var loading = false

    var defaultTemplateId: String? = null
        private set

    val component: JComponent

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = textRenderer { template ->
            val name = template.name.ifBlank { MstBundle.message("templates.unnamed") }
            if (template.id == defaultTemplateId) "★ $name" else name
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) select(list.selectedValue) }
        editor.nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (loading) return
                editing?.let(editor::saveTo)
                list.repaint()
                onChanged()
            }
        })
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addTemplate(Template(name = MstBundle.message("templates.newName"))) }
            .setRemoveAction { removeSelected() }
            .addExtraAction(DumbAwareAction.create(MstBundle.message("templates.copy"), AllIcons.Actions.Copy) { copySelected() })
            .addExtraAction(DumbAwareAction.create(MstBundle.message("templates.toggleDefault"), AllIcons.Nodes.Favorite) { toggleDefault() })
            .disableUpDownActions()
            .createPanel()
        component = JBSplitter(false, 0.3f).apply {
            firstComponent = decorated
            secondComponent = editor.component
        }
        select(null)
    }

    fun reset(templates: List<Template>, defaultId: String?) {
        editing = null
        defaultTemplateId = defaultId
        model.replaceAll(templates.map { it.copy() })
        list.clearSelection()
        if (model.size > 0) list.selectedIndex = 0
    }

    /** The edited templates, as copies. */
    fun currentTemplates(): List<Template> {
        editing?.let(editor::saveTo)
        return model.items.map { it.copy() }
    }

    private fun select(template: Template?) {
        editing?.let(editor::saveTo)
        editing = template
        loading = true
        try {
            editor.load(template ?: Template(name = ""))
        } finally {
            loading = false
        }
        editor.setEnabled(template != null)
    }

    private fun addTemplate(template: Template) {
        model.add(template)
        list.selectedIndex = model.size - 1
        onChanged()
    }

    private fun copySelected() {
        editing?.let(editor::saveTo)
        val source = list.selectedValue ?: return
        addTemplate(source.copy(id = UUID.randomUUID().toString(), name = MstBundle.message("templates.copyName", source.name)))
    }

    private fun removeSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val template = model.getElementAt(index)
        val (rules, projects) = countReferences(template.id)
        if (rules + projects > 0) {
            val answer = Messages.showYesNoDialog(
                project,
                MstBundle.message("templates.delete.message", template.name, rules, projects),
                MstBundle.message("templates.delete.title"),
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        editing = null
        model.remove(index)
        if (template.id == defaultTemplateId) defaultTemplateId = null
        list.clearSelection()
        if (model.size > 0) list.selectedIndex = minOf(index, model.size - 1)
        onChanged()
    }

    private fun toggleDefault() {
        val template = list.selectedValue ?: return
        defaultTemplateId = if (defaultTemplateId == template.id) null else template.id
        list.repaint()
        onChanged()
    }
}

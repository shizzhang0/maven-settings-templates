package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.ListCellRenderer

/** A list renderer showing [text] for each item (SimpleListCellRenderer.create is deprecated in 2026.2). */
internal fun <T : Any> textRenderer(text: (T) -> String): ListCellRenderer<T?> =
    textListCellRenderer { value: T? -> value?.let(text).orEmpty() }

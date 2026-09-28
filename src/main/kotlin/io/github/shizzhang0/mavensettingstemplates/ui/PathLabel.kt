package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Dimension

/**
 * A label for a long path that never widens its layout: it takes the width it is given and shortens the path in the
 * middle to fit, keeping the drive and the file name visible. The tooltip shows the full text.
 */
internal class PathLabel : JBLabel() {
    private var path = ""

    fun setPath(path: String) {
        this.path = path
        toolTipText = path
        fit()
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = JBUI.scale(MIN_WIDTH) }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        fit()
    }

    private fun fit() {
        val available = width - insets.left - insets.right
        var candidate = path
        if (available > 0) {
            val metrics = getFontMetrics(font)
            var maxLength = path.length
            while (maxLength > MIN_PATH_CHARS && metrics.stringWidth(candidate) > available) {
                maxLength--
                candidate = StringUtil.shortenPathWithEllipsis(path, maxLength)
            }
        }
        if (text != candidate) text = candidate
    }

    private companion object {
        const val MIN_WIDTH = 40
        const val MIN_PATH_CHARS = 8
    }
}

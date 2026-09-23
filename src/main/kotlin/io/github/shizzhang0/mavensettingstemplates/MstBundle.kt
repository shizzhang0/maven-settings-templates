package io.github.shizzhang0.mavensettingstemplates

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MavenSettingsTemplatesBundle"

internal object MstBundle {
    private val instance = DynamicBundle(MstBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): @Nls String =
        instance.getMessage(key, *params)
}

package io.github.shizzhang0.mavensettingstemplates.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.shizzhang0.mavensettingstemplates.core.TemplatesConfig

/** Templates, default template and folder rules. Roams with Settings Sync (design §4). */
@Service(Service.Level.APP)
@State(name = "MavenSettingsTemplates", storages = [Storage("mavenSettingsTemplates.xml")])
class TemplatesSettings : PersistentStateComponent<TemplatesConfig> {

    @Volatile
    private var config = TemplatesConfig()

    /** A private copy the caller may read or edit freely. */
    fun snapshot(): TemplatesConfig = config.deepCopy()

    fun replace(newConfig: TemplatesConfig) {
        config = newConfig.deepCopy()
    }

    override fun getState(): TemplatesConfig = config.deepCopy()

    override fun loadState(state: TemplatesConfig) {
        config = state.deepCopy()
    }

    companion object {
        fun getInstance(): TemplatesSettings = service()
    }
}

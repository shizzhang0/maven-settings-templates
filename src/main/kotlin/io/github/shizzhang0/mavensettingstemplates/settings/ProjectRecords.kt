package io.github.shizzhang0.mavensettingstemplates.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer
import io.github.shizzhang0.mavensettingstemplates.core.ProjectRecord

/** Per-project bindings and lastApplied values. Machine-specific, so it never roams (design §4). */
@Service(Service.Level.APP)
@State(
    name = "MavenSettingsTemplatesProjects",
    storages = [Storage(value = "mavenSettingsTemplates.local.xml", roamingType = RoamingType.DISABLED)],
)
class ProjectRecords : PersistentStateComponent<ProjectRecords.Records> {

    data class Records(var records: MutableMap<String, ProjectRecord> = mutableMapOf())

    private val lock = Any()
    private var records = mutableMapOf<String, ProjectRecord>()

    fun get(key: String): ProjectRecord? = synchronized(lock) { records[key]?.deepCopy() }

    /** All records as (key, copy) pairs. */
    fun all(): List<Pair<String, ProjectRecord>> =
        synchronized(lock) { records.map { (key, record) -> key to record.deepCopy() } }

    /** Applies [change] to the record for [key], creating it for [path] if needed. */
    fun update(key: String, path: String, change: (ProjectRecord) -> Unit) {
        synchronized(lock) {
            val record = records.getOrPut(key) { ProjectRecord(path = path) }
            record.path = path
            change(record)
        }
    }

    fun remove(keys: Collection<String>) {
        synchronized(lock) { keys.forEach { records.remove(it) } }
    }

    override fun getState(): Records =
        synchronized(lock) { Records(records.mapValuesTo(mutableMapOf()) { it.value.deepCopy() }) }

    override fun loadState(state: Records) {
        synchronized(lock) { records = state.records.mapValuesTo(mutableMapOf()) { it.value.deepCopy() } }
    }

    companion object {
        fun getInstance(): ProjectRecords = service()

        /** Map key for a project base path (design §6). */
        fun keyOf(projectPath: String): String = PathNormalizer.normalize(projectPath)
    }
}

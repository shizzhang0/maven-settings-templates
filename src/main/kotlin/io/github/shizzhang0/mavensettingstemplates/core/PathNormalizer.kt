package io.github.shizzhang0.mavensettingstemplates.core

import com.intellij.openapi.util.SystemInfo

/** Path helpers for comparing and matching only; stored values are never rewritten (design §6). */
object PathNormalizer {
    const val USER_HOME_VAR: String = "\${user.home}"

    private val systemUserHome: String get() = System.getProperty("user.home")

    /** Trims [path] and replaces `${user.home}`. This is the form written into the IDE. */
    fun expand(path: String, userHome: String = systemUserHome): String =
        path.trim().replace(USER_HOME_VAR, userHome)

    fun normalize(
        path: String,
        userHome: String = systemUserHome,
        caseSensitive: Boolean = SystemInfo.isFileSystemCaseSensitive,
    ): String {
        val slashed = expand(path, userHome).replace('\\', '/')
        val trimmed = if (slashed.length > 1) slashed.trimEnd('/') else slashed
        return if (caseSensitive) trimmed else trimmed.lowercase()
    }

    fun samePath(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** True when [path] is [folder] itself or lies anywhere below it. */
    fun isUnder(path: String, folder: String): Boolean {
        val f = normalize(folder)
        if (f.isEmpty()) return false
        val p = normalize(path)
        return p == f || p.startsWith(if (f.endsWith('/')) f else "$f/")
    }
}

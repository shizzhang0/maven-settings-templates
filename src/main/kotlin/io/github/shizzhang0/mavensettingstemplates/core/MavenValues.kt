package io.github.shizzhang0.mavensettingstemplates.core

enum class MavenHomeKind {
    BUNDLED_3,
    WRAPPER,
    CUSTOM,

    /** A home type this plugin cannot write (e.g. Bundled Maven 4). Only ever produced when reading the IDE. */
    OTHER,
}

/**
 * The three Maven settings this plugin manages, fully expanded (no `${user.home}`).
 * Properties are `var` only so XmlSerializer can persist it; treat instances as immutable.
 * For [MavenHomeKind.OTHER], [homePath] holds the IDE's title of that home type.
 */
data class MavenValues(
    var homeKind: MavenHomeKind = MavenHomeKind.BUNDLED_3,
    var homePath: String = "",
    var userSettingsFile: String = "",
    var localRepository: String = "",
) {
    /** Equality that ignores slash style, trailing slashes and (on Windows) case: the "≈" of design §5.2. */
    fun sameAs(other: MavenValues?): Boolean {
        if (other == null || homeKind != other.homeKind) return false
        val homeMatches = when (homeKind) {
            MavenHomeKind.CUSTOM -> PathNormalizer.samePath(homePath, other.homePath)
            MavenHomeKind.OTHER -> homePath == other.homePath
            MavenHomeKind.BUNDLED_3, MavenHomeKind.WRAPPER -> true
        }
        return homeMatches &&
            PathNormalizer.samePath(userSettingsFile, other.userSettingsFile) &&
            PathNormalizer.samePath(localRepository, other.localRepository)
    }
}

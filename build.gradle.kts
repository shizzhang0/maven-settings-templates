import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        // Set `intellijPlatformLocalPath` (e.g. in ~/.gradle/gradle.properties) to build against an installed IDE.
        // Otherwise download the exact build whose Maven API was verified (2026.2.3, 262.10968.63).
        val localIde = providers.gradleProperty("intellijPlatformLocalPath").orNull
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdea("2026.2.3")
        }
        bundledPlugin("org.jetbrains.idea.maven")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // No upper bound: new IDE versions can install the plugin (design decision D1).
            untilBuild = provider { null }
        }
    }
}

import org.jetbrains.changelog.Changelog
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

val pluginVersion = project.version.toString()

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // No upper bound: new IDE versions can install the plugin (design decision D1).
            untilBuild = provider { null }
        }
        // "What's New" on JetBrains Marketplace and in the IDE, taken from this version's CHANGELOG.md section.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
    // Needed only for publishing (see docs/publishing.md); the key never lives in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    // publishPlugin reads the Marketplace token from the ORG_GRADLE_PROJECT_intellijPlatformPublishingToken
    // environment variable (the plugin's default property), so no publishing block is needed.
    pluginVerification {
        ides {
            // Verify against the platform the plugin is built with; no extra IDE downloads.
            current()
        }
    }
}

// verifyPluginSignature reads signPlugin's output without declaring it; Gradle 9 rejects that
// ("implicit dependency") when both run in one build.
tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}

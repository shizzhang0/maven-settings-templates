import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Set `intellijPlatformLocalPath` (e.g. in ~/.gradle/gradle.properties) to build against an installed IDE.
val localIde: String? = providers.gradleProperty("intellijPlatformLocalPath").orNull

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        // Without a local IDE, build against 2026.2.3 (262.10968.63): the oldest supported release, whose Maven API
        // design §3 verified (and re-checked on 2026.3).
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
    // Needed only by publishPlugin (see docs/publishing.md). PUBLISH_TOKEN is also the plugin's own default
    // (verified in 2.19.0); stated here so it does not depend on that.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            // The platform the plugin is built with: 2026.2.3, the oldest supported release, unless a local IDE is set.
            current()
            // The newest release as well, so a release build checks both ends of the supported range. Skipped with a
            // local IDE, which is usually the newest release already, so local builds download no extra IDE.
            if (localIde == null) {
                create(IntelliJPlatformType.IntellijIdea, "2026.3")
            }
        }
    }
}

// verifyPluginSignature reads signPlugin's output without declaring it; Gradle 9 rejects that
// ("implicit dependency") when both run in one build.
tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}

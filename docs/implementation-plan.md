# Maven Settings Templates 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个 IntelliJ IDEA 插件：用模板集中管理 Maven home / User settings file / Local repository，按"项目单独配置 > 最深文件夹规则 > 默认模板"自动应用到项目，并在设置被手动修改时提醒用户。

**Architecture:** 纯逻辑（值模型、路径规范化、解析、决策）放在 `core/`，不依赖 IDE 服务；持久化是两个应用级 `PersistentStateComponent`；所有 Maven API 调用集中在 `maven/MavenSettingsAccess.kt`；`apply/` 负责生命周期（启动时应用、预写入 default project、设置变更后重评估、漂移监控）；`ui/` 是挂在 Maven 设置页下的单个项目级配置页。

**Tech Stack:** Kotlin 2.3.20、IntelliJ Platform Gradle Plugin 2.19.0、Gradle 9.6.1、IntelliJ IDEA 2026.2.3（build 262.10968.63）+ bundled Maven 插件、Kotlin UI DSL v2。

**Spec:** [docs/design.md](design.md)。执行者必须同时阅读设计文档和本计划，本计划中的"§x"都指设计文档的章节。

## Global Constraints

- 目标 IDE：IntelliJ IDEA 2026.2.3（build `262.10968.63`），`since-build = 262`，**不设 until-build**。
- 插件 id：`io.github.shizzhang0.mavensettingstemplates`；Gradle group：`io.github.shizzhang0`；Kotlin 根包：`io.github.shizzhang0.mavensettingstemplates`；vendor：`Timothy`（url `https://github.com/shizzhang0`）。
- 语言：代码、注释、界面文字、plugin.xml、README、CHANGELOG 一律英文；`docs/` 下的设计类文档用中文。
- 界面文字全部放在 `src/main/resources/messages/MavenSettingsTemplatesBundle.properties`，通过 `MstBundle.message(...)` 读取。**文案里不要出现撇号 `'`**：带参数的消息会经过 MessageFormat，撇号会被当成转义符吞掉。需要引号时用双引号。
- **只有 `maven/MavenSettingsAccess.kt` 可以 import `org.jetbrains.idea.maven.*`**。
- **不凭记忆写 API**。本计划里出现的每个平台 / Maven API 都已经对本机 IDE 的 jar 做过 javap 核实。如果执行中需要新的 API，先用 javap 核实再写：
  - Maven 插件：`"C:/Users/timothy/AppData/Local/Programs/IntelliJ IDEA/plugins/maven-plugin/lib/intellij.maven.jar"`
  - 平台：`"C:/Users/timothy/AppData/Local/Programs/IntelliJ IDEA/lib/*.jar"`（用 `unzip -Z1 <jar> | grep <类路径>` 定位类在哪个 jar）
- **Git**：在分支 `feature/initial-implementation` 上开发（用户已批准在本项目中执行 `git commit`）。每个任务验证通过后提交一次：提交信息用英文，采用 Conventional Commits 风格（`feat:` / `chore:` / `docs:`），末尾带 `Co-Authored-By` 行。**不要 push，也不要合并到 `main`**，这两件事由用户决定。每个任务的最后一步是"检查点"：提交，并把结果和提交哈希一起汇报给用户。
- **不保留单元测试**（用户全局规则）。需要验证纯逻辑时，写名为 `*TempTest.kt` 的临时测试，跑通后**在同一个任务内删除**。
- 所有命令在项目根目录用 Git Bash 执行（`./gradlew ...`）。
- **构建使用本机安装的 IDE，不下载**：`~/.gradle/gradle.properties`（不在仓库里）中设置了
  - `intellijPlatformLocalPath=C:/Users/timothy/AppData/Local/Programs/IntelliJ IDEA`（`build.gradle.kts` 读到这个属性就用 `local(...)`，否则下载 2026.2.3）
  - `org.gradle.java.installations.paths=C:/Users/timothy/AppData/Local/Programs/IntelliJ IDEA/jbr`：2026.2 平台要求用 Java 25 编译，本机只有 JDK 21，所以用 IDE 自带的 JBR 25.0.4 作为工具链。
  - `runIde` 启动的是这份本机 IDE，但配置、缓存和日志都在 `.intellijPlatform/sandbox/` 下，不影响你平时使用的 IDE 配置。

## 文件结构

```text
build.gradle.kts                          修改：目标 IDE、Maven 依赖、ideaVersion、pluginVerification
gradle.properties                         修改：group
README.md                                 重写
CHANGELOG.md                              修改
src/main/resources/META-INF/plugin.xml    重写
src/main/resources/messages/MavenSettingsTemplatesBundle.properties   新建（每个任务追加 key）
src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/
├── MstBundle.kt                 消息包访问
├── Presentation.kt              来源 / 值的界面文案（设置页与通知共用）
├── core/                        纯数据与纯逻辑
│   ├── MavenValues.kt           MavenHomeKind、MavenValues（展开后的三项设置 + 路径无关比较）
│   ├── PathNormalizer.kt        ${user.home} 展开、规范化、isUnder
│   ├── model.kt                 Template、FolderRule、BindingMode、Binding、TemplatesConfig、ProjectRecord
│   ├── SettingsResolver.kt      Source、Resolved、SettingsResolver（§5.1）
│   └── Decision.kt              Trigger、Decision、decide()（§5.2）
├── settings/                    持久化
│   ├── TemplatesSettings.kt     mavenSettingsTemplates.xml（随 Settings Sync 同步）
│   └── ProjectRecords.kt        mavenSettingsTemplates.local.xml（不同步）
├── maven/
│   └── MavenSettingsAccess.kt   唯一的 Maven API 调用点
├── notify/
│   └── MstNotifications.kt      首次应用提示、漂移提醒、错误提示
├── apply/
│   ├── ProjectEvaluator.kt      单个项目的决策执行与通知按钮动作（§5.2–§5.4）
│   ├── DefaultProjectSeeder.kt  预写入 default project + 缓存 baseline（§7.1）
│   ├── StartupActivity.kt       打开项目时应用（§7.2）
│   ├── SettingsAppliedHandler.kt 设置页点 OK 后重评估（§7.3）
│   └── DriftWatcher.kt          漂移监控 + 窗口激活时重新挂载（§7.4）
└── ui/
    ├── TemplateEditor.kt        单个模板的字段编辑器（也用于项目自定义值）
    ├── TemplatesPanel.kt        模板列表 + 编辑器
    ├── RulesPanel.kt            文件夹规则表
    ├── ProjectRecordsPanel.kt   项目记录表
    ├── CurrentProjectPanel.kt   当前项目区块
    └── MavenSettingsTemplatesConfigurable.kt   配置页入口
```

---

### Task 1: 脚手架清理与构建配置

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/META-INF/plugin.xml`（整体重写）
- Modify: `README.md`（整体重写）
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/MstBundle.kt`
- Create: `src/main/resources/messages/MavenSettingsTemplatesBundle.properties`
- Delete: `src/main/kotlin/MyMessageBundle.kt`、`src/main/kotlin/MyToolWindowFactory.kt`、`src/main/resources/messages/MyMessageBundle.properties`

**Interfaces:**
- Produces: `internal object MstBundle { fun message(key: String, vararg params: Any?): String }`；资源包 `messages.MavenSettingsTemplatesBundle`。

- [ ] **Step 1: 删除模板示例代码**

```bash
rm src/main/kotlin/MyMessageBundle.kt src/main/kotlin/MyToolWindowFactory.kt src/main/resources/messages/MyMessageBundle.properties
```

- [ ] **Step 2: 修改 `gradle.properties`**

整个文件替换为：

```properties
group = io.github.shizzhang0
version = 1.0.0-SNAPSHOT

# Enable Gradle Configuration Cache -> https://docs.gradle.org/current/userguide/configuration_cache.html
org.gradle.configuration-cache = true

# Enable Gradle Build Cache -> https://docs.gradle.org/current/userguide/build_cache.html
org.gradle.caching = true
```

- [ ] **Step 3: 修改 `build.gradle.kts`**

整个文件替换为：

```kotlin
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
```

- [ ] **Step 4: 重写 `plugin.xml`**

```xml
<!-- Plugin Configuration File. Read more: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html -->
<idea-plugin>
    <id>io.github.shizzhang0.mavensettingstemplates</id>
    <name>Maven Settings Templates</name>
    <vendor url="https://github.com/shizzhang0">Timothy</vendor>

    <description><![CDATA[
        Manage Maven home, user settings file and local repository as reusable templates,
        and apply them to projects automatically.
        <ul>
          <li>Map a folder to a template: every project under it uses that template, including new clones and git worktrees.</li>
          <li>Override the choice for a single project, or leave a project alone.</li>
          <li>Get notified when the Maven settings of a project drift from its template.</li>
        </ul>
        ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.idea.maven</depends>

    <resource-bundle>messages.MavenSettingsTemplatesBundle</resource-bundle>

    <extensions defaultExtensionNs="com.intellij">
    </extensions>
</idea-plugin>
```

- [ ] **Step 5: 创建 `MstBundle.kt`**

```kotlin
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
```

- [ ] **Step 6: 创建资源包**

`src/main/resources/messages/MavenSettingsTemplatesBundle.properties`：

```properties
# User-visible strings for Maven Settings Templates.
# Avoid apostrophes: messages with parameters go through MessageFormat.
```

- [ ] **Step 7: 重写 `README.md`**

```markdown
# Maven Settings Templates

An IntelliJ IDEA plugin that manages **Maven home**, **user settings file** and **local repository**
as reusable templates and applies them to your projects automatically.

## Why

If you work on projects for different companies or teams, each one usually needs its own
`settings.xml` and local repository. IntelliJ IDEA stores these per project, so every new clone,
every new git worktree and every deleted `.idea` folder starts with the wrong values, and the first
Maven import runs against the wrong repository.

## How it decides

For each project the plugin picks the first match:

1. **Project override**: a template, custom values, or "not managed" chosen for this project.
2. **Folder rule**: a folder mapped to a template. The deepest matching folder wins.
3. **Default template**.
4. Otherwise the plugin leaves the project alone.

Empty template fields mean "use the IDE default" (Bundled Maven 3, `~/.m2/settings.xml`,
`~/.m2/repository`). Paths may contain `${user.home}`.

All configuration is stored at the application level, keyed by project path, so it survives
deleting `.idea` and applies to new git worktrees.

## Drift notifications

When the Maven settings of a project no longer match what the plugin applied, you get a
notification with three choices: **Restore template values**, **Save as project custom**, or
**Ignore** (for this IDE session).

## Settings

`Settings | Build, Execution, Deployment | Build Tools | Maven | Settings Templates`

## Requirements

IntelliJ IDEA 2026.2 or newer with the bundled Maven plugin.

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`.

## License

[Apache License 2.0](LICENSE)
```

- [ ] **Step 8: 构建并核对生成的 plugin.xml**

Run: `./gradlew buildPlugin`
Expected: `BUILD SUCCESSFUL`，`build/distributions/` 下生成 `maven-settings-templates-1.0.0-SNAPSHOT.zip`。

Run: `find build -path "*patchPluginXml*" -name plugin.xml -exec grep -n "idea-version" {} \;`
Expected: 只有 `since-build="262"`，**没有** `until-build`。

如果 `untilBuild = provider { null }` 编译报错，或者生成的文件里仍有 `until-build`，先核实 2.19.0 的 DSL：`find ~/.gradle/caches -name "intellij-platform-gradle-plugin-2.19.0.jar"`，再用 `javap -cp <jar> org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension\$PluginConfiguration\$IdeaVersion` 查看 `untilBuild` 的类型后调整。不要猜。

- [ ] **Step 9: 校验项目配置**

Run: `./gradlew verifyPluginProjectConfiguration`
Expected: `BUILD SUCCESSFUL`，没有报错。

- [ ] **Step 10: 检查点**

向用户汇报：构建通过、生成的 `idea-version` 内容、删除了哪些文件。然后提交（提交约定见 Global Constraints）。

---

### Task 2: 值模型与路径规范化

**Files:**
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/core/MavenValues.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/core/PathNormalizer.kt`
- Temp test（本任务结束前删除）: `src/test/kotlin/io/github/shizzhang0/mavensettingstemplates/core/PathNormalizerTempTest.kt`

**Interfaces:**
- Produces:
  - `enum class MavenHomeKind { BUNDLED_3, WRAPPER, CUSTOM, OTHER }`
  - `data class MavenValues(var homeKind: MavenHomeKind = BUNDLED_3, var homePath: String = "", var userSettingsFile: String = "", var localRepository: String = "") { fun sameAs(other: MavenValues?): Boolean }`
  - `object PathNormalizer { const val USER_HOME_VAR: String; fun expand(path: String, userHome: String = …): String; fun normalize(path: String, userHome: String = …, caseSensitive: Boolean = …): String; fun samePath(a: String, b: String): Boolean; fun isUnder(path: String, folder: String): Boolean }`

- [ ] **Step 1: 写临时测试**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TEMPORARY: delete after verification (project rule: no retained unit tests).
class PathNormalizerTempTest {
    private val home = "C:\\Users\\tim"

    @Test
    fun expandsUserHome() {
        assertEquals("C:\\Users\\tim/.m2/settings.xml", PathNormalizer.expand("\${user.home}/.m2/settings.xml", home))
    }

    @Test
    fun expandTrimsInput() {
        assertEquals("D:\\x", PathNormalizer.expand("  D:\\x  ", home))
    }

    @Test
    fun normalizesSlashesTrailingSlashAndCase() {
        assertEquals("d:/work/companya", PathNormalizer.normalize("D:\\Work\\CompanyA\\", home, caseSensitive = false))
    }

    @Test
    fun keepsCaseOnCaseSensitiveFileSystems() {
        assertEquals("/home/Tim/x", PathNormalizer.normalize("/home/Tim/x/", home, caseSensitive = true))
    }

    @Test
    fun expandsBeforeNormalizing() {
        assertEquals("c:/users/tim/.m2", PathNormalizer.normalize("\${user.home}\\.m2", home, caseSensitive = false))
    }

    @Test
    fun isUnderMatchesFolderItselfAndDescendants() {
        assertTrue(PathNormalizer.isUnder("D:/work/CompanyA", "D:\\work\\CompanyA"))
        assertTrue(PathNormalizer.isUnder("D:/work/CompanyA/app/sub", "D:/work/CompanyA/"))
    }

    @Test
    fun isUnderRejectsSiblingWithSamePrefix() {
        assertFalse(PathNormalizer.isUnder("D:/work/CompanyAB/app", "D:/work/CompanyA"))
    }

    @Test
    fun isUnderRejectsEmptyFolder() {
        assertFalse(PathNormalizer.isUnder("D:/work", ""))
    }

    @Test
    fun sameAsIgnoresSlashStyleAndTrailingSlash() {
        val a = MavenValues(MavenHomeKind.CUSTOM, "D:\\maven\\3.9", "D:\\s.xml", "")
        val b = MavenValues(MavenHomeKind.CUSTOM, "D:/maven/3.9/", "D:/s.xml", "")
        assertTrue(a.sameAs(b))
    }

    @Test
    fun sameAsComparesHomePathOnlyForCustom() {
        assertTrue(MavenValues(MavenHomeKind.WRAPPER, "x").sameAs(MavenValues(MavenHomeKind.WRAPPER, "y")))
        assertFalse(MavenValues(MavenHomeKind.WRAPPER).sameAs(MavenValues(MavenHomeKind.BUNDLED_3)))
        assertFalse(MavenValues().sameAs(null))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests "io.github.shizzhang0.mavensettingstemplates.core.PathNormalizerTempTest"`
Expected: 编译失败，报 `Unresolved reference 'PathNormalizer'` / `'MavenValues'`。

如果报 `NoClassDefFoundError: org/opentest4j/...`，这是平台测试框架在运行时需要 opentest4j，和本任务的代码无关：临时在 `build.gradle.kts` 的 `dependencies` 里加 `testImplementation("org.opentest4j:opentest4j:1.3.0")`，**并在 Step 7 删除临时测试时一并移除**。

- [ ] **Step 3: 实现 `MavenValues.kt`**

```kotlin
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
```

- [ ] **Step 4: 实现 `PathNormalizer.kt`**

```kotlin
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
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests "io.github.shizzhang0.mavensettingstemplates.core.PathNormalizerTempTest"`
Expected: `BUILD SUCCESSFUL`，10 个测试全部通过。

- [ ] **Step 6: 删除临时测试**

```bash
rm src/test/kotlin/io/github/shizzhang0/mavensettingstemplates/core/PathNormalizerTempTest.kt
find src/test -type d -empty -delete
```

如果 Step 2 临时加了 opentest4j 依赖，这里一并从 `build.gradle.kts` 删掉。

- [ ] **Step 7: 确认构建仍然通过**

Run: `./gradlew buildPlugin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 8: 检查点**

向用户汇报测试结果，并说明临时测试已删除。然后提交（提交约定见 Global Constraints）。

---

### Task 3: 数据模型、解析器与决策函数

**Files:**
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/core/model.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/core/SettingsResolver.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/core/Decision.kt`
- Temp tests（本任务结束前删除）: `src/test/kotlin/io/github/shizzhang0/mavensettingstemplates/core/SettingsResolverTempTest.kt`、`DecisionTempTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `MavenHomeKind`、`MavenValues`、`PathNormalizer`。
- Produces:
  - `data class Template(var id: String = UUID, var name: String = "", var mavenHomeKind: MavenHomeKind = BUNDLED_3, var mavenHomePath: String = "", var userSettingsFile: String = "", var localRepository: String = "") { fun toValues(): MavenValues; companion fun fromValues(values: MavenValues): Template }`
  - `data class FolderRule(var folder: String = "", var templateId: String = "", var enabled: Boolean = true)`
  - `enum class BindingMode { TEMPLATE, CUSTOM, NOT_MANAGED }`
  - `data class Binding(var mode: BindingMode = NOT_MANAGED, var templateId: String? = null, var custom: Template? = null) { fun deepCopy(): Binding }`
  - `data class TemplatesConfig(var templates: MutableList<Template>, var defaultTemplateId: String?, var rules: MutableList<FolderRule>) { fun template(id: String?): Template?; fun deepCopy(): TemplatesConfig }`
  - `data class ProjectRecord(var path: String = "", var binding: Binding? = null, var lastApplied: MavenValues? = null) { fun deepCopy(): ProjectRecord }`
  - `sealed interface Source { ProjectTemplate(templateName), ProjectCustom, Rule(folder, templateName), DefaultTemplate(templateName) }`
  - `data class Resolved(val values: MavenValues, val source: Source)`
  - `class SettingsResolver(config: TemplatesConfig) { fun resolve(projectPath: String, binding: Binding?): Resolved?; fun matchingRule(projectPath: String): Pair<FolderRule, Template>? }`
  - `enum class Trigger { OPEN, SETTINGS_APPLIED, CHANGED_AT_RUNTIME }`
  - `enum class Decision { NONE, RECORD, FIRST_APPLY, FOLLOW, NOTIFY_DRIFT }`
  - `fun decide(target: MavenValues?, current: MavenValues, lastApplied: MavenValues?, baseline: MavenValues?, ignored: MavenValues?, trigger: Trigger): Decision`

- [ ] **Step 1: 写解析器的临时测试**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// TEMPORARY: delete after verification (project rule: no retained unit tests).
class SettingsResolverTempTest {
    private val personal = Template(id = "p", name = "Personal")
    private val companyA = Template(id = "a", name = "CompanyA", userSettingsFile = "D:/a/settings.xml")
    private val legacy = Template(id = "l", name = "Legacy", localRepository = "D:/legacy/repo")

    private fun config(
        default: String? = "p",
        rules: List<FolderRule> = listOf(
            FolderRule("D:\\work\\CompanyA", "a"),
            FolderRule("D:\\work\\CompanyA\\legacy", "l"),
        ),
    ) = TemplatesConfig(mutableListOf(personal, companyA, legacy), default, rules.toMutableList())

    @Test
    fun deepestRuleWins() {
        val resolved = SettingsResolver(config()).resolve("D:/work/CompanyA/legacy/app", null)
        assertEquals(Source.Rule("D:\\work\\CompanyA\\legacy", "Legacy"), resolved?.source)
    }

    @Test
    fun parentRuleCoversOtherChildren() {
        val resolved = SettingsResolver(config()).resolve("D:/work/CompanyA/order", null)
        assertEquals(Source.Rule("D:\\work\\CompanyA", "CompanyA"), resolved?.source)
        assertEquals("D:/a/settings.xml", resolved?.values?.userSettingsFile)
    }

    @Test
    fun siblingWithSamePrefixFallsBackToDefault() {
        val resolved = SettingsResolver(config()).resolve("D:/work/CompanyAB/x", null)
        assertEquals(Source.DefaultTemplate("Personal"), resolved?.source)
    }

    @Test
    fun disabledRuleIsSkipped() {
        val rules = listOf(FolderRule("D:\\work\\CompanyA", "a"), FolderRule("D:\\work\\CompanyA\\legacy", "l", enabled = false))
        val resolved = SettingsResolver(config(rules = rules)).resolve("D:/work/CompanyA/legacy/app", null)
        assertEquals(Source.Rule("D:\\work\\CompanyA", "CompanyA"), resolved?.source)
    }

    @Test
    fun ruleWithDeletedTemplateFallsBackToNextRule() {
        val rules = listOf(FolderRule("D:\\work\\CompanyA", "a"), FolderRule("D:\\work\\CompanyA\\legacy", "gone"))
        val resolved = SettingsResolver(config(rules = rules)).resolve("D:/work/CompanyA/legacy/app", null)
        assertEquals(Source.Rule("D:\\work\\CompanyA", "CompanyA"), resolved?.source)
    }

    @Test
    fun nothingMatchesWithoutDefaultReturnsNull() {
        assertNull(SettingsResolver(config(default = null)).resolve("E:/elsewhere", null))
    }

    @Test
    fun notManagedBindingWins() {
        assertNull(SettingsResolver(config()).resolve("D:/work/CompanyA/x", Binding(BindingMode.NOT_MANAGED)))
    }

    @Test
    fun templateBindingWins() {
        val resolved = SettingsResolver(config()).resolve("D:/work/CompanyA/x", Binding(BindingMode.TEMPLATE, templateId = "l"))
        assertEquals(Source.ProjectTemplate("Legacy"), resolved?.source)
    }

    @Test
    fun templateBindingWithDeletedTemplateFallsThroughToRules() {
        val resolved = SettingsResolver(config()).resolve("D:/work/CompanyA/x", Binding(BindingMode.TEMPLATE, templateId = "gone"))
        assertEquals(Source.Rule("D:\\work\\CompanyA", "CompanyA"), resolved?.source)
    }

    @Test
    fun customBindingWins() {
        val binding = Binding(BindingMode.CUSTOM, custom = Template(userSettingsFile = "E:/c.xml"))
        val resolved = SettingsResolver(config()).resolve("D:/work/CompanyA/x", binding)
        assertEquals(Source.ProjectCustom, resolved?.source)
        assertEquals("E:/c.xml", resolved?.values?.userSettingsFile)
    }

    @Test
    fun toValuesExpandsUserHomeAndDropsPathForNonCustomHome() {
        val values = Template(mavenHomeKind = MavenHomeKind.WRAPPER, mavenHomePath = "ignored", localRepository = "\${user.home}/repo").toValues()
        assertEquals("", values.homePath)
        assertEquals(System.getProperty("user.home") + "/repo", values.localRepository)
    }
}
```

- [ ] **Step 2: 写决策函数的临时测试**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.core

import org.junit.Assert.assertEquals
import org.junit.Test

// TEMPORARY: delete after verification (project rule: no retained unit tests).
class DecisionTempTest {
    private val a = MavenValues(userSettingsFile = "D:/a.xml")
    private val b = MavenValues(userSettingsFile = "D:/b.xml")
    private val manual = MavenValues(userSettingsFile = "D:/manual.xml")
    private val baseline = MavenValues()

    private fun decideFor(target: MavenValues?, current: MavenValues, last: MavenValues?, ignored: MavenValues? = null, trigger: Trigger = Trigger.OPEN) =
        decide(target, current, last, baseline, ignored, trigger)

    @Test fun noTargetIsNone() = assertEquals(Decision.NONE, decideFor(null, a, a))

    @Test fun alreadyAtTargetAndRecordedIsNone() = assertEquals(Decision.NONE, decideFor(a, a, a))

    @Test fun alreadyAtTargetButNotRecordedIsRecord() = assertEquals(Decision.RECORD, decideFor(a, a, null))

    @Test fun manuallySetToTargetIsRecord() = assertEquals(Decision.RECORD, decideFor(a, a, b))

    @Test fun neverAppliedIsFirstApply() = assertEquals(Decision.FIRST_APPLY, decideFor(a, baseline, null))

    @Test fun untouchedSinceLastApplyFollowsNewTarget() = assertEquals(Decision.FOLLOW, decideFor(b, a, a, trigger = Trigger.SETTINGS_APPLIED))

    @Test fun recreatedIdeaFolderFollowsOnOpen() = assertEquals(Decision.FOLLOW, decideFor(a, baseline, a, trigger = Trigger.OPEN))

    @Test fun baselineValuesAtRuntimeAreDrift() = assertEquals(Decision.NOTIFY_DRIFT, decideFor(a, baseline, a, trigger = Trigger.CHANGED_AT_RUNTIME))

    @Test fun manualChangeIsDrift() = assertEquals(Decision.NOTIFY_DRIFT, decideFor(a, manual, a, trigger = Trigger.CHANGED_AT_RUNTIME))

    @Test fun ignoredValuesAreNone() = assertEquals(Decision.NONE, decideFor(a, manual, a, ignored = manual, trigger = Trigger.CHANGED_AT_RUNTIME))

    @Test fun ignoreDoesNotCoverNewValues() = assertEquals(Decision.NOTIFY_DRIFT, decideFor(a, b, a, ignored = manual, trigger = Trigger.CHANGED_AT_RUNTIME))
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `./gradlew test --tests "io.github.shizzhang0.mavensettingstemplates.core.*TempTest"`
Expected: 编译失败，报 `Unresolved reference`（`Template`、`SettingsResolver`、`decide` 等）。

- [ ] **Step 4: 实现 `model.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.core

import java.util.UUID

/** A named set of Maven settings. Paths hold raw user input; `${user.home}` is expanded by [toValues]. */
data class Template(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var mavenHomeKind: MavenHomeKind = MavenHomeKind.BUNDLED_3,
    var mavenHomePath: String = "",
    var userSettingsFile: String = "",
    var localRepository: String = "",
) {
    fun toValues(): MavenValues = MavenValues(
        homeKind = mavenHomeKind,
        homePath = if (mavenHomeKind == MavenHomeKind.CUSTOM) PathNormalizer.expand(mavenHomePath) else "",
        userSettingsFile = PathNormalizer.expand(userSettingsFile),
        localRepository = PathNormalizer.expand(localRepository),
    )

    companion object {
        /** Used by "Save as project custom". [values] must not use [MavenHomeKind.OTHER]. */
        fun fromValues(values: MavenValues): Template = Template(
            mavenHomeKind = values.homeKind,
            mavenHomePath = values.homePath,
            userSettingsFile = values.userSettingsFile,
            localRepository = values.localRepository,
        )
    }
}

/** Every project under [folder] uses [templateId]; the deepest matching folder wins (design D4/D5). */
data class FolderRule(
    var folder: String = "",
    var templateId: String = "",
    var enabled: Boolean = true,
)

enum class BindingMode { TEMPLATE, CUSTOM, NOT_MANAGED }

/** A per-project override. A project without one follows folder rules. */
data class Binding(
    var mode: BindingMode = BindingMode.NOT_MANAGED,
    var templateId: String? = null,
    var custom: Template? = null,
) {
    fun deepCopy(): Binding = copy(custom = custom?.copy())
}

/** Everything the user configures; persisted by TemplatesSettings. */
data class TemplatesConfig(
    var templates: MutableList<Template> = mutableListOf(),
    var defaultTemplateId: String? = null,
    var rules: MutableList<FolderRule> = mutableListOf(),
) {
    fun template(id: String?): Template? = id?.let { wanted -> templates.firstOrNull { it.id == wanted } }

    fun deepCopy(): TemplatesConfig = TemplatesConfig(
        templates.mapTo(mutableListOf()) { it.copy() },
        defaultTemplateId,
        rules.mapTo(mutableListOf()) { it.copy() },
    )
}

/** What the plugin knows about one project; persisted by ProjectRecords under the normalized path. */
data class ProjectRecord(
    /** The project base path as the IDE reported it; shown in the UI. */
    var path: String = "",
    var binding: Binding? = null,
    /** Values the plugin last wrote (already expanded); null means never applied. */
    var lastApplied: MavenValues? = null,
) {
    fun deepCopy(): ProjectRecord = copy(binding = binding?.deepCopy(), lastApplied = lastApplied?.copy())
}
```

- [ ] **Step 5: 实现 `SettingsResolver.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.core

/** Where resolved values came from; drives UI and notification text. */
sealed interface Source {
    data class ProjectTemplate(val templateName: String) : Source
    data object ProjectCustom : Source
    data class Rule(val folder: String, val templateName: String) : Source
    data class DefaultTemplate(val templateName: String) : Source
}

data class Resolved(val values: MavenValues, val source: Source)

/** Design §5.1: project binding > deepest folder rule > default template > leave alone. */
class SettingsResolver(private val config: TemplatesConfig) {

    /** Returns null when the plugin must leave the project alone. */
    fun resolve(projectPath: String, binding: Binding?): Resolved? {
        if (binding != null) {
            when (binding.mode) {
                BindingMode.NOT_MANAGED -> return null
                BindingMode.CUSTOM -> binding.custom?.let { return Resolved(it.toValues(), Source.ProjectCustom) }
                BindingMode.TEMPLATE -> config.template(binding.templateId)?.let {
                    return Resolved(it.toValues(), Source.ProjectTemplate(it.name))
                }
            }
        }
        matchingRule(projectPath)?.let { (rule, template) ->
            return Resolved(template.toValues(), Source.Rule(rule.folder, template.name))
        }
        config.template(config.defaultTemplateId)?.let {
            return Resolved(it.toValues(), Source.DefaultTemplate(it.name))
        }
        return null
    }

    /** The deepest enabled rule containing [projectPath] whose template still exists. */
    fun matchingRule(projectPath: String): Pair<FolderRule, Template>? =
        config.rules.asSequence()
            .filter { it.enabled && PathNormalizer.isUnder(projectPath, it.folder) }
            .mapNotNull { rule -> config.template(rule.templateId)?.let { rule to it } }
            .maxByOrNull { (rule, _) -> PathNormalizer.normalize(rule.folder).length }
}
```

- [ ] **Step 6: 实现 `Decision.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.core

enum class Trigger { OPEN, SETTINGS_APPLIED, CHANGED_AT_RUNTIME }

enum class Decision {
    /** Nothing to do. */
    NONE,

    /** The IDE already has the target values; only remember them as lastApplied. */
    RECORD,

    /** First time this project is managed: write, sync, offer Undo. */
    FIRST_APPLY,

    /** Nobody touched what we wrote, or `.idea` was recreated: write and sync silently. */
    FOLLOW,

    /** Someone changed the values by hand: ask the user. */
    NOTIFY_DRIFT,
}

/**
 * Design §5.2. [target] null means "leave the project alone". [baseline] (what a brand-new project gets)
 * is only consulted on [Trigger.OPEN]: matching it then means `.idea` was recreated, not edited by hand.
 */
fun decide(
    target: MavenValues?,
    current: MavenValues,
    lastApplied: MavenValues?,
    baseline: MavenValues?,
    ignored: MavenValues?,
    trigger: Trigger,
): Decision = when {
    target == null -> Decision.NONE
    current.sameAs(target) -> if (target.sameAs(lastApplied)) Decision.NONE else Decision.RECORD
    lastApplied == null -> Decision.FIRST_APPLY
    current.sameAs(lastApplied) -> Decision.FOLLOW
    trigger == Trigger.OPEN && current.sameAs(baseline) -> Decision.FOLLOW
    current.sameAs(ignored) -> Decision.NONE
    else -> Decision.NOTIFY_DRIFT
}
```

- [ ] **Step 7: 运行，确认通过**

Run: `./gradlew test --tests "io.github.shizzhang0.mavensettingstemplates.core.*TempTest"`
Expected: `BUILD SUCCESSFUL`，22 个测试全部通过（解析器 11 个，决策 11 个）。

- [ ] **Step 8: 删除临时测试并确认构建**

```bash
rm src/test/kotlin/io/github/shizzhang0/mavensettingstemplates/core/SettingsResolverTempTest.kt src/test/kotlin/io/github/shizzhang0/mavensettingstemplates/core/DecisionTempTest.kt
find src/test -type d -empty -delete
./gradlew buildPlugin
```

Expected: `BUILD SUCCESSFUL`。如果 Task 2 里临时加过 opentest4j 依赖，确认它已经从 `build.gradle.kts` 移除。

- [ ] **Step 9: 检查点**

向用户汇报测试结果，并说明临时测试已删除。然后提交（提交约定见 Global Constraints）。

---

### Task 4: 持久化、Maven 访问层与模板编辑页

**Files:**
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/settings/TemplatesSettings.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/settings/ProjectRecords.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/maven/MavenSettingsAccess.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/Presentation.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/Renderers.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/TemplateEditor.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/TemplatesPanel.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/MavenSettingsTemplatesConfigurable.kt`（本任务只有模板区块，Task 5 整体替换）
- Modify: `plugin.xml`、`MavenSettingsTemplatesBundle.properties`

**Interfaces:**
- Consumes: Task 2、3 的 core 类型。
- Produces:
  - `class TemplatesSettings { fun snapshot(): TemplatesConfig; fun replace(newConfig: TemplatesConfig); companion fun getInstance() }`
  - `class ProjectRecords { fun get(key: String): ProjectRecord?; fun all(): List<Pair<String, ProjectRecord>>; fun update(key: String, path: String, change: (ProjectRecord) -> Unit); fun remove(keys: Collection<String>); companion fun getInstance(); companion fun keyOf(projectPath: String): String }`
  - `object MavenSettingsAccess { class RawSnapshot; fun settingsIdentity(project): Any; fun read(project): MavenValues; fun snapshot(project): RawSnapshot; fun write(project, values: MavenValues); fun restore(project, snapshot: RawSnapshot); fun sync(project); fun listen(project, parent: Disposable, onChange: () -> Unit): Any; fun homeKindTitle(kind: MavenHomeKind): String? }`
  - `internal object Presentation { fun describe(source: Source): String; fun homeKind(kind): String; fun home(values): String; fun path(value: String): String; fun summary(values): String }`
  - `internal class TemplateEditor(project: Project?, showName: Boolean) { val nameField: JBTextField; val component: JComponent; fun load(template: Template); fun saveTo(template: Template); fun setEnabled(enabled: Boolean) }`
  - `internal class TemplatesPanel(project: Project?, countReferences: (String) -> Pair<Int, Int>, onChanged: () -> Unit) { val component: JComponent; val defaultTemplateId: String?; fun reset(templates: List<Template>, defaultId: String?); fun currentTemplates(): List<Template> }`

- [ ] **Step 1: 追加消息**

在 `MavenSettingsTemplatesBundle.properties` 末尾追加：

```properties
configurable.displayName=Settings Templates
section.templates=Templates
field.name=Name:
field.mavenHome=Maven home:
field.userSettings=User settings file:
field.localRepository=Local repository:
field.hint.ideDefault=IDE default: {0}
home.custom=Custom path
editor.comment=Empty fields use the IDE defaults. {0} is replaced with your home folder.
editor.missingPaths=Does not exist: {0}
templates.newName=New template
templates.unnamed=(unnamed)
templates.copy=Copy
templates.copyName={0} (copy)
templates.toggleDefault=Set as Default / Unset Default
templates.delete.title=Delete Template
templates.delete.message=Template "{0}" is used by {1} folder rule(s) and {2} project(s). They will fall back to the next priority. Delete it anyway?
source.projectTemplate=template "{0}"
source.projectCustom=custom values of this project
source.rule=template "{0}" (folder rule {1})
source.default=default template "{0}"
value.ideDefault=(IDE default)
values.home=Maven home: {0}
values.userSettings=User settings file: {0}
values.localRepository=Local repository: {0}
```

- [ ] **Step 2: 实现 `TemplatesSettings.kt`**

```kotlin
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
```

- [ ] **Step 3: 实现 `ProjectRecords.kt`**

```kotlin
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
```

- [ ] **Step 4: 实现 `MavenSettingsAccess.kt`**

这里用到的每个 Maven API 都已在设计文档 §3 中核实，且没有标 `@ApiStatus.Internal`。

```kotlin
package io.github.shizzhang0.mavensettingstemplates.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec
import org.jetbrains.idea.maven.project.BundledMaven3
import org.jetbrains.idea.maven.project.MavenGeneralSettings
import org.jetbrains.idea.maven.project.MavenHomeType
import org.jetbrains.idea.maven.project.MavenInSpecificPath
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSettingsCache
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent
import org.jetbrains.idea.maven.project.MavenWrapper

/**
 * The only file that touches the Maven plugin API. Every call was verified with javap against
 * IntelliJ IDEA 2026.2.3 (build 262.10968.63); see design §3.
 */
object MavenSettingsAccess {

    /** Raw IDE values kept in memory so Undo can restore even home types this plugin cannot model. */
    class RawSnapshot internal constructor(
        internal val homeType: MavenHomeType,
        internal val userSettingsFile: String,
        internal val localRepository: String,
    )

    /** The settings object is replaced when workspace.xml is reloaded (design §3.5). */
    private fun generalSettings(project: Project): MavenGeneralSettings =
        MavenWorkspaceSettingsComponent.getInstance(project).settings.generalSettings

    /** Identity of the current settings object, used to detect replacement. */
    fun settingsIdentity(project: Project): Any = generalSettings(project)

    fun read(project: Project): MavenValues {
        val settings = generalSettings(project)
        val (kind, path) = when (val home = settings.mavenHomeType) {
            BundledMaven3 -> MavenHomeKind.BUNDLED_3 to ""
            MavenWrapper -> MavenHomeKind.WRAPPER to ""
            is MavenInSpecificPath -> MavenHomeKind.CUSTOM to home.mavenHome
            else -> MavenHomeKind.OTHER to home.title
        }
        return MavenValues(kind, path, settings.userSettingsFile.orEmpty(), settings.localRepository.orEmpty())
    }

    fun snapshot(project: Project): RawSnapshot {
        val settings = generalSettings(project)
        return RawSnapshot(settings.mavenHomeType, settings.userSettingsFile.orEmpty(), settings.localRepository.orEmpty())
    }

    /** Writes [values] on the caller's thread; the batch fires a single `changed()`. */
    fun write(project: Project, values: MavenValues) {
        val home = when (values.homeKind) {
            MavenHomeKind.BUNDLED_3 -> BundledMaven3
            MavenHomeKind.WRAPPER -> MavenWrapper
            MavenHomeKind.CUSTOM -> MavenInSpecificPath(values.homePath)
            MavenHomeKind.OTHER -> error("OTHER home types are never written")
        }
        writeRaw(project, home, values.userSettingsFile, values.localRepository)
    }

    fun restore(project: Project, snapshot: RawSnapshot) {
        writeRaw(project, snapshot.homeType, snapshot.userSettingsFile, snapshot.localRepository)
    }

    private fun writeRaw(project: Project, home: MavenHomeType, userSettingsFile: String, localRepository: String) {
        val settings = generalSettings(project)
        settings.beginUpdate()
        try {
            settings.mavenHomeType = home
            // Explicit setters: Kotlin exposes these two as read-only properties (getter/setter types differ).
            settings.setUserSettingsFile(userSettingsFile)
            settings.setLocalRepository(localRepository)
        } finally {
            settings.endUpdate()
        }
    }

    /** Refreshes Maven's cached effective paths and, for Maven projects, schedules a full sync. Blocking: call off the EDT. */
    fun sync(project: Project) {
        MavenSettingsCache.getInstance(project).reload()
        val manager = MavenProjectsManager.getInstance(project)
        if (manager.isMavenizedProject) {
            manager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("Maven Settings Templates"))
        }
    }

    /** Listens to the current settings object until [parent] is disposed; returns that object's identity. */
    fun listen(project: Project, parent: Disposable, onChange: () -> Unit): Any {
        val settings = generalSettings(project)
        settings.addListener(MavenGeneralSettings.Listener { onChange() }, parent)
        return settings
    }

    /** The IDE's own label for a home kind, e.g. "Bundled (Maven 3)"; null for kinds the IDE does not name. */
    fun homeKindTitle(kind: MavenHomeKind): String? = when (kind) {
        MavenHomeKind.BUNDLED_3 -> BundledMaven3.title
        MavenHomeKind.WRAPPER -> MavenWrapper.title
        MavenHomeKind.CUSTOM, MavenHomeKind.OTHER -> null
    }
}
```

- [ ] **Step 5: 实现 `Presentation.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates

import com.intellij.openapi.util.io.FileUtil
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.core.Source
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess

/** User-facing text for resolved sources and values, shared by the settings page and notifications. */
internal object Presentation {
    fun describe(source: Source): String = when (source) {
        is Source.ProjectTemplate -> MstBundle.message("source.projectTemplate", source.templateName)
        Source.ProjectCustom -> MstBundle.message("source.projectCustom")
        is Source.Rule -> MstBundle.message("source.rule", source.templateName, FileUtil.toSystemDependentName(source.folder))
        is Source.DefaultTemplate -> MstBundle.message("source.default", source.templateName)
    }

    fun homeKind(kind: MavenHomeKind): String =
        MavenSettingsAccess.homeKindTitle(kind) ?: MstBundle.message("home.custom")

    fun home(values: MavenValues): String = when (values.homeKind) {
        MavenHomeKind.CUSTOM, MavenHomeKind.OTHER -> values.homePath
        MavenHomeKind.BUNDLED_3, MavenHomeKind.WRAPPER -> homeKind(values.homeKind)
    }

    fun path(value: String): String = value.ifEmpty { MstBundle.message("value.ideDefault") }

    fun summaryLines(values: MavenValues): List<String> = listOf(
        MstBundle.message("values.home", home(values)),
        MstBundle.message("values.userSettings", path(values.userSettingsFile)),
        MstBundle.message("values.localRepository", path(values.localRepository)),
    )
}
```

- [ ] **Step 5b: 实现 `ui/Renderers.kt`**

`SimpleListCellRenderer.create(...)` 的两个重载在 2026.2 都已标记为 deprecated，统一改用 Kotlin UI DSL 的 `textListCellRenderer`（已用 javap 核实存在且未 deprecated）：

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.ListCellRenderer

/** A list renderer showing [text] for each item (SimpleListCellRenderer.create is deprecated in 2026.2). */
internal fun <T : Any> textRenderer(text: (T) -> String): ListCellRenderer<T?> =
    textListCellRenderer { value: T? -> value?.let(text).orEmpty() }
```

- [ ] **Step 6: 实现 `TemplateEditor.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Edits the Maven fields of one template; also reused for per-project custom values. */
internal class TemplateEditor(project: Project?, showName: Boolean) {
    val nameField = JBTextField()

    private val homeKindCombo = ComboBox(
        CollectionComboBoxModel(listOf(MavenHomeKind.BUNDLED_3, MavenHomeKind.WRAPPER, MavenHomeKind.CUSTOM)),
    ).apply {
        renderer = textRenderer(Presentation::homeKind)
    }
    private val homePathField = pathField(project, FileChooserDescriptorFactory.singleDir(), "field.mavenHome", null)
    private val userSettingsField = pathField(project, FileChooserDescriptorFactory.singleFile(), "field.userSettings", ".m2/settings.xml")
    private val localRepositoryField = pathField(project, FileChooserDescriptorFactory.singleDir(), "field.localRepository", ".m2/repository")

    /** Missing paths are only a warning; saving is never blocked (design §8, §11). */
    private val missingPathsLabel = JBLabel().apply {
        icon = AllIcons.General.Warning
        isVisible = false
    }

    val component: JComponent = panel {
        if (showName) {
            row(MstBundle.message("field.name")) { cell(nameField).align(AlignX.FILL) }
        }
        // One cell for both, otherwise the combo shares a grid column with the full-width fields and stretches.
        row(MstBundle.message("field.mavenHome")) {
            cell(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(homeKindCombo, BorderLayout.WEST)
                add(homePathField, BorderLayout.CENTER)
            }).align(AlignX.FILL)
        }
        row(MstBundle.message("field.userSettings")) { cell(userSettingsField).align(AlignX.FILL) }
        row(MstBundle.message("field.localRepository")) { cell(localRepositoryField).align(AlignX.FILL) }
        row { cell(missingPathsLabel) }
        row { comment(MstBundle.message("editor.comment", PathNormalizer.USER_HOME_VAR)) }
    }

    init {
        homeKindCombo.addActionListener {
            updateHomePathState()
            updateMissingPaths()
        }
        listOf(homePathField, userSettingsField, localRepositoryField).forEach { field ->
            field.textField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = updateMissingPaths()
            })
        }
        updateHomePathState()
    }

    fun load(template: Template) {
        nameField.text = template.name
        homeKindCombo.selectedItem = template.mavenHomeKind
        homePathField.text = template.mavenHomePath
        userSettingsField.text = template.userSettingsFile
        localRepositoryField.text = template.localRepository
        updateHomePathState()
    }

    fun saveTo(template: Template) {
        template.name = nameField.text.trim()
        template.mavenHomeKind = homeKindCombo.selectedItem as? MavenHomeKind ?: MavenHomeKind.BUNDLED_3
        template.mavenHomePath = homePathField.text.trim()
        template.userSettingsFile = userSettingsField.text.trim()
        template.localRepository = localRepositoryField.text.trim()
    }

    fun setEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        homeKindCombo.isEnabled = enabled
        userSettingsField.isEnabled = enabled
        localRepositoryField.isEnabled = enabled
        updateHomePathState()
    }

    private fun updateHomePathState() {
        homePathField.isEnabled = homeKindCombo.isEnabled && homeKindCombo.selectedItem == MavenHomeKind.CUSTOM
    }

    private fun updateMissingPaths() {
        val candidates = buildList {
            if (homeKindCombo.selectedItem == MavenHomeKind.CUSTOM) add(homePathField.text)
            add(userSettingsField.text)
            add(localRepositoryField.text)
        }
        val missing = candidates.filter { it.isNotBlank() }
            .map { FileUtil.toSystemDependentName(PathNormalizer.expand(it)) }
            .filterNot { File(it).exists() }
        missingPathsLabel.text = MstBundle.message("editor.missingPaths", missing.joinToString(", "))
        missingPathsLabel.isVisible = missing.isNotEmpty()
    }

    private fun pathField(
        project: Project?,
        descriptor: FileChooserDescriptor,
        titleKey: String,
        defaultUnderHome: String?,
    ): TextFieldWithBrowseButton = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, descriptor.withTitle(MstBundle.message(titleKey).removeSuffix(":")))
        if (defaultUnderHome != null) {
            val defaultPath = FileUtil.toSystemDependentName(System.getProperty("user.home") + "/" + defaultUnderHome)
            // The default constructor creates an ExtendableTextField, which is a JBTextField (verified via javap).
            (textField as? JBTextField)?.emptyText?.text = MstBundle.message("field.hint.ideDefault", defaultPath)
        }
    }
}
```

- [ ] **Step 7: 实现 `TemplatesPanel.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.util.UUID
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Master-detail editor: the template list on the left, a [TemplateEditor] on the right. */
internal class TemplatesPanel(
    private val project: Project?,
    private val countReferences: (templateId: String) -> Pair<Int, Int>,
    private val onChanged: () -> Unit,
) {
    private val model = CollectionListModel<Template>()
    private val list = JBList(model)
    private val editor = TemplateEditor(project, showName = true)
    private var editing: Template? = null
    private var loading = false

    var defaultTemplateId: String? = null
        private set

    val component: JComponent

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = textRenderer { template ->
            val name = template.name.ifBlank { MstBundle.message("templates.unnamed") }
            if (template.id == defaultTemplateId) "★ $name" else name
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) select(list.selectedValue) }
        editor.nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (loading) return
                editing?.let(editor::saveTo)
                list.repaint()
                onChanged()
            }
        })
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addTemplate(Template(name = MstBundle.message("templates.newName"))) }
            .setRemoveAction { removeSelected() }
            .addExtraAction(DumbAwareAction.create(MstBundle.message("templates.copy"), AllIcons.Actions.Copy) { copySelected() })
            .addExtraAction(DumbAwareAction.create(MstBundle.message("templates.toggleDefault"), AllIcons.Nodes.Favorite) { toggleDefault() })
            .disableUpDownActions()
            .createPanel()
        component = JBSplitter(false, 0.3f).apply {
            firstComponent = decorated
            secondComponent = editor.component
        }
        select(null)
    }

    fun reset(templates: List<Template>, defaultId: String?) {
        editing = null
        defaultTemplateId = defaultId
        model.replaceAll(templates.map { it.copy() })
        list.clearSelection()
        if (model.size > 0) list.selectedIndex = 0
    }

    /** The edited templates, as copies. */
    fun currentTemplates(): List<Template> {
        editing?.let(editor::saveTo)
        return model.items.map { it.copy() }
    }

    private fun select(template: Template?) {
        editing?.let(editor::saveTo)
        editing = template
        loading = true
        try {
            editor.load(template ?: Template(name = ""))
        } finally {
            loading = false
        }
        editor.setEnabled(template != null)
    }

    private fun addTemplate(template: Template) {
        model.add(template)
        list.selectedIndex = model.size - 1
        onChanged()
    }

    private fun copySelected() {
        editing?.let(editor::saveTo)
        val source = list.selectedValue ?: return
        addTemplate(source.copy(id = UUID.randomUUID().toString(), name = MstBundle.message("templates.copyName", source.name)))
    }

    private fun removeSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val template = model.getElementAt(index)
        val (rules, projects) = countReferences(template.id)
        if (rules + projects > 0) {
            val answer = Messages.showYesNoDialog(
                project,
                MstBundle.message("templates.delete.message", template.name, rules, projects),
                MstBundle.message("templates.delete.title"),
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        editing = null
        model.remove(index)
        if (template.id == defaultTemplateId) defaultTemplateId = null
        list.clearSelection()
        if (model.size > 0) list.selectedIndex = minOf(index, model.size - 1)
        onChanged()
    }

    private fun toggleDefault() {
        val template = list.selectedValue ?: return
        defaultTemplateId = if (defaultTemplateId == template.id) null else template.id
        list.repaint()
        onChanged()
    }
}
```

- [ ] **Step 8: 实现配置页（本任务只有模板区块）**

`MavenSettingsTemplatesConfigurable.kt`：

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.TemplatesConfig
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings
import javax.swing.JComponent

/** Build Tools > Maven > Settings Templates (design §8). Task 5 adds the remaining sections. */
class MavenSettingsTemplatesConfigurable(private val project: Project) : Configurable {

    private var templatesPanel: TemplatesPanel? = null

    override fun getDisplayName(): String = MstBundle.message("configurable.displayName")

    override fun createComponent(): JComponent {
        val templates = TemplatesPanel(project, countReferences = { 0 to 0 }, onChanged = {})
        templatesPanel = templates
        return panel {
            group(MstBundle.message("section.templates")) {
                row { cell(templates.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
        }
    }

    override fun isModified(): Boolean = pendingConfig() != TemplatesSettings.getInstance().snapshot()

    override fun apply() {
        TemplatesSettings.getInstance().replace(pendingConfig())
    }

    override fun reset() {
        val config = TemplatesSettings.getInstance().snapshot()
        templatesPanel?.reset(config.templates, config.defaultTemplateId)
    }

    override fun disposeUIResources() {
        templatesPanel = null
    }

    private fun pendingConfig(): TemplatesConfig {
        val persisted = TemplatesSettings.getInstance().snapshot()
        val templates = templatesPanel ?: return persisted
        return persisted.copy(templates = templates.currentTemplates().toMutableList(), defaultTemplateId = templates.defaultTemplateId)
    }
}
```

- [ ] **Step 9: 注册配置页**

在 `plugin.xml` 的 `<extensions defaultExtensionNs="com.intellij">` 里加入：

```xml
        <projectConfigurable parentId="MavenSettings"
                             id="io.github.shizzhang0.mavensettingstemplates"
                             instance="io.github.shizzhang0.mavensettingstemplates.ui.MavenSettingsTemplatesConfigurable"
                             key="configurable.displayName"/>
```

- [ ] **Step 10: 构建**

Run: `./gradlew buildPlugin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 11: 在 runIde 中手动验证**

Run: `./gradlew runIde`（会打开一个沙盒 IDE）。在沙盒 IDE 里随便打开或新建一个项目，然后逐项检查：

1. 页面位于 `Settings > Build, Execution, Deployment > Build Tools > Maven > Settings Templates`。**如果没挂到 Maven 下面**，把 `parentId="MavenSettings"` 改成 `parentId="tools"`（这是设计 §8 规定的降级位置），并在检查点里告诉用户。
2. 点 `+` 新建两个模板：`Personal` 和 `CompanyA`。`CompanyA` 的 User settings file 填 `${user.home}\.m2\settings-a.xml`，Maven home 选 `Custom path` 后，路径框才可以编辑。
3. 留空的字段显示灰色占位文字 `IDE default: C:\Users\...\.m2\settings.xml`。填一个不存在的路径（例如 `D:\nope\settings.xml`），编辑器下方出现带警告图标的 `Does not exist: D:\nope\settings.xml`，但 Apply 仍然可以保存。
4. 选中 `Personal` 后点星标按钮，列表中显示为 `★ Personal`；再点一次取消。
5. 点 Apply 后关闭设置，再重新打开：模板和默认标记都还在。
6. 复制按钮生成 `CompanyA (copy)`；删除按钮可以删除它（本任务没有规则，所以不会弹确认框）。
7. 关闭沙盒 IDE，执行 `find .intellijPlatform/sandbox -name mavenSettingsTemplates.xml -exec cat {} \;`，文件里能看到模板和 `defaultTemplateId`。

- [ ] **Step 12: 检查点**

向用户汇报验证结果，包括页面实际挂载的位置。然后提交（提交约定见 Global Constraints）。

---

### Task 5: 规则、项目记录与当前项目

**Files:**
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/RulesPanel.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/ProjectRecordsPanel.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/CurrentProjectPanel.kt`
- Modify: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/ui/MavenSettingsTemplatesConfigurable.kt`（整体替换）
- Modify: `MavenSettingsTemplatesBundle.properties`

**Interfaces:**
- Consumes: Task 4 的 `TemplatesPanel`、`TemplateEditor`、`TemplatesSettings`、`ProjectRecords`、`Presentation`；Task 3 的 `SettingsResolver`、`Binding`、`BindingMode`。
- Produces:
  - `internal class RulesPanel(project: Project?, templates: () -> List<Template>) { val component; fun reset(newRules: List<FolderRule>); fun currentRules(): List<FolderRule>; fun countReferences(templateId: String): Int; fun refresh() }`
  - `internal class ProjectRecordsPanel(templates: () -> List<Template>) { val component; fun reset(records: List<Pair<String, ProjectRecord>>); fun removedKeys(): Set<String>; fun refresh() }`
  - `internal class CurrentProjectPanel(project: Project, templates: () -> List<Template>, onChanged: () -> Unit) { val component; fun reset(binding: Binding?); fun currentBinding(): Binding?; fun refreshTemplates(selectedId: String? = …); fun setEffective(text: String) }`
  - `MavenSettingsTemplatesConfigurable` 的最终结构（Task 6 会在 `apply()` 末尾加一行）。

- [ ] **Step 1: 追加消息**

```properties
section.current=Current Project
section.rules=Folder Rules (deepest matching folder wins)
section.records=Project Records
rules.column.enabled=Enabled
rules.column.folder=Folder
rules.column.template=Template
rules.chooseFolder=Choose Folder
rules.missingTemplate=Missing template
rules.duplicate.title=Duplicate Folder
rules.duplicate.message=A rule for {0} already exists.
records.column.path=Path
records.column.binding=Binding
records.column.status=Status
records.missing=Missing
records.removeMissing=Remove Missing
binding.followRules=Follow rules
binding.notManaged=Not managed
binding.custom=Custom values
binding.template=Template "{0}"
binding.missingTemplate=Missing template
current.follow=Follow folder rules and the default template
current.template=Use template:
current.custom=Custom values:
current.notManaged=Not managed (leave the Maven settings of this project alone)
current.effective=Effective: {0}
current.effective.none=Effective: not managed. The plugin leaves the Maven settings of this project alone.
```

- [ ] **Step 2: 实现 `RulesPanel.kt`**

模板列要用 `ColoredTableCellRenderer`，不能用 `DefaultTableCellRenderer`：后者一旦调用 `setForeground`，颜色会一直留在渲染器上，后面画的行也会跟着变红。

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.FolderRule
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer
import io.github.shizzhang0.mavensettingstemplates.core.Template
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor

/** Folder -> template rules. The deepest matching folder wins, so rows are sorted by path and never reordered. */
internal class RulesPanel(private val project: Project?, private val templates: () -> List<Template>) {
    private val rules = mutableListOf<FolderRule>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount(): Int = rules.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            COLUMN_ENABLED -> MstBundle.message("rules.column.enabled")
            COLUMN_FOLDER -> MstBundle.message("rules.column.folder")
            else -> MstBundle.message("rules.column.template")
        }

        override fun getColumnClass(column: Int): Class<*> =
            if (column == COLUMN_ENABLED) Boolean::class.javaObjectType else Any::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = column != COLUMN_FOLDER

        override fun getValueAt(row: Int, column: Int): Any? = when (column) {
            COLUMN_ENABLED -> rules[row].enabled
            COLUMN_FOLDER -> rules[row].folder
            else -> templates().firstOrNull { it.id == rules[row].templateId }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            when (column) {
                COLUMN_ENABLED -> rules[row].enabled = value as Boolean
                COLUMN_TEMPLATE -> (value as? Template)?.let { rules[row].templateId = it.id }
            }
            fireTableCellUpdated(row, column)
        }
    }

    private val table = object : JBTable(tableModel) {
        override fun getCellEditor(row: Int, column: Int): TableCellEditor =
            if (column == COLUMN_TEMPLATE) DefaultCellEditor(templateCombo()) else super.getCellEditor(row, column)
    }

    val component: JComponent

    init {
        table.columnModel.getColumn(COLUMN_ENABLED).maxWidth = JBUI.scale(70)
        table.columnModel.getColumn(COLUMN_TEMPLATE).cellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val template = value as? Template
                if (template != null) {
                    append(template.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    append(MstBundle.message("rules.missingTemplate"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
        component = ToolbarDecorator.createDecorator(table)
            .setAddAction { addRule() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
    }

    fun reset(newRules: List<FolderRule>) {
        stopEditing()
        rules.clear()
        rules += newRules.map { it.copy() }
        sortRules()
        tableModel.fireTableDataChanged()
    }

    fun currentRules(): List<FolderRule> {
        stopEditing()
        return rules.map { it.copy() }
    }

    fun countReferences(templateId: String): Int = rules.count { it.templateId == templateId }

    /** Repaints template names after templates were renamed, added or removed. */
    fun refresh() {
        stopEditing()
        tableModel.fireTableDataChanged()
    }

    private fun addRule() {
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(MstBundle.message("rules.chooseFolder"))
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val folder = FileUtil.toSystemDependentName(chosen.path)
        if (rules.any { PathNormalizer.samePath(it.folder, folder) }) {
            Messages.showErrorDialog(project, MstBundle.message("rules.duplicate.message", folder), MstBundle.message("rules.duplicate.title"))
            return
        }
        rules += FolderRule(folder = folder, templateId = templates().firstOrNull()?.id.orEmpty())
        sortRules()
        tableModel.fireTableDataChanged()
    }

    private fun removeSelected() {
        stopEditing()
        table.selectedRows.sortedDescending().forEach { rules.removeAt(it) }
        tableModel.fireTableDataChanged()
    }

    private fun templateCombo(): ComboBox<Template> =
        ComboBox(CollectionComboBoxModel(templates())).apply {
            renderer = textRenderer(Template::name)
        }

    private fun sortRules() = rules.sortBy { PathNormalizer.normalize(it.folder) }

    private fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    private companion object {
        const val COLUMN_ENABLED = 0
        const val COLUMN_FOLDER = 1
        const val COLUMN_TEMPLATE = 2
    }
}
```

- [ ] **Step 3: 实现 `ProjectRecordsPanel.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.ProjectRecord
import io.github.shizzhang0.mavensettingstemplates.core.Template
import java.io.File
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/** Every project the plugin has seen. Removing a record makes the next open of that project a first apply. */
internal class ProjectRecordsPanel(private val templates: () -> List<Template>) {
    private val rows = mutableListOf<Pair<String, ProjectRecord>>()
    private val pendingRemovals = mutableSetOf<String>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> MstBundle.message("records.column.path")
            1 -> MstBundle.message("records.column.binding")
            else -> MstBundle.message("records.column.status")
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val record = rows[row].second
            return when (column) {
                0 -> FileUtil.toSystemDependentName(record.path)
                1 -> describeBinding(record.binding)
                else -> if (File(record.path).exists()) "" else MstBundle.message("records.missing")
            }
        }
    }

    private val table = JBTable(tableModel)

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .disableAddAction()
        .setRemoveAction { remove(table.selectedRows.map { rows[it].first }) }
        .addExtraAction(DumbAwareAction.create(MstBundle.message("records.removeMissing"), AllIcons.Actions.GC) {
            remove(rows.filterNot { File(it.second.path).exists() }.map { it.first })
        })
        .disableUpDownActions()
        .createPanel()

    fun reset(records: List<Pair<String, ProjectRecord>>) {
        pendingRemovals.clear()
        rows.clear()
        rows += records.sortedBy { it.second.path.lowercase() }
        tableModel.fireTableDataChanged()
    }

    fun removedKeys(): Set<String> = pendingRemovals.toSet()

    fun refresh() = tableModel.fireTableDataChanged()

    private fun remove(keys: Collection<String>) {
        pendingRemovals += keys
        rows.removeAll { it.first in keys }
        tableModel.fireTableDataChanged()
    }

    private fun describeBinding(binding: Binding?): String {
        if (binding == null) return MstBundle.message("binding.followRules")
        return when (binding.mode) {
            BindingMode.NOT_MANAGED -> MstBundle.message("binding.notManaged")
            BindingMode.CUSTOM -> MstBundle.message("binding.custom")
            BindingMode.TEMPLATE -> templates().firstOrNull { it.id == binding.templateId }
                ?.let { MstBundle.message("binding.template", it.name) }
                ?: MstBundle.message("binding.missingTemplate")
        }
    }
}
```

- [ ] **Step 4: 实现 `CurrentProjectPanel.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.Template
import javax.swing.JComponent

/** The "Current Project" block. Not shown for the default project ("Settings for New Projects"). */
internal class CurrentProjectPanel(
    project: Project,
    private val templates: () -> List<Template>,
    private val onChanged: () -> Unit,
) {
    private val followRadio = JBRadioButton(MstBundle.message("current.follow"))
    private val templateRadio = JBRadioButton(MstBundle.message("current.template"))
    private val customRadio = JBRadioButton(MstBundle.message("current.custom"))
    private val notManagedRadio = JBRadioButton(MstBundle.message("current.notManaged"))
    private val radios = listOf(followRadio, templateRadio, customRadio, notManagedRadio)
    private val templateCombo = ComboBox<Template>().apply {
        renderer = textRenderer(Template::name)
    }
    private val customEditor = TemplateEditor(project, showName = false)
    private val effectiveLabel = JBLabel()
    private var customValues = Template()
    private lateinit var customRow: Row

    val component: JComponent = panel {
        row { label(FileUtil.toSystemDependentName(project.basePath.orEmpty())) }
        // The DSL rejects radio buttons outside buttonsGroup and creates the ButtonGroup itself.
        buttonsGroup {
            row { cell(followRadio) }
            row {
                cell(templateRadio)
                cell(templateCombo)
            }
            row { cell(customRadio) }
            customRow = row { cell(customEditor.component).align(AlignX.FILL) }
            row { cell(notManagedRadio) }
        }
        row { cell(effectiveLabel) }
    }

    init {
        radios.forEach { radio ->
            radio.addActionListener {
                updateEnabledState()
                onChanged()
            }
        }
        templateCombo.addActionListener { onChanged() }
    }

    fun reset(binding: Binding?) {
        customValues = binding?.custom?.copy() ?: Template()
        customEditor.load(customValues)
        refreshTemplates(binding?.templateId)
        val selected = when (binding?.mode) {
            null -> followRadio
            BindingMode.TEMPLATE -> templateRadio
            BindingMode.CUSTOM -> customRadio
            BindingMode.NOT_MANAGED -> notManagedRadio
        }
        selected.isSelected = true
        updateEnabledState()
    }

    /** The binding being edited; null means "follow folder rules". */
    fun currentBinding(): Binding? = when {
        templateRadio.isSelected -> Binding(BindingMode.TEMPLATE, templateId = (templateCombo.selectedItem as? Template)?.id)
        customRadio.isSelected -> {
            customEditor.saveTo(customValues)
            Binding(BindingMode.CUSTOM, custom = customValues.copy())
        }
        notManagedRadio.isSelected -> Binding(BindingMode.NOT_MANAGED)
        else -> null
    }

    /** Rebuilds the template list after templates were added, removed or renamed. */
    fun refreshTemplates(selectedId: String? = (templateCombo.selectedItem as? Template)?.id) {
        val list = templates()
        templateCombo.model = CollectionComboBoxModel(list, list.firstOrNull { it.id == selectedId })
    }

    /** One line per entry, so long paths never widen the whole settings page. */
    fun setEffective(lines: List<String>) {
        effectiveLabel.text = lines.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
    }

    private fun updateEnabledState() {
        templateCombo.isEnabled = templateRadio.isSelected
        customEditor.setEnabled(customRadio.isSelected)
        customRow.visible(customRadio.isSelected)
    }
}
```

- [ ] **Step 5: 替换配置页为最终结构**

`MavenSettingsTemplatesConfigurable.kt` 整个文件替换为：

```kotlin
package io.github.shizzhang0.mavensettingstemplates.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.SettingsResolver
import io.github.shizzhang0.mavensettingstemplates.core.TemplatesConfig
import io.github.shizzhang0.mavensettingstemplates.settings.ProjectRecords
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings
import javax.swing.JComponent

/** Build Tools > Maven > Settings Templates (design §8). A project-level page editing application-level data. */
class MavenSettingsTemplatesConfigurable(private val project: Project) : Configurable {

    private var templatesPanel: TemplatesPanel? = null
    private var rulesPanel: RulesPanel? = null
    private var recordsPanel: ProjectRecordsPanel? = null
    private var currentPanel: CurrentProjectPanel? = null

    /** Base path of the current project; null for the default project ("Settings for New Projects"). */
    private val projectPath: String? = project.basePath?.takeUnless { project.isDefault }

    override fun getDisplayName(): String = MstBundle.message("configurable.displayName")

    override fun createComponent(): JComponent {
        val templates = TemplatesPanel(project, ::countReferences, ::onTemplatesChanged)
        val rules = RulesPanel(project) { templates.currentTemplates() }
        val records = ProjectRecordsPanel { templates.currentTemplates() }
        val current = projectPath?.let { CurrentProjectPanel(project, { templates.currentTemplates() }, ::refreshEffective) }
        templatesPanel = templates
        rulesPanel = rules
        recordsPanel = records
        currentPanel = current
        return panel {
            if (current != null) {
                group(MstBundle.message("section.current")) {
                    row { cell(current.component).align(AlignX.FILL) }
                }
            }
            group(MstBundle.message("section.templates")) {
                row { cell(templates.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
            group(MstBundle.message("section.rules")) {
                row { cell(rules.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
            group(MstBundle.message("section.records")) {
                row { cell(records.component).align(Align.FILL) }.resizableRow()
            }.resizableRow()
        }
    }

    override fun isModified(): Boolean {
        val templates = templatesPanel ?: return false
        if (pendingConfig(templates) != TemplatesSettings.getInstance().snapshot()) return true
        if (recordsPanel?.removedKeys().orEmpty().isNotEmpty()) return true
        val current = currentPanel ?: return false
        return current.currentBinding() != persistedBinding()
    }

    override fun apply() {
        val templates = templatesPanel ?: return
        TemplatesSettings.getInstance().replace(pendingConfig(templates))
        val records = ProjectRecords.getInstance()
        records.remove(recordsPanel?.removedKeys().orEmpty())
        val current = currentPanel
        val path = projectPath
        if (current != null && path != null) {
            val binding = current.currentBinding()
            if (binding != persistedBinding()) {
                records.update(ProjectRecords.keyOf(path), path) { it.binding = binding }
            }
        }
        reset()
    }

    override fun reset() {
        val config = TemplatesSettings.getInstance().snapshot()
        templatesPanel?.reset(config.templates, config.defaultTemplateId)
        rulesPanel?.reset(config.rules)
        recordsPanel?.reset(ProjectRecords.getInstance().all())
        currentPanel?.reset(persistedBinding())
        refreshEffective()
    }

    override fun disposeUIResources() {
        templatesPanel = null
        rulesPanel = null
        recordsPanel = null
        currentPanel = null
    }

    private fun pendingConfig(templates: TemplatesPanel): TemplatesConfig = TemplatesConfig(
        templates.currentTemplates().toMutableList(),
        templates.defaultTemplateId,
        rulesPanel?.currentRules().orEmpty().toMutableList(),
    )

    private fun persistedBinding(): Binding? =
        projectPath?.let { ProjectRecords.getInstance().get(ProjectRecords.keyOf(it))?.binding }

    private fun countReferences(templateId: String): Pair<Int, Int> {
        val rules = rulesPanel?.countReferences(templateId) ?: 0
        val projects = ProjectRecords.getInstance().all().count { (_, record) ->
            record.binding?.mode == BindingMode.TEMPLATE && record.binding?.templateId == templateId
        }
        return rules to projects
    }

    private fun onTemplatesChanged() {
        rulesPanel?.refresh()
        recordsPanel?.refresh()
        currentPanel?.refreshTemplates()
        refreshEffective()
    }

    private fun refreshEffective() {
        val current = currentPanel ?: return
        val templates = templatesPanel ?: return
        val path = projectPath ?: return
        val resolved = SettingsResolver(pendingConfig(templates)).resolve(path, current.currentBinding())
        current.setEffective(
            if (resolved == null) {
                listOf(MstBundle.message("current.effective.none"))
            } else {
                listOf(MstBundle.message("current.effective", Presentation.describe(resolved.source))) +
                    Presentation.summaryLines(resolved.values)
            },
        )
    }
}
```

- [ ] **Step 6: 构建**

Run: `./gradlew buildPlugin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 在 runIde 中手动验证**

Run: `./gradlew runIde`，打开任意项目，进入设置页：

1. 页面从上到下依次是四个区块：Current Project、Templates、Folder Rules、Project Records。
2. 在 Folder Rules 点 `+`，弹出文件夹选择器，选一个目录后新增一行，模板默认是第一个模板。再选同一个目录，会弹 `Duplicate Folder` 错误框。
3. 规则行按路径排序。改变模板下拉的选择、取消勾选 Enabled，Apply 后重新打开，修改都保留。
4. 把某条规则引用的模板删掉：会弹确认框，显示 `used by 1 folder rule(s)`；确认后，那一行的模板列变成红色的 `Missing template`，其他行不受影响。
5. Current Project 区块：切换四个单选项时，Effective 那一行会立刻更新；选 Custom values 时，只有下面的编辑器可以编辑；选 Not managed 时显示 `Effective: not managed...`。
6. 选 Use template 后点 Apply，Project Records 里出现当前项目，Binding 列显示 `Template "..."`。
7. 打开 `File > New Projects Setup > Settings for New Projects...`，进入同一页面：没有 Current Project 区块。
8. 在 Project Records 里选中一行点 `-`，Apply 后该记录消失。

- [ ] **Step 8: 检查点**

向用户汇报验证结果。然后提交（提交约定见 Global Constraints）。

---

### Task 6: 应用流程：启动应用、预写入、通知

**Files:**
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/notify/MstNotifications.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/apply/ProjectEvaluator.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/apply/DefaultProjectSeeder.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/apply/StartupActivity.kt`
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/apply/SettingsAppliedHandler.kt`
- Modify: `MavenSettingsTemplatesConfigurable.kt`（`apply()` 末尾加一行）、`plugin.xml`、`MavenSettingsTemplatesBundle.properties`

**Interfaces:**
- Consumes: Task 3 的 `decide`、`Decision`、`Trigger`、`SettingsResolver`；Task 4 的 `MavenSettingsAccess`、`TemplatesSettings`、`ProjectRecords`、`Presentation`。
- Produces:
  - `class ProjectEvaluator(project) { fun isSelfWriting(): Boolean; fun evaluate(trigger: Trigger); companion fun getInstance(project) }`
  - `object DefaultProjectSeeder { fun seed(); fun baseline(): MavenValues }`
  - `object SettingsAppliedHandler { fun onApplied() }`
  - `class MavenSettingsTemplatesStartupActivity : ProjectActivity`

- [ ] **Step 1: 追加消息**

```properties
notification.group.drift=Maven settings drift
notification.group.info=Maven settings applied
notification.title=Maven Settings Templates
notification.applied=Applied {0} to this project.
notification.drift.title=Maven settings differ from {0}
notification.error=Could not apply Maven settings: {0}
notification.action.undo=Undo
notification.action.restore=Restore template values
notification.action.saveCustom=Save as project custom
notification.action.ignore=Ignore
```

- [ ] **Step 2: 实现 `MstNotifications.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.notify

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.shizzhang0.mavensettingstemplates.MstBundle
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.core.PathNormalizer

/** Notification groups are registered in plugin.xml with these ids. */
object MstNotifications {
    private const val DRIFT_GROUP = "MavenSettingsTemplates.Drift"
    private const val INFO_GROUP = "MavenSettingsTemplates.Info"

    /** First apply (design §5.2 case 1): a balloon that fades, with Undo. */
    fun applied(project: Project, source: String, onUndo: () -> Unit) {
        group(INFO_GROUP)
            .createNotification(
                MstBundle.message("notification.title"),
                MstBundle.message("notification.applied", escape(source)),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.undo")) { onUndo() })
            .notify(project)
    }

    /** Drift (design §5.2 case 3): a sticky balloon listing only the fields that differ. */
    fun drift(
        project: Project,
        source: String,
        target: MavenValues,
        current: MavenValues,
        canSaveAsCustom: Boolean,
        onRestore: () -> Unit,
        onSaveAsCustom: () -> Unit,
        onIgnore: () -> Unit,
    ): Notification {
        val notification = group(DRIFT_GROUP).createNotification(
            escape(MstBundle.message("notification.drift.title", source)),
            diffHtml(target, current),
            NotificationType.WARNING,
        )
        notification.addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.restore")) { onRestore() })
        if (canSaveAsCustom) {
            notification.addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.saveCustom")) { onSaveAsCustom() })
        }
        notification.addAction(NotificationAction.createSimpleExpiring(MstBundle.message("notification.action.ignore")) { onIgnore() })
        notification.notify(project)
        return notification
    }

    fun error(project: Project, message: String) {
        group(INFO_GROUP)
            .createNotification(MstBundle.message("notification.title"), MstBundle.message("notification.error", escape(message)), NotificationType.ERROR)
            .notify(project)
    }

    private fun group(id: String): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(id)

    /** One line per differing field: "label: template value -> current value". */
    private fun diffHtml(target: MavenValues, current: MavenValues): String {
        val rows = buildList {
            val homeTarget = target.copy(userSettingsFile = "", localRepository = "")
            val homeCurrent = current.copy(userSettingsFile = "", localRepository = "")
            if (!homeTarget.sameAs(homeCurrent)) {
                add(row("field.mavenHome", Presentation.home(target), Presentation.home(current)))
            }
            if (!PathNormalizer.samePath(target.userSettingsFile, current.userSettingsFile)) {
                add(row("field.userSettings", Presentation.path(target.userSettingsFile), Presentation.path(current.userSettingsFile)))
            }
            if (!PathNormalizer.samePath(target.localRepository, current.localRepository)) {
                add(row("field.localRepository", Presentation.path(target.localRepository), Presentation.path(current.localRepository)))
            }
        }
        return rows.joinToString("<br>")
    }

    private fun row(labelKey: String, target: String, current: String): String =
        "<b>${escape(MstBundle.message(labelKey).removeSuffix(":"))}</b>: ${escape(target)} → ${escape(current)}"

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
```

- [ ] **Step 3: 实现 `DefaultProjectSeeder.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess
import io.github.shizzhang0.mavensettingstemplates.settings.ProjectRecords
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings

/** Design §7.1: seed the default project with the default template so new projects inherit it. */
object DefaultProjectSeeder {
    private val LOG = logger<DefaultProjectSeeder>()

    @Volatile
    private var cachedBaseline: MavenValues? = null

    fun seed() {
        try {
            val defaultProject = ProjectManager.getInstance().defaultProject
            val config = TemplatesSettings.getInstance().snapshot()
            val values = config.template(config.defaultTemplateId)?.toValues()
            if (values != null && !MavenSettingsAccess.read(defaultProject).sameAs(values)) {
                MavenSettingsAccess.write(defaultProject, values)
            }
            cachedBaseline = MavenSettingsAccess.read(defaultProject)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Could not seed the default project; relying on the startup activity only (design §11)", e)
            cachedBaseline = null
        }
    }

    /** What a brand-new project starts with (design §5.2 baseline); IDE defaults when unknown. O(1). */
    fun baseline(): MavenValues = cachedBaseline ?: MavenValues()
}

class SeedDefaultProjectListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        // Load both stores now so the OPEN path does no disk I/O before its write (design §7.2).
        ProjectRecords.getInstance()
        DefaultProjectSeeder.seed()
    }
}
```

- [ ] **Step 4: 实现 `ProjectEvaluator.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.notification.Notification
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.shizzhang0.mavensettingstemplates.Presentation
import io.github.shizzhang0.mavensettingstemplates.core.Binding
import io.github.shizzhang0.mavensettingstemplates.core.BindingMode
import io.github.shizzhang0.mavensettingstemplates.core.Decision
import io.github.shizzhang0.mavensettingstemplates.core.MavenHomeKind
import io.github.shizzhang0.mavensettingstemplates.core.MavenValues
import io.github.shizzhang0.mavensettingstemplates.core.SettingsResolver
import io.github.shizzhang0.mavensettingstemplates.core.Template
import io.github.shizzhang0.mavensettingstemplates.core.Trigger
import io.github.shizzhang0.mavensettingstemplates.core.decide
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess
import io.github.shizzhang0.mavensettingstemplates.notify.MstNotifications
import io.github.shizzhang0.mavensettingstemplates.settings.ProjectRecords
import io.github.shizzhang0.mavensettingstemplates.settings.TemplatesSettings
import java.util.concurrent.atomic.AtomicInteger

/** Design §5.2-§5.4 for one project. Every entry point is serialized on this instance. */
@Service(Service.Level.PROJECT)
class ProjectEvaluator(private val project: Project) {

    private val selfWriteDepth = AtomicInteger()
    private var ignored: MavenValues? = null // session only (design D8)
    private var pendingDrift: Notification? = null

    @Volatile
    private var errorReported = false

    /** True while this plugin writes, so DriftWatcher can skip its own events (design §5.4). */
    fun isSelfWriting(): Boolean = selfWriteDepth.get() > 0

    /**
     * On [Trigger.OPEN] this runs on the startup activity's thread and must reach [write] without suspending,
     * switching threads or doing I/O: it races Maven's own startup activity (design §7.2).
     */
    @Synchronized
    fun evaluate(trigger: Trigger) = guarded {
        val path = project.basePath ?: return@guarded
        val key = ProjectRecords.keyOf(path)
        val records = ProjectRecords.getInstance()
        val record = records.get(key)
        val resolved = SettingsResolver(TemplatesSettings.getInstance().snapshot()).resolve(path, record?.binding)
        if (resolved == null) {
            expirePendingDrift()
            ignored = null
            return@guarded
        }
        val target = resolved.values
        val current = MavenSettingsAccess.read(project)
        val decision = decide(target, current, record?.lastApplied, DefaultProjectSeeder.baseline(), ignored, trigger)
        when (decision) {
            Decision.NONE -> expirePendingDrift()
            Decision.RECORD -> {
                expirePendingDrift()
                records.update(key, path) { it.lastApplied = target }
            }
            Decision.FIRST_APPLY -> {
                val before = MavenSettingsAccess.snapshot(project)
                write(target)
                records.update(key, path) { it.lastApplied = target }
                requestSync()
                MstNotifications.applied(project, Presentation.describe(resolved.source)) { undo(key, path, before) }
            }
            Decision.FOLLOW -> {
                write(target)
                expirePendingDrift()
                records.update(key, path) { it.lastApplied = target }
                requestSync()
            }
            Decision.NOTIFY_DRIFT -> {
                expirePendingDrift()
                pendingDrift = MstNotifications.drift(
                    project = project,
                    source = Presentation.describe(resolved.source),
                    target = target,
                    current = current,
                    canSaveAsCustom = current.homeKind != MavenHomeKind.OTHER,
                    onRestore = ::restoreTemplateValues,
                    onSaveAsCustom = { saveAsCustom(key, path) },
                    onIgnore = ::ignoreCurrentValues,
                )
            }
        }
        // Logged after the write on purpose; used to measure the startup race (design §12 #2).
        LOG.info("$trigger ${project.name}: $decision via ${resolved.source}")
    }

    /** Undo of a first apply: restore the old values and stop managing this project (design §5.3). */
    @Synchronized
    private fun undo(key: String, path: String, before: MavenSettingsAccess.RawSnapshot) = guarded {
        if (project.isDisposed) return@guarded
        ProjectRecords.getInstance().update(key, path) {
            it.binding = Binding(BindingMode.NOT_MANAGED)
            it.lastApplied = null
        }
        selfWrite { MavenSettingsAccess.restore(project, before) }
        requestSync()
    }

    @Synchronized
    private fun restoreTemplateValues() = guarded {
        if (project.isDisposed) return@guarded
        val path = project.basePath ?: return@guarded
        val key = ProjectRecords.keyOf(path)
        val records = ProjectRecords.getInstance()
        val resolved = SettingsResolver(TemplatesSettings.getInstance().snapshot())
            .resolve(path, records.get(key)?.binding) ?: return@guarded
        write(resolved.values)
        records.update(key, path) { it.lastApplied = resolved.values }
        ignored = null
        requestSync()
    }

    @Synchronized
    private fun saveAsCustom(key: String, path: String) = guarded {
        if (project.isDisposed) return@guarded
        val current = MavenSettingsAccess.read(project)
        if (current.homeKind == MavenHomeKind.OTHER) return@guarded
        ProjectRecords.getInstance().update(key, path) {
            it.binding = Binding(BindingMode.CUSTOM, custom = Template.fromValues(current))
            it.lastApplied = current
        }
        ignored = null
    }

    @Synchronized
    private fun ignoreCurrentValues() = guarded {
        if (!project.isDisposed) ignored = MavenSettingsAccess.read(project)
    }

    private fun write(values: MavenValues) = selfWrite { MavenSettingsAccess.write(project, values) }

    private inline fun selfWrite(block: () -> Unit) {
        selfWriteDepth.incrementAndGet()
        try {
            block()
        } finally {
            selfWriteDepth.decrementAndGet()
        }
    }

    /** `MavenSettingsCache.reload()` blocks, so the sync always runs on a pooled thread. */
    private fun requestSync() {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            if (!project.isDisposed) guarded { MavenSettingsAccess.sync(project) }
        })
    }

    private fun expirePendingDrift() {
        pendingDrift?.expire()
        pendingDrift = null
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Maven Settings Templates failed for ${project.name}", e)
            if (!errorReported) {
                errorReported = true
                MstNotifications.error(project, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private val LOG = logger<ProjectEvaluator>()

        fun getInstance(project: Project): ProjectEvaluator = project.service()
    }
}
```

- [ ] **Step 5: 实现 `StartupActivity.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.shizzhang0.mavensettingstemplates.core.Trigger

/**
 * Registered with order="first". The platform launches post-startup activities concurrently, so this only
 * starts first; evaluate() therefore writes before any suspension to win the race with Maven (design §7.2).
 */
class MavenSettingsTemplatesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ProjectEvaluator.getInstance(project).evaluate(Trigger.OPEN)
    }
}
```

- [ ] **Step 6: 实现 `SettingsAppliedHandler.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import io.github.shizzhang0.mavensettingstemplates.core.Trigger

/** Design §7.3: after Apply/OK, reseed the default project and re-evaluate every open project. */
object SettingsAppliedHandler {
    fun onApplied() {
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            DefaultProjectSeeder.seed()
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                ProjectEvaluator.getInstance(project).evaluate(Trigger.SETTINGS_APPLIED)
            }
        })
    }
}
```

- [ ] **Step 7: 配置页 Apply 后触发重评估**

在 `MavenSettingsTemplatesConfigurable.kt` 中，增加 import：

```kotlin
import io.github.shizzhang0.mavensettingstemplates.apply.SettingsAppliedHandler
```

并把 `apply()` 最后的：

```kotlin
        reset()
    }
```

改为：

```kotlin
        reset()
        SettingsAppliedHandler.onApplied()
    }
```

- [ ] **Step 8: 注册扩展**

`plugin.xml` 从 `<extensions` 开始到文件末尾，替换为：

```xml
    <extensions defaultExtensionNs="com.intellij">
        <projectConfigurable parentId="MavenSettings"
                             id="io.github.shizzhang0.mavensettingstemplates"
                             instance="io.github.shizzhang0.mavensettingstemplates.ui.MavenSettingsTemplatesConfigurable"
                             key="configurable.displayName"/>
        <postStartupActivity implementation="io.github.shizzhang0.mavensettingstemplates.apply.MavenSettingsTemplatesStartupActivity"
                             order="first"/>
        <notificationGroup id="MavenSettingsTemplates.Drift" displayType="STICKY_BALLOON" key="notification.group.drift"/>
        <notificationGroup id="MavenSettingsTemplates.Info" displayType="BALLOON" key="notification.group.info"/>
    </extensions>

    <applicationListeners>
        <listener class="io.github.shizzhang0.mavensettingstemplates.apply.SeedDefaultProjectListener"
                  topic="com.intellij.ide.AppLifecycleListener"/>
    </applicationListeners>
</idea-plugin>
```

如果 Task 4 验证时把 `parentId` 改成了 `tools`，这里保持同样的值。

- [ ] **Step 9: 构建**

Run: `./gradlew buildPlugin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 10: 准备测试目录**

```bash
mkdir -p /c/tmp/mst/CompanyA/demo /c/tmp/mst/CompanyA/legacy/old-app /c/tmp/mst/Personal/hobby
for d in /c/tmp/mst/CompanyA/demo /c/tmp/mst/CompanyA/legacy/old-app /c/tmp/mst/Personal/hobby; do
cat > "$d/pom.xml" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>demo</groupId>
  <artifactId>demo</artifactId>
  <version>1.0</version>
</project>
EOF
done
printf '<settings/>\n' > /c/tmp/mst/settings-a.xml
```

在沙盒 IDE 的设置页里配置：
- 模板 `Personal`（★ 默认）：Local repository = `${user.home}\.m2\repo-personal`
- 模板 `CompanyA`：User settings file = `C:\tmp\mst\settings-a.xml`，Local repository = `${user.home}\.m2\repo-a`
- 模板 `Legacy`：Maven home = `Use Maven wrapper`
- 规则：`C:\tmp\mst\CompanyA` → CompanyA；`C:\tmp\mst\CompanyA\legacy` → Legacy

- [ ] **Step 11: 在 runIde 中手动验证**

Run: `./gradlew runIde`。每个场景结束后，在沙盒 IDE 的 idea.log（`find .intellijPlatform/sandbox -name idea.log`）里搜索 `via`，核对我们记录的决策。

1. **§12 #3 default project 预写入**：配好模板后重启沙盒 IDE。打开 `File > New Projects Setup > Settings for New Projects > Build Tools > Maven`，Local repository 显示 `C:\Users\...\.m2\repo-personal`。如果不是，查看 idea.log 里有没有 `Could not seed the default project`，把结果写进检查点。
2. **§12 #1 首次应用**：打开 `C:\tmp\mst\CompanyA\demo`。右下角弹出 `Applied template "CompanyA" (folder rule ...)`；Maven 设置页里 User settings file 和 Local repository 是展开后的绝对路径（**§12 #12**）；日志里有 `OPEN demo: FIRST_APPLY`。
3. **§12 #13 规则优先级**：打开 `C:\tmp\mst\CompanyA\legacy\old-app`，Maven home 是 `Use Maven wrapper`。
4. **§12 #9 Undo**：在一个新打开的项目（例如 `Personal\hobby`）的提示上点 Undo。Maven 设置恢复成原来的值；设置页的 Project Records 里该项目显示 `Not managed`；关闭再重新打开，不再弹出提示，日志里不再有该项目的 `FIRST_APPLY`。
5. **§12 #5 删除 `.idea`**：关闭 `CompanyA\demo`，删除它的 `.idea` 目录，再打开。不弹任何提示，日志里是 `OPEN demo: FOLLOW` 或 `RECORD`，Maven 设置恢复为 CompanyA 的值。
6. **§12 #8（前半）模板变更**：`CompanyA\demo` 保持打开，修改 CompanyA 模板的 Local repository 后点 OK。该项目的 Maven 设置静默跟随，日志里是 `SETTINGS_APPLIED demo: FOLLOW`。
7. **§12 #10 不接管**：把 `CompanyA\demo` 设为 Not managed 后点 OK，再手动修改它的 Maven 设置：不弹任何提示。
8. **§12 #2 竞态实测**：把 `Personal\hobby` 的记录从 Project Records 删掉，然后关闭项目，删除 `.idea`，再重新打开，重复 3 次。每次对照 idea.log 里 `FIRST_APPLY` 那行的时间戳，和 Maven 相关日志（搜索 `MavenProjectsManager` 或 `sync`），记录 Maven 读取设置是在我们写入之前还是之后。无论先后，最终 Maven 设置都必须是 Personal 的值。把统计结果写进检查点。

- [ ] **Step 12: 检查点**

向用户汇报每个场景的结果，尤其是：default project 预写入是否生效（§7.1 需实测）、竞态统计（§7.2 需实测）、Maven 自身是否也触发了 sync（§7.4 需实测，看同步日志里是否连续出现两次 sync）。然后提交（提交约定见 Global Constraints）。

---

### Task 7: 漂移监控

**Files:**
- Create: `src/main/kotlin/io/github/shizzhang0/mavensettingstemplates/apply/DriftWatcher.kt`
- Modify: `apply/StartupActivity.kt`、`apply/SettingsAppliedHandler.kt`、`plugin.xml`

**Interfaces:**
- Consumes: Task 4 的 `MavenSettingsAccess.settingsIdentity` / `listen`；Task 6 的 `ProjectEvaluator`。
- Produces: `class DriftWatcher(project) : Disposable { fun attach(); fun reattachIfReplaced(); companion fun getInstance(project) }`；`class ReattachOnActivationListener : ApplicationActivationListener`。

- [ ] **Step 1: 实现 `DriftWatcher.kt`**

```kotlin
package io.github.shizzhang0.mavensettingstemplates.apply

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.Alarm
import io.github.shizzhang0.mavensettingstemplates.core.Trigger
import io.github.shizzhang0.mavensettingstemplates.maven.MavenSettingsAccess

/** Design §7.4: debounced drift detection that survives the settings object being replaced. */
@Service(Service.Level.PROJECT)
class DriftWatcher(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var attachedTo: Any? = null
    private var listenerScope: Disposable? = null

    @Synchronized
    fun attach() {
        if (project.isDisposed || MavenSettingsAccess.settingsIdentity(project) === attachedTo) return
        listenerScope?.let { Disposer.dispose(it) }
        val scope = Disposer.newDisposable(this, "MavenSettingsTemplates drift listener")
        attachedTo = MavenSettingsAccess.listen(project, scope, ::onSettingsChanged)
        listenerScope = scope
    }

    /** workspace.xml reloads replace the settings object and silently drop our listener (design §3.5). */
    fun reattachIfReplaced() {
        val replaced = synchronized(this) {
            !project.isDisposed && attachedTo != null && MavenSettingsAccess.settingsIdentity(project) !== attachedTo
        }
        if (replaced) {
            attach()
            scheduleEvaluation()
        }
    }

    private fun onSettingsChanged() {
        if (ProjectEvaluator.getInstance(project).isSelfWriting()) return
        scheduleEvaluation()
    }

    private fun scheduleEvaluation() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed) ProjectEvaluator.getInstance(project).evaluate(Trigger.CHANGED_AT_RUNTIME)
        }, DEBOUNCE_MS)
    }

    override fun dispose() = Unit

    companion object {
        private const val DEBOUNCE_MS = 500

        fun getInstance(project: Project): DriftWatcher = project.service()
    }
}

/** External `.idea` edits usually land while the IDE is in the background; check when it regains focus. */
class ReattachOnActivationListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        for (project in ProjectManager.getInstance().openProjects) {
            project.serviceIfCreated<DriftWatcher>()?.reattachIfReplaced()
        }
    }
}
```

- [ ] **Step 2: 启动时挂载监听**

`StartupActivity.kt` 的 `execute` 改为：

```kotlin
    override suspend fun execute(project: Project) {
        ProjectEvaluator.getInstance(project).evaluate(Trigger.OPEN)
        DriftWatcher.getInstance(project).attach()
    }
```

- [ ] **Step 3: 设置变更后也确保已挂载**

动态安装插件时，已打开的项目不会跑启动 activity，所以这里也要挂载。`SettingsAppliedHandler.kt` 循环体改为：

```kotlin
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                ProjectEvaluator.getInstance(project).evaluate(Trigger.SETTINGS_APPLIED)
                DriftWatcher.getInstance(project).attach()
            }
```

- [ ] **Step 4: 注册窗口激活监听**

在 `plugin.xml` 的 `<applicationListeners>` 里追加：

```xml
        <listener class="io.github.shizzhang0.mavensettingstemplates.apply.ReattachOnActivationListener"
                  topic="com.intellij.openapi.application.ApplicationActivationListener"/>
```

- [ ] **Step 5: 构建**

Run: `./gradlew buildPlugin`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 在 runIde 中手动验证**

沿用 Task 6 的测试目录和模板。

1. **§12 #6 手动修改**：打开 `CompanyA\demo`，在 Maven 设置页把 Local repository 改成别的路径后点 OK。右下角出现常驻提醒 `Maven settings differ from template "CompanyA" ...`，内容只有 `Local repository: <模板值> → <当前值>` 一行。然后分别验证三个按钮：
   - **Restore template values**：值恢复成模板值，提醒消失。
   - 再改一次，点 **Save as project custom**：Project Records 里该项目变成 `Custom values`；关闭再打开，不弹提醒。然后把绑定改回 Follow rules 并点 OK，值静默恢复成 CompanyA 模板。
   - 再改一次，点 **Ignore**：本次会话不再提醒；再改成另一个值，又会提醒；重启沙盒 IDE 后打开项目，也会提醒（Ignore 只管本次会话）。
2. **§12 #7 连续修改**：连续三次修改 Local repository 并点 Apply，右下角最终只剩一条提醒（旧提醒都已失效）。
3. **§12 #8（后半）**：先让 `CompanyA\demo` 处于被手动修改过的状态，再修改 CompanyA 模板并点 OK，弹出提醒，而不是静默覆盖。
4. **§12 #11 外部修改 workspace.xml**：切到外部编辑器，打开 `C:\tmp\mst\CompanyA\demo\.idea\workspace.xml`，找到 Maven 的 `localRepository` 值，改掉后保存，再切回沙盒 IDE。IDE 重新加载后出现漂移提醒，日志里有 `CHANGED_AT_RUNTIME demo: NOTIFY_DRIFT`。
5. **自写不误报**：点 Restore 之后，日志里没有紧跟着出现一条 `CHANGED_AT_RUNTIME ... NOTIFY_DRIFT`。

- [ ] **Step 7: 检查点**

向用户汇报每个场景的结果。然后提交（提交约定见 Global Constraints）。

---

### Task 8: 收尾：插件校验、文档、全量回归

**Files:**
- Modify: `build.gradle.kts`（`pluginVerification`）
- Modify: `CHANGELOG.md`

- [ ] **Step 1: 核实 `pluginVerification` 的 DSL**

插件 jar 在 Task 1 构建时已经进入 Gradle 缓存：

```bash
JAR=$(find ~/.gradle/caches -name "intellij-platform-gradle-plugin-2.19.0.jar" | head -1)
unzip -Z1 "$JAR" | grep -iE "PluginVerification.*Ides" | head
```

对找到的 ides 扩展类执行 `javap -cp "$JAR" '<类名>' | grep -i recommended`，确认存在 `recommended()`。如果不存在，按 javap 的实际输出选择一个等价方法，把最终写法告诉用户。不要猜。

- [ ] **Step 2: 配置 `pluginVerification`**

在 `build.gradle.kts` 的 `intellijPlatform { ... }` 块里、`pluginConfiguration { ... }` 之后加入：

```kotlin
    pluginVerification {
        ides {
            recommended()
        }
    }
```

- [ ] **Step 3: 运行插件校验**

Run: `./gradlew verifyPlugin`
Expected: `BUILD SUCCESSFUL`。报告里没有 `Compatibility problems`，也没有 `Internal API usages`。

`Deprecated API usages` 如果出现，逐条列给用户，不要擅自改动。

- [ ] **Step 4: 更新 `CHANGELOG.md`**

把 `## [Unreleased]` 一节替换为：

```markdown
## [Unreleased]

### Added

- Templates for Maven home, user settings file and local repository, with `${user.home}` support.
- Folder rules (the deepest matching folder wins), a default template, and per-project overrides.
- Automatic application when a project opens, with an Undo notification the first time a project is managed.
- Drift notifications with Restore template values, Save as project custom, and Ignore.
```

- [ ] **Step 5: 确认没有遗留测试**

Run: `find src/test -name "*.kt" 2>/dev/null; grep -n opentest4j build.gradle.kts`
Expected: 两条命令都没有输出。

- [ ] **Step 6: 全量回归**

Run: `./gradlew buildPlugin` 后执行 `./gradlew runIde`，按设计文档 §12 的 13 个场景完整走一遍。

场景 #4（新 worktree）需要在一个临时仓库里执行 `git init`、`git commit`、`git worktree add`。用户已批准执行 `git commit`，这个临时仓库也包括在内：

```bash
cd /c/tmp/mst/CompanyA/demo && git init -q && git add pom.xml && git commit -qm init && git worktree add ../demo-wt
```

然后打开 `C:\tmp\mst\CompanyA\demo-wt`：自动应用 CompanyA，并弹出首次接管提示。

- [ ] **Step 7: 清理测试目录**

征得用户同意后执行：

```bash
rm -rf /c/tmp/mst
```

- [ ] **Step 8: 检查点**

向用户汇报：`verifyPlugin` 的结果、13 个场景的逐条结果、设计文档里 4 个"需实测"项的结论（§7.1 预写入、§7.2 竞态、§8 设置页挂载位置、§7.4 是否重复 sync）。如果实测结论和设计文档不一致，建议相应更新设计文档。然后提交。是否 push 分支、是否合并到 `main`，由用户决定。

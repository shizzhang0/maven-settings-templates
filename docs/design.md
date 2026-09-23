# Maven Settings Templates — 设计文档

- 日期：2026-09-23
- 状态：已确认
- 目标 IDE：IntelliJ IDEA 2026.2（since-build `262`）

> 语言约定：本文档用中文；项目内的代码、注释、界面文字、plugin.xml、提交信息一律用英文。

---

## 1. 目标

一个 IntelliJ IDEA 插件，用**模板**集中管理 Maven 的三项设置，并按项目自动应用：

- Maven home path
- User settings file
- Local repository

核心诉求：

1. 同一台机器上，不同来源的项目（个人 / 公司A / 公司B）自动使用各自正确的 Maven 配置，不需要逐个项目手动设置。
2. **首次 Maven 导入就用对配置**：新 clone、新 git worktree、删除 `.idea` 之后重新打开时都一样，尽量不多跑 sync。
3. 配置不丢失：所有配置都存在应用级，不依赖 `.idea`。
4. 用户手动改了 Maven 设置时，插件不静默覆盖，而是提醒用户，让用户决定怎么处理。

## 2. 已确认的决策

| # | 决策 | 结论 |
|---|---|---|
| D1 | 支持的 IDE 版本 | 仅 2026.2+（since-build `262`，不设 until-build），不做兼容层 |
| D2 | Maven home 字段形式 | 下拉框（Bundled (Maven 3) / Use Maven wrapper）+ 自定义路径，与 IDE 原生页面一致。**没有 Maven 4**：`getAllKnownHomes()` 只返回 `[BundledMaven3, MavenWrapper]` |
| D3 | 字段留空的含义 | 写入空串，即 **IDE 默认值**（`~/.m2/settings.xml`、`~/.m2/repository`）。Maven home 下拉框默认选中 Bundled (Maven 3)。没有"字段级不接管" |
| D4 | 路径规则形式 | **文件夹 → 模板**。用文件夹选择器选取，匹配该文件夹及其所有子目录。不支持通配符 |
| D5 | 多条规则同时命中 | **嵌套最深（最具体）的文件夹优先**。不需要手动排序；同一文件夹不允许重复配置 |
| D6 | 强制模式 | **取消强制模式开关**，改为基于 lastApplied 的统一模型（见 §5） |
| D7 | 首次接管的提示 | **常驻提示**（sticky balloon），带 [Undo] 按钮。原定为可自动消失，实测时很容易错过，改为常驻 |
| D8 | "Ignore" 的作用范围 | 仅本次 IDE 会话 |
| D9 | `${user.home}` | 支持，写入 IDE 前展开。**不做**"选择路径后自动替换成 `${user.home}`" |
| D10 | 设置页 | 只有一个项目级页面，挂在 Maven 设置页下面 |
| D11 | 命名空间 | `io.github.shizzhang0`（与 GitHub 账户 `shizzhang0` 对应，GitHub 上的 `timothy` 是另一个人）。用户可见的作者名用 `Timothy` |
| D12 | baseline 规则 | 接受 §5.2 描述的取舍 |

## 3. 已核实的 Maven API（2026.2，IU-262.10968.63）

以下结论全部来自对本机 `plugins/maven-plugin/lib/intellij.maven.jar` 的 `javap` 反编译，不是凭记忆写的。

### 3.1 读写设置

```text
MavenWorkspaceSettingsComponent.getInstance(project).getSettings().getGeneralSettings()
  → MavenGeneralSettings
```

`MavenProjectsManager.getInstance(project).getGeneralSettings()` 拿到的是同一份对象。

| 用途 | 方法 |
|---|---|
| Maven home | `getMavenHomeType(): MavenHomeType` / `setMavenHomeType(MavenHomeType)` |
| User settings file | `getUserSettingsFile(): String` / `setUserSettingsFile(String)` |
| Local repository | `getLocalRepository(): String` / `setLocalRepository(String)` |
| 批量修改 | `beginUpdate()` / `endUpdate()`：中间的多次修改只触发一次 `changed()` |
| 监听 | `addListener(MavenGeneralSettings.Listener, Disposable)`。`Listener` 只有一个无参的 `changed()`，**拿不到新旧值**，需要自己读当前值来比对 |

### 3.2 MavenHomeType

- `BundledMaven3.INSTANCE`、`MavenWrapper.INSTANCE`：Kotlin object
- `MavenInSpecificPath(String)`：data class，`equals` 只做字符串比较（因此比较前必须先规范化路径，见 §6）
- `BundledMaven4.INSTANCE`：类存在，但 UI 里没有这个选项，本插件不支持
- `MavenHomeKt.resolveMavenHomeType(String)`：空串解析为 `BundledMaven3`，匹配到标题时解析为对应类型，其他情况解析为 `MavenInSpecificPath`

### 3.3 IDE 原生页面的行为

- 输入框旁的 "Override" 复选框只存在于 UI 层：`PathOverrider.getResult()` 在勾选时返回 `textField.getText().trim()`，未勾选时返回空串。
- setter 不做任何路径规范化，存进去的就是输入框里的原文（通常是 `C:\...` 这种反斜杠格式）。

### 3.4 同步与缓存

- 触发同步：`MavenProjectsManager.getInstance(p).scheduleUpdateAllMavenProjects(MavenSyncSpec.full("<reason>"))`
- 刷新有效路径缓存：`MavenSettingsCache.getInstance(p).reload()`
- 判断是否为 Maven 项目：`MavenProjectsManager.isMavenizedProject()`

### 3.5 生命周期

- Maven 通过 `<postStartupActivity implementation="…MavenProjectsManagerProjectActivity"/>` 启动首次导入（`intellij.maven.xml:80`），**没有声明 order**。
- **平台并发执行所有 postStartupActivity**：`StartupManagerImpl.doRunPostStartupActivities` 按 order 排序后遍历，但对每个 activity 调用 `launchActivity` → `BuildersKt.launch`，不等上一个完成。所以 `order="first"` 只保证**先启动**，不保证**先完成**。
- Maven 的 `onProjectStartup` 先检查 `isNormalProject` / `wasMavenized`，**第一件实际工作就是 `MavenSettingsCache.reloadAsync()`（读取 settings.xml 和本地仓库路径）**，然后 `initOnProjectStartup()`：当 `MavenProjectsManagerState.originalFiles` 非空、但项目树根节点为空时，立即 `scheduleUpdateAllMavenProjects(MavenSyncSpec.full("MavenProjectsManager.onProjectStartup"))`。
- 唯一会被平台等待的更早钩子 `InitProjectActivity` 标注了 `@ApiStatus.Internal`，EP 声明旁的注释为 *"only bundled plugin can define initProjectActivity"*，第三方插件不能使用。`projectServiceContainerInitializedListener` 在 2026.2 中已经不存在。
- **`MavenWorkspaceSettingsComponent.loadState()` 会整个替换 `MavenGeneralSettings` 实例**（`getRealSettings()` → 新对象），外部代码不会调用 `copyListeners`。因此 workspace.xml 重新加载后，挂在旧实例上的监听器会静默失效。

### 3.6 标识

- Maven 插件 id：`org.jetbrains.idea.maven`
- Maven 设置页 configurable id：`MavenSettings`（项目级页面，`groupId="build.tools"`）

## 4. 数据模型

所有数据都存在应用级，拆成两个 `PersistentStateComponent`：

| 组件 | 存储文件 | 随 Settings Sync 同步 | 内容 |
|---|---|---|---|
| `TemplatesSettings` | `mavenSettingsTemplates.xml` | 是 | 模板、默认模板、路径规则 |
| `ProjectRecords` | `mavenSettingsTemplates.local.xml`（`RoamingType.DISABLED`） | 否 | 按项目路径记录的单独配置与 lastApplied |

`ProjectRecords` 不同步，因为项目路径和"上次写入的值"只对本机有意义。

```kotlin
// TemplatesSettings state
class Template {
    var id: String            // UUID; rules and bindings reference templates by id, so renaming is safe
    var name: String
    var mavenHomeKind: MavenHomeKind = BUNDLED_3   // BUNDLED_3 | WRAPPER | CUSTOM
    var mavenHomePath: String = ""                 // used only when kind == CUSTOM
    var userSettingsFile: String = ""              // "" = IDE default
    var localRepository: String = ""               // "" = IDE default
}
class FolderRule {
    var folder: String        // chosen via folder chooser; stored as picked
    var templateId: String
    var enabled: Boolean = true
}
var templates: MutableList<Template>
var defaultTemplateId: String?
var rules: MutableList<FolderRule>

// ProjectRecords state: key = normalized project path (see §6)
class ProjectRecord {
    var binding: Binding?     // null = follow rules
    var lastApplied: MavenValues?   // values the plugin last wrote, already expanded
}
class Binding {
    var mode: BindingMode     // TEMPLATE | CUSTOM | NOT_MANAGED
    var templateId: String?   // mode == TEMPLATE
    var custom: Template?     // mode == CUSTOM (name unused)
}
```

- `MavenHomeKind` 存的是枚举，**不存 IDE 显示的标题**，因为标题可能随语言包变化（比如中文语言包）。
- `MavenValues` 是展开后的四元组：`(homeKind, homePath, userSettingsFile, localRepository)`。

### 4.1 引用失效

模板被删除后，引用它的规则和绑定都会失效：

- 删除时弹确认框，提示有 N 条规则、N 个项目引用了这个模板。
- 解析时跳过失效引用，落到下一优先级。
- 设置页里把失效的引用标红。

## 5. 核心行为

### 5.1 解析：用哪套值（纯函数）

```text
resolve(projectPath) → MavenValues? (null = 不动)
  1. 项目单独配置
       NOT_MANAGED → null
       TEMPLATE    → 该模板（模板已删除则继续往下）
       CUSTOM      → 自定义值
  2. 路径规则：在所有启用、且 projectPath 位于其 folder 之内的规则中，取 folder 最长的一条
  3. 默认模板
  4. null
```

"位于其 folder 之内"是指规范化后 `path == folder` 或 `path.startsWith(folder + "/")`，避免 `公司A` 误匹配 `公司AB`。

### 5.2 决策：写入、跟随，还是提醒

`evaluate(project, trigger)`，trigger 取值为 `OPEN | SETTINGS_APPLIED | CHANGED_AT_RUNTIME`：

```text
target  = resolve(path)
if target == null: 清除该项目的会话状态; return        // 不动、不回滚、不监控
current = 读取 IDE 当前值
rec     = records[path]

if current ≈ target:
    rec.lastApplied = target; return                    // 已经一致（也包括用户手动改成了目标值）

if rec.lastApplied == null:                             // ① 首次接管
    before = current
    write(target); rec.lastApplied = target; sync()
    常驻提示 "Applied template 'X'" [Undo]

else if current ≈ rec.lastApplied                        // ② 没人动过（模板或规则变了）
     or (trigger == OPEN and current ≈ 任一 baseline): //    或 .idea 被删 / 新 worktree
    write(target); rec.lastApplied = target; sync()      //    静默跟随

else:                                                    // ③ 被手动改过
    if sessionIgnored[path] ≈ current: return
    常驻提醒（带差异）[Restore template values] [Save as project custom] [Ignore]
```

`baseline` 是项目的 `.idea` 被（重新）创建时会拿到的值。**实测发现有两种**，两者都算：
- **IDE 从未打开过的项目**会继承 default project 的 Maven 设置：设置了默认模板时就是 §7.1 预写入的值，否则是用户在 "Settings for New Projects" 里配置的值。
- **IDE 打开过的项目**，删掉 `.idea` 后再打开，拿到的是 IDE 出厂默认值（Bundled Maven 3 / 空 / 空），不会再继承 default project。

**baseline 规则的取舍**：删除 `.idea` 后重新打开，Maven 设置会回到 baseline，这时 current ≠ lastApplied，不能当成手动修改，否则每次删 `.idea` 都会误报。代价是：如果用户在两次会话之间手动把某个项目改回了恰好等于 baseline 的值，下次打开时会被静默覆盖。这种情况很少见，而且"改回默认"本身就有歧义，可以接受。运行时（`CHANGED_AT_RUNTIME`）的修改一律按 ③ 处理，不走 baseline 判断。

### 5.3 通知按钮

| 按钮 | 行为 |
|---|---|
| **Undo**（首次接管） | 写回 `before`；把该项目绑定设为 `NOT_MANAGED`；清空 lastApplied；sync。设为 NOT_MANAGED 是为了下次打开时不再被当成首次接管 |
| **Restore template values** | `write(target)`；`lastApplied = target`；sync |
| **Save as project custom** | 把当前值存成该项目的 `CUSTOM` 绑定；`lastApplied = current`。不需要 sync，这些值本来就已经生效。如果当前 Maven home 是插件无法表示的类型（例如 `BundledMaven4`），不显示这个按钮 |
| **Ignore** | `sessionIgnored[path] = current`，只保存在内存里。值再次变化时会重新提醒 |

- 每个项目最多只有一条待处理的常驻提醒：发新提醒之前先让旧的失效（expire）。
- 提醒内容只列出不一致的字段，格式为 `模板值 → 当前值`。

### 5.4 写入

```kotlin
fun write(project, values) {
    // Runs on the caller's thread: at startup that is the activity's background thread,
    // so the write can win the race against Maven (§7.2). Serialized by a per-project lock.
    selfWriteDepth++                // listener ignores events while > 0
    gs.beginUpdate()
    try {
        gs.mavenHomeType    = values.toMavenHomeType()
        gs.userSettingsFile = values.userSettingsFile
        gs.localRepository  = values.localRepository
    } finally { gs.endUpdate(); selfWriteDepth-- }
}
fun sync(project) {
    MavenSettingsCache.getInstance(project).reload()
    val m = MavenProjectsManager.getInstance(project)
    if (m.isMavenizedProject) m.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("Maven Settings Templates"))
}
```

防循环有两层：

1. `selfWriteDepth` 标记：插件自己写入时产生的事件直接忽略。
2. "值不一致才写"：即使标记失效，插件自己写入后 current ≈ lastApplied ≈ target，`evaluate` 什么也不会做。

**所有 Maven API 调用只出现在 `MavenSettingsAccess` 一个文件里**。以后 Maven API 再变，只需要改这一个地方。

## 6. 路径规范化

只在比较和匹配时使用，**不改写存储的值**；写入 IDE 的是模板原文展开 `${user.home}` 之后的结果。

```text
normalize(p):
  1. 展开 ${user.home} → System.getProperty("user.home")
  2. 反斜杠统一成 /
  3. 去掉末尾的 /
  4. 文件系统大小写不敏感时（Windows）转为小写
```

用在以下地方：规则匹配、`ProjectRecords` 的 key（来自 `project.basePath`）、`current ≈ target` 比较、`MavenInSpecificPath` 比较。

`project.basePath` 为 null 的项目直接跳过。

## 7. 生效时机

### 7.1 IDE 启动：预写入 default project

在 `AppLifecycleListener` 中，如果设置了默认模板，就把默认模板写入 `ProjectManager.getInstance().defaultProject` 的 `MavenGeneralSettings`。修改默认模板并点 OK 后也再写入一次。

目的：新 clone 的项目、新 worktree 首次打开时，直接继承这份值。

**需实测**：default project 上能否拿到 `MavenWorkspaceSettingsComponent`；新打开的、没有 `.idea` 的项目是否会继承这份值。

### 7.2 项目打开：抢在 Maven 首次导入之前

```xml
<postStartupActivity implementation="…MavenSettingsTemplatesStartupActivity" order="first"/>
```

**这是一个尽力而为的竞态，不是保证**（§3.5）。平台并发启动所有 activity，Maven 的 activity 启动后很快就会读取设置。我们能做的是尽量抢到这个窗口：

1. `order="first"`：让我们**先启动**。
2. `execute()` 一开始就**在当前线程同步完成解析和写入**：在写入之前不挂起、不切换到 EDT，也不做任何 I/O。解析只读内存里的应用级状态，写入只是给 `MavenGeneralSettings` 的字段赋值，都是微秒级操作。
3. 写入之后，再挂载 DriftWatcher 并发出通知（这些可以慢）。

**两种结果都正确**：
- **抢到了**：Maven 读到的就是正确的值，首次导入用对配置，不会多跑 sync。
- **没抢到**：Maven 读到了旧值。我们写入后调用 `MavenSettingsCache.reload()`，再按 §5.4 调度 full sync，最终配置正确，代价是多一次 sync。§5.2 的"值不一致才写、才 sync"保证了这种降级是自动发生的。

**竞态只在"打开时需要改值"的情况下才会出现**：
- 最常见的情况是已有项目、值没变（current ≈ lastApplied ≈ target），这时什么都不写，也就不存在竞态。
- 新项目 / 新 worktree 在规则模板等于默认模板时，§7.1 的预写入已经让值正确了，同样不存在竞态。
- 只有"命中的规则模板 ≠ 默认模板"的新项目，以及"关闭 IDE 期间模板被修改过"的已有项目，才会进入这个竞态。

**需实测**：在日志里记录我们的写入时刻，以及 Maven 同步日志中实际使用的 settings 文件，统计抢到的概率。

### 7.3 插件设置点 OK

对所有已打开的项目执行 `evaluate(project, SETTINGS_APPLIED)`：没人动过的项目静默跟随，被手动改过的弹提醒。

### 7.4 运行时变化：DriftWatcher

- 给每个项目的 `MavenGeneralSettings` 挂 listener（Disposable 为项目级 service）。
- 事件去抖 500ms 后执行 `evaluate(project, CHANGED_AT_RUNTIME)`，连续多次修改只产生一次评估。
- **实例被替换的问题（§3.5）**：DriftWatcher 记录挂载时的实例引用。在 `ApplicationActivationListener`（IDE 窗口重新获得焦点时，外部修改 `.idea` 通常就发生在这之前）中检查实例是否变化；如果变了，就重新挂载并执行一次 `evaluate`。
- 不监控 default project。对解析结果为 null（不动）的项目，也不做任何处理。

**需实测**：Maven 自身的 `MavenGeneralSettingsWatcher` 在设置变化后是否会自动 sync，以及我们主动触发的 sync 会不会和它重复。

## 8. 设置页

位置：`Settings > Build, Execution, Deployment > Build Tools > Maven > Settings Templates`

注册为 `projectConfigurable`，`parentId="MavenSettings"`。项目级页面可以读写应用级数据，也能知道当前是哪个项目，所以一个页面就够了。挂载失败时的降级位置：`Settings > Tools > Maven Settings Templates`。

界面文字为英文，示意如下：

```text
┌ Current Project ──────────────────────────────────────────────┐
│ D:\work\CompanyA\order-service                                 │
│ ◉ Follow rules  (matched: D:\work\CompanyA → CompanyA)         │
│ ○ Use template [ CompanyA        ▼]                            │
│ ○ Custom values (expands the three fields)                     │
│ ○ Not managed                                                  │
│ Effective: CompanyA · settings = C:\Users\...\settings-A.xml   │
└────────────────────────────────────────────────────────────────┘
┌ Templates ────────────────────────────────────────────────────┐
│ [+][-][Copy][★ Default] │ Name            [CompanyA          ] │
│ ★ Personal              │ Maven home      [Bundled (Maven 3)▼][…]│
│   CompanyA              │ User settings   [                  ][📁]│
│   CompanyB              │ Local repository[                  ][📁]│
│                         │ (empty fields show IDE default in grey)│
└────────────────────────────────────────────────────────────────┘
┌ Folder Rules (deepest matching folder wins) ──────────────────┐
│ [+][-]                                                        │
│ ☑ D:\work\CompanyA           → CompanyA                        │
│ ☑ D:\work\CompanyA\legacy    → Legacy                          │
└────────────────────────────────────────────────────────────────┘
┌ Project Records ───────────────────────────────────────────────┐
│ Path                              Binding     [Remove][Remove missing]│
│ D:\work\CompanyA\order-service    Follow rules                  │
│ D:\old\demo (missing)             Not managed                   │
└────────────────────────────────────────────────────────────────┘
```

- 三个路径字段都用 `TextFieldWithBrowseButton`：Maven home 和 Local repository 选文件夹，User settings file 选文件。输入框可以手动编辑，这样才能写 `${user.home}`。
- 字段留空时，用灰色占位文字显示 IDE 默认值。
- 从 "Settings for New Projects"（default project）打开时，隐藏 Current Project 区块。
- 校验：规则文件夹重复时不允许 Apply；路径不存在时只给出警告，不阻止保存。
- 规则列表按文件夹路径排序显示，不支持手动排序。

## 9. 代码结构

包名：`io.github.shizzhang0.mavensettingstemplates`（Kotlin 包名不能带连字符）

```text
core/       model.kt, MavenValues.kt, PathNormalizer.kt,
            SettingsResolver.kt, Decision.kt                              ← 纯数据与纯逻辑，不依赖 IDE 服务
settings/   TemplatesSettings.kt, ProjectRecords.kt                       ← 持久化（PersistentStateComponent）
maven/      MavenSettingsAccess.kt                                        ← 唯一调用 Maven API 的地方
apply/      ProjectEvaluator.kt, DefaultProjectSeeder.kt, StartupActivity.kt,
            SettingsAppliedHandler.kt, DriftWatcher.kt
ui/         MavenSettingsTemplatesConfigurable.kt + panels
notify/     MstNotifications.kt
```

数据模型（`Template`、`FolderRule`、`Binding`、`TemplatesConfig`、`ProjectRecord`）放在 `core/` 而不是 `settings/`：`SettingsResolver` 和 `decide()` 需要用到它们，放在 `settings/` 会让 `core` 反过来依赖持久化层。§5.2 的决策逻辑被抽成纯函数 `decide()`，这样可以用临时测试单独验证。

## 10. 脚手架调整

- `build.gradle.kts`：`intellijIdea("2026.2.x")`（具体版本以 Gradle 能解析到的 2026.2 最新补丁版为准），并添加 `bundledPlugin("org.jetbrains.idea.maven")`。
- `gradle.properties`：group 改为 `io.github.shizzhang0`。
- `plugin.xml`：
  - id 改为 `io.github.shizzhang0.mavensettingstemplates`（**发布后不能再改**）
  - name 改为 `Maven Settings Templates`
  - 添加 `<depends>org.jetbrains.idea.maven</depends>`
  - since-build `262`，不设 until-build
  - vendor 改为 `Timothy`，url 指向 `https://github.com/shizzhang0`
  - description 改成真实内容（英文）
- `README.md`：把模板自带的说明换成这个插件的英文介绍。
- 删除 `MyToolWindowFactory` 和 `MyMessageBundle`，新建英文资源包 `messages/MavenSettingsTemplatesBundle.properties`。
- 注册两个 `notificationGroup`（漂移提醒、应用提示），都用常驻提示（sticky balloon）。

## 11. 错误处理

| 情况 | 处理 |
|---|---|
| 路径不存在（settings.xml、仓库目录、Maven home） | 照样写入，由 IDE / Maven 报错；设置页里给出警告 |
| 写入或 sync 抛异常 | 捕获并记录日志，同一项目在本次会话中只弹一次错误提示 |
| 预写入 default project 失败 | 记录日志后静默降级，只依赖 §7.2 |
| `project.basePath == null` | 跳过 |
| 模板引用失效 | 见 §4.1 |

## 12. 验证方式

按用户的全局约定，**不保留单元测试**。纯逻辑部分（`SettingsResolver`、`PathNormalizer`）可以写临时测试来验证，验证完成后删除。

主要验证手段是在 `runIde` 中逐条手动走下面的场景，另外 `buildPlugin` 和 `verifyPlugin` 必须通过。

1. **首次导入用对配置**：在规则文件夹下新 clone 一个项目并打开，Maven 同步日志中使用的 settings 是模板值，而且只跑了一次 sync。
2. **竞态实测**：在"规则模板 ≠ 默认模板"的新项目上重复打开多次，对照我们的写入日志和 Maven 同步日志中实际使用的 settings 文件，统计抢到的概率；抢输的那几次，最终配置也必须正确（多一次 sync）。
3. **default project 预写入**：打开 Settings for New Projects，Maven 页显示的是默认模板的值；新建项目后继承这些值。
4. **新 worktree**：在规则文件夹下 `git worktree add` 并打开，自动应用对应模板，出现首次接管提示。
5. **删除 `.idea` 后重新打开**：静默恢复模板值，不出现常驻提醒。
6. **手动修改**：在 Maven 设置里修改并点 OK，出现带差异的常驻提醒；三个按钮分别验证。
7. **连续修改**：连续改多次，只出现一条提醒。
8. **修改模板**：改完点 OK，没人动过的已打开项目静默跟随；被手动改过的项目弹提醒。
9. **Undo**：首次接管后点 Undo，值被恢复，项目变为 Not managed；重新打开后不会再被应用。
10. **Not managed / 无命中**：插件不做任何写入，也不弹任何提醒。
11. **外部修改 workspace.xml**：外部编辑后切回 IDE，监听器重新挂载，漂移被检测到。
12. **`${user.home}`**：写入 IDE 的是展开后的绝对路径。
13. **规则优先级**：嵌套文件夹下的项目命中最深的那条规则；`CompanyAB` 不会误命中 `CompanyA`。

## 13. 不做的事（YAGNI）

- 通配符规则、手动排序
- Bundled Maven 4
- 字段级"不接管"
- 自动把路径替换成 `${user.home}`
- 自动改回（锁定模式）
- 2026.2 之前版本的兼容层
- 除 `${user.home}` 之外的变量（例如 `${env.X}`）
- 多语言界面（只提供英文）

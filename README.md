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

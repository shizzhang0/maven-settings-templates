<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Maven Settings Templates Changelog

## [Unreleased]

### Changed

- The Maven home field works like the one in the IDE's own Maven settings: pick Bundled (Maven 3), the Maven wrapper or a detected Maven installation from the list, or type or browse to a path.
- The template editor shows the Maven version and global settings file of the chosen Maven home, and warns when a folder is not a Maven home.
- An empty local repository field now shows the repository that will actually be used, including one set in the Maven home's `conf/settings.xml`.

## [1.0.0] - 2026-09-23

### Added

- Templates for Maven home, user settings file and local repository, with `${user.home}` support.
- Folder rules (the deepest matching folder wins), a default template, and per-project overrides.
- Automatic application when a project opens, with an Undo notification the first time a project is managed.
- New projects inherit the default template through the default project settings.
- Drift notifications with Restore template values, Save as project custom, and Ignore.

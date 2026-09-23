<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Maven Settings Templates Changelog

## [Unreleased]

### Added

- Templates for Maven home, user settings file and local repository, with `${user.home}` support.
- Folder rules (the deepest matching folder wins), a default template, and per-project overrides.
- Automatic application when a project opens, with an Undo notification the first time a project is managed.
- New projects inherit the default template through the default project settings.
- Drift notifications with Restore template values, Save as project custom, and Ignore.

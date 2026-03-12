# Fixture Navigator

A PyCharm plugin for quickly searching and jumping to any `@pytest.fixture` definition in your project.

## Features

- Searchable popup listing every `@pytest.fixture` found in `test_*.py` and `conftest.py` files
- Filter by fixture **name** and/or **file path** simultaneously
- **Browse button** to pick a root folder from a directory chooser
- **Multiple selection** — open several fixtures at once (Shift+Click / Ctrl+Click)
- **Auto-updated index** — file watcher re-indexes in the background whenever a test file is saved
- **Pre-fills name filter** from selected text in the editor
- Right-click any folder in the Project tree → **Open Fixture Navigator Here** (pre-filtered to that folder)
- Keyboard navigation: arrows, Enter, F4, double-click

## Shortcuts

| Action | Default |
|--------|---------|
| Open Fixture Navigator | `Cmd+Shift+F` (macOS) / `Ctrl+Shift+F` (Linux/Windows) |
| Navigate to selected fixture | `Enter` or `F4` |
| Open multiple fixtures | `Shift+Click` / `Ctrl+Click`, then `Enter` |

Shortcuts can be changed in **Settings → Keymap → Fixture Navigator**.

## Requirements

- PyCharm Professional 2025.1
- JDK 21

## Installation

### From a release zip

1. Download the latest `.zip` from [Releases](../../releases)
2. In PyCharm: **Settings → Plugins → ⚙ → Install Plugin from Disk**
3. Select the downloaded zip and restart PyCharm

### From source

```bash
./gradlew buildPlugin
# zip is output to build/distributions/
```

Then install from disk as above.

## Development

Install JDK 21 if needed:

```bash
# macOS
brew install --cask temurin@21

# Linux (SDKMAN)
sdk install java 21-tem

# Windows (Scoop)
scoop install temurin21-jdk
```

```bash
# Launch a PyCharm instance with the plugin loaded
./gradlew runIde

# Or use the helper script
./scripts/dev.sh
```

Gradle downloads PyCharm and all dependencies automatically on first run. There are no automated tests — exercise the plugin manually inside the launched PyCharm instance.

## Scripts

| Script | Description |
|--------|-------------|
| `./scripts/dev.sh` | Build and launch PyCharm with the plugin |
| `./scripts/build.sh` | Build the distributable zip |
| `./scripts/release.sh <version>` | Bump version, build, and tag for release |

## Project structure

```
src/main/kotlin/com/yourplugin/
    FunctionNavigatorAction.kt   # All plugin logic (single file)
src/main/resources/META-INF/
    plugin.xml                   # Plugin metadata and action registration
```

## Architecture

Three classes in one file:

- **`FunctionNavigatorAction`** — keyboard shortcut / menu action; reads editor selection as initial name filter
- **`FixtureNavigatorTreeAction`** — Project tree right-click action; pre-filters by selected folder
- **`FixtureNavigatorDialog`** — Swing dialog with dual search fields, browse button, and filtered JList
- **`FixtureCellRenderer`** — renders fixture name in bold and `file:line` in gray

Fixture detection walks PSI trees of all matching files looking for `PyFunction` nodes decorated with `@pytest.fixture`. Results are cached per project and automatically refreshed by a `BulkFileListener` whenever a relevant file changes.

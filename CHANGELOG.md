# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-03-12

### Added
- Searchable popup listing every `@pytest.fixture` in `test_*.py` and `conftest.py` files
- Dual filter fields: filter by fixture name and file path simultaneously
- Browse button on the path field to pick a root folder via directory chooser
- Multiple selection support — open several fixtures at once (Shift+Click / Ctrl+Click)
- Automatic index refresh via file watcher — re-indexes in the background whenever a test file changes
- Pre-fills name filter from selected text in the editor when opening the dialog
- Right-click action on Project tree folders: **Open Fixture Navigator Here**
- Keyboard navigation: arrow keys, Enter, F4 to jump to definition; double-click support
- Default shortcut: `Cmd+Shift+F` (macOS) / `Ctrl+Shift+F` (Linux/Windows)
- First-open scan runs in a background thread to avoid blocking the UI
- Per-project fixture cache — subsequent opens are instant

[Unreleased]: https://github.com/your-username/fixture-navigator/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/your-username/fixture-navigator/releases/tag/v0.1.0

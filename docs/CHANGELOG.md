# Changelog

All notable changes to the Muse project will be documented in this file.

## [1.123] - 2026-07-15

### Fixed
- Build: remove settings.shutdown() from onCreate (causes NPE on Flow collect)
- DB migration MIGRATION_34_35 childCount

## [1.122] - 2026-07-15

### Added
- Model routing, send queue, memory dedup, context management

## [1.121] - 2026-07-15

### Changed
- Full technical debt cleanup (97 items)
- i18n hardcoded Chinese strings extraction

## [1.120] - 2026-07-15

### Added
- Release signing configuration
- Cloud backup AES-256-GCM encryption
- Biometric unlock

### Fixed
- License screen crash
- Switch button black box overlay
- CancellationException propagation (11 places)

## [1.119] - 2026-07-14

### Added
- GitHub open source release (GPL v3)
- Update check via GitHub Releases API

---

> Full version history: see [docs/12-version-history.md](docs/12-version-history.md)
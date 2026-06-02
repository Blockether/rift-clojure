# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial release: Clojure binding to rift (copy-on-write workspaces) via the
  JDK Foreign Function & Memory API. `init` / `create` / `remove!` / `list` /
  `ancestors` / `gc`. Native libraries built per-platform by CI and bundled
  into the published jar. Version tracks the vendored rift release 1:1.

[Unreleased]: https://github.com/Blockether/rift-clojure/commits/main

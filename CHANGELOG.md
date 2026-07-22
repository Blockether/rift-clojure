# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.0.10-7] - 2026-07-22

### Changed
- Vendor rift native from Blockether/rift `v0.0.10-7`, which adds a conditional-chmod
  perf tweak on top of the APFS filtered-clone EACCES fix: `copy_metadata_apfs` no
  longer issues a redundant second `chmod` for already-writable (working-tree) sources,
  keeping the widen+restore only for read-only `0o444` Git objects.

## [v0.0.10-6] - 2026-06-24

### Fixed
- Vendor rift native from Blockether/rift `v0.0.10-6`, which carries the APFS
  filtered-clone fix: `create` no longer fails with `Permission denied (os error 13)`
  when cloning a committed git repo (read-only `0444` `.git` objects with the
  `com.apple.provenance` xattr). Clones stay owner-writable while metadata is
  copied, then the source's exact mode is restored last.

## [v0.0.10-5] - 2026-06-24

### Changed
- feat: drop tools.deps from default deps (native-image clean) + bump build tooling
- feat(graalvm): ship native-image config + GraalPy-safe ofAuto arena
- release: update version files for v0.0.10-3


## [v0.0.10-4] - 2026-06-24

### Changed
- feat(graalvm): ship native-image config + GraalPy-safe ofAuto arena


## [v0.0.10-3] - 2026-06-23

### Changed
- release: 0.0.10-3 (tools.deps native resolver)
- fix(resolver): resolve native jar via tools.deps, not hand-rolled HTTP
- release: update version files for v0.0.10-2
- fix: read version from namespaced rift/VERSION (avoid classpath VERSION collision)
- release: update version files for v0.0.10-1


## [v0.0.10-2] - 2026-06-22

### Changed
- fix: read version from namespaced rift/VERSION (avoid classpath VERSION collision)


## [v0.0.10-1] - 2026-06-21

### Changed
- Prepare 0.0.10-1 Clojars release
- Split rift native artifacts by platform
- release: update version files for v0.0.10


## [v0.0.10] - 2026-06-07

### Changed
- chore(ci): bump GitHub Actions to Node 24 runtimes
- ci(darwin-x64): unambiguous Rosetta proof — host arm64 vs x86_64 java
- ci(darwin-x64): log hard proof — dylib is Mach-O x86_64 + JVM under Rosetta
- ci: cross-build + Rosetta-test darwin-x64 on Apple Silicon
- ci: cache Rust build (Swatinem/rust-cache) — kill cold cdylib compile
- docs(readme): drop Native libraries build/CI section
- release: update version files for v0.0.8


## [v0.0.8] - 2026-06-02

### Changed
- ci: drop darwin-x64 (Intel Mac) — Apple Silicon only
- docs: add Clojars + CI badges
- docs: trim README of surplus
- test: drop Windows handling — Linux + macOS only for now
- ci: run the full 4-platform matrix on every push (not just deploy)
- feat: default *database* binding so callers can skip :database
- test: require real CoW everywhere — no opt-in, no fallback
- test(ci): run the REAL rift CoW round-trip on Linux too (btrfs)
- test(ci): gate Clojars deploy on per-platform binding tests
- feat: rift-clojure — CoW workspaces via JDK FFM binding


### Added
- Initial release: Clojure binding to rift (copy-on-write workspaces) via the
  JDK Foreign Function & Memory API. `init` / `create` / `remove!` / `list` /
  `ancestors` / `gc`. Native libraries built per-platform by CI and bundled
  into the published jar. Version tracks the vendored rift release 1:1.

[Unreleased]: https://github.com/Blockether/rift-clojure/compare/v0.0.10-5...HEAD
[v0.0.8]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.8
[v0.0.10]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10
[v0.0.10-1]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-1
[v0.0.10-2]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-2
[v0.0.10-3]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-3
[v0.0.10-4]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-4
[v0.0.10-5]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-5

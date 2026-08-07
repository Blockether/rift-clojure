# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.0.10-11] - 2026-08-07

### Changed
- build: pin the rift source by version, not by commit
- feat: hand a managed workspace over clean
- release: update version files for v0.0.10-10


### Added
- `rift/clean!` hands a managed workspace over without the changes that were
  pending in it: it resets the detached `HEAD`, real index, and worktree to
  `:commit` (default `HEAD`), removes untracked and ignored state while preserving
  `.rift`, and records removed paths in `rift/excluded`. A workspace with no Git
  repository is left exactly as it was copied.

### Changed
- The binding is pinned to the rift source by VERSION, not by commit: CI and the
  release build check out `Blockether/rift` tag `v$(cat resources/VERSION)`, so a
  published `com.blockether/rift X.Y.Z` always wraps `rift vX.Y.Z`.

## [v0.0.10-10] - 2026-08-03

### Changed
- release: 0.0.10-10 — name the mechanism that made a workspace
- ci: build the native from the rift source that reports a clone's mechanism
- test: let the pinned native decide how strictly kind is asserted
- feat: report the mechanism that made a workspace
- release: update version files for v0.0.10-9


## [v0.0.10-10] - 2026-08-03

### Added
- `rift/create-detailed` — like `create`, but returns `{:path :kind}` where
  `:kind` is the mechanism that actually made the workspace: `:btrfs`,
  `:reflink`, `:apfs`, `:worktree` (rift's linked-Git-worktree fallback for
  filesystems without copy-on-write) or `:copy`. Callers that label a workspace
  could only name rift, never how it was made. `nil` against an older native.

### Changed
- Vendor the rift source that reports a clone's mechanism. Every platform's
  binding test now demands a `:kind` and cross-checks it against the disk: a
  `:worktree` clone owns a `.git` FILE, a copy-on-write clone a `.git`
  DIRECTORY.

## [v0.0.10-9] - 2026-08-03

### Changed
- release: 0.0.10-9 — expose what a clone excluded
- release: update version files for v0.0.10-8
- docs: correct v0.0.10-8 changelog date


## [v0.0.10-9] - 2026-08-03

### Added
- `rift/excluded` — the paths `create` left out of a workspace, relative to its
  root, read from the clone's `.rift` marker. A filtered clone omits regenerable
  artifact trees and whatever the source repository ignores; consumers used to
  mirror those rules and drift from them. Returns `[]` for workspaces created
  before rift `v0.0.10-9`.

### Changed
- Vendor rift native from Blockether/rift `v0.0.10-9`: a git-**tracked**
  `dist/`, `build/`, or `target/` is no longer dropped from a clone (the index
  now beats the built-in artifact list), and `create` records what it excluded
  in the workspace marker.

## [v0.0.10-8] - 2026-08-03

### Changed
- release: 0.0.10-8 — vendor gitignore-aware rift clones (build from Blockether/rift v0.0.10-8)
- release: 0.0.10-7 — vendor rift conditional-chmod perf (build from Blockether/rift v0.0.10-7)
- release: update version files for v0.0.10-6


## [v0.0.10-8] - 2026-08-03

### Changed
- Vendor rift native from Blockether/rift `v0.0.10-8`, which makes repository
  clones gitignore-aware: `CopyFilter` now carries a `RepositoryIgnore` built
  from the source repo, and ignored entries are pruned inside
  `WalkDir::filter_entry`, so an ignored directory costs one check instead of
  one clone per file. Cloning a real 20k-entry repository drops from ~3.6s to
  ~0.36s (10x). Force-added paths in the index and `.git` itself are never
  filtered, so a fresh clone's `git status` matches the source's.

## [v0.0.10-7] - 2026-07-22

### Changed
- Vendor rift native from Blockether/rift `v0.0.10-7`, which adds a conditional-chmod
  perf tweak on top of the APFS filtered-clone EACCES fix: `copy_metadata_apfs` no
  longer issues a redundant second `chmod` for already-writable (working-tree) sources,
  keeping the widen+restore only for read-only `0o444` Git objects.

## [v0.0.10-6] - 2026-07-22

### Changed
- release: 0.0.10-6 — vendor rift EACCES fix (build native from Blockether/rift v0.0.10-6)
- ci: pin rift source ref to v0.0.10 (was deriving v<clj-version> → 404)
- release: update version files for v0.0.10-5
- feat: drop tools.deps from default deps (native-image clean) + bump build tooling
- release: update version files for v0.0.10-4

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

[Unreleased]: https://github.com/Blockether/rift-clojure/compare/v0.0.10-11...HEAD
[v0.0.10-9]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-9
[v0.0.10-8]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-8
[v0.0.10-7]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-7
[v0.0.8]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.8
[v0.0.10]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10
[v0.0.10-1]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-1
[v0.0.10-2]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-2
[v0.0.10-3]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-3
[v0.0.10-4]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-4
[v0.0.10-5]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-5
[v0.0.10-6]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-6
[v0.0.10-10]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-10
[v0.0.10-11]: https://github.com/Blockether/rift-clojure/releases/tag/v0.0.10-11

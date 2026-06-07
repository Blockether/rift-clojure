# rift-clojure

[![Clojars Project](https://img.shields.io/clojars/v/com.blockether/rift.svg)](https://clojars.org/com.blockether/rift)
[![CI](https://github.com/Blockether/rift-clojure/actions/workflows/ci.yml/badge.svg)](https://github.com/Blockether/rift-clojure/actions/workflows/ci.yml)

Clojure binding to [**rift**](https://github.com/anomalyco/rift) — copy-on-write
development workspaces, a faster alternative to `git worktree` (instant, near-zero
disk via APFS `clonefile` on macOS / btrfs snapshots on Linux).

It loads rift's native `librift_ffi` **in-process** through the JDK Foreign
Function & Memory API (`java.lang.foreign`) — no subprocess, no JNI. Same shared
library rift ships for Bun/Node, called from the JVM.

> ⚠️ rift is **experimental**. This is a **vendored binding**: its version tracks
> the rift release 1:1 — `com.blockether/rift X.Y.Z` bundles `rift vX.Y.Z`.

## Install

```clojure
com.blockether/rift {:mvn/version "0.0.10"}
```

Run the JVM with native access enabled (else the linker refuses to load the lib):

```
--enable-native-access=ALL-UNNAMED
```

## Usage

```clojure
(require '[com.blockether.rift :as rift])

(rift/init   {:at "/repo"})                         ; register a rift root
(def ws (rift/create {:from "/repo" :name "fix"}))  ; => CoW copy path
(rift/list      {:of "/repo"})                      ; => direct children
(rift/ancestors {:of ws})                           ; => parents, nearest first
(rift/remove!   {:at ws})                            ; trash it
(rift/gc)                                            ; delete trashed storage
```

Every fn takes an optional `:database` (rift's SQLite registry). Set a default
once instead of repeating it:

```clojure
(rift/set-default-database! "/path/to/rift.sqlite")  ; process-wide
(rift/with-database "/tmp/test.sqlite" …)            ; scoped
```

Precedence: explicit `:database` > `with-database` > `set-default-database!` >
platform default (`(rift/default-database)`).

Errors are `ex-info` with `{:type :rift/error :code <string> :path <string?>}`
(`cow_unavailable`, `workspace_not_initialized`, `already_exists`, …).

## Platforms

| Platform       | Library             | Backend          |
| -------------- | ------------------- | ---------------- |
| `darwin-arm64` | `librift_ffi.dylib` | APFS `clonefile` |
| `linux-x64`    | `librift_ffi.so`    | btrfs snapshots  |
| `linux-arm64`  | `librift_ffi.so`    | btrfs snapshots  |

Linux CoW requires a **btrfs** filesystem.

## Develop

```
clojure -X:test          # run the test suite
clojure -T:build jar     # build the jar
clojure -T:build install # install to ~/.m2
```

## License

MIT (matching rift); vendored `librift_ffi` binaries © the rift authors, MIT.

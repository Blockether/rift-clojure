# rift-clojure

Clojure binding to [**rift**](https://github.com/anomalyco/rift) — copy-on-write
development workspaces, a faster alternative to `git worktree` (instant creation,
near-zero disk via APFS `clonefile` on macOS / btrfs snapshots on Linux).

The binding loads rift's native `librift_ffi` **in-process** through the JDK
Foreign Function & Memory API (`java.lang.foreign`, stable since JDK 22) — no
subprocess, no JNI, no shelling out. It's the same shared library rift ships for
its Bun/Node bindings; we just call it from the JVM.

> ⚠️ rift itself is marked **experimental**. This is a **vendored binding**:
> its version tracks the rift release it wraps 1:1 — `com.blockether/rift X.Y.Z`
> bundles `rift vX.Y.Z`. The release tag *is* the rift ref we build against.

## Install

```clojure
;; deps.edn
com.blockether/rift {:mvn/version "0.0.8"}
```

You **must** run the JVM with native access enabled:

```
--enable-native-access=ALL-UNNAMED
```

Otherwise the first call prints a restricted-method warning (and a future JDK
defaulting to `--illegal-native-access=deny` would refuse to load the library).

## Usage

```clojure
(require '[com.blockether.rift :as rift])

(rift/init   {:at "/repo"})                         ; register a rift root
(def ws (rift/create {:from "/repo" :name "fix"}))  ; => "/…/.rifts/repo/fix"  (CoW copy)
(rift/list      {:of "/repo"})                      ; => ["/…/fix" …]  direct children
(rift/ancestors {:of ws})                           ; => parents, nearest first
(rift/remove!   {:at ws})                            ; trash the workspace
(rift/gc)                                            ; physically delete trashed storage
```

Every fn takes an optional `:database` to point at a specific SQLite registry
instead of the default (handy for per-repo isolation and tests).

Errors surface as `ex-info` with `{:type :rift/error :code <string> :path <string?>}`,
mirroring rift's failure codes (`cow_unavailable`, `workspace_not_initialized`,
`already_exists`, …).

## Supported platforms

| Platform       | Library              | Backend                  |
| -------------- | -------------------- | ------------------------ |
| `darwin-arm64` | `librift_ffi.dylib`  | APFS `clonefile`         |
| `darwin-x64`   | `librift_ffi.dylib`  | APFS `clonefile`         |
| `linux-x64`    | `librift_ffi.so`     | btrfs snapshots          |
| `linux-arm64`  | `librift_ffi.so`     | btrfs snapshots          |

The native libraries are **built by CI and bundled into the published jar** —
they are NOT committed to this repo. On Linux, CoW operations require the
workspace to live on a **btrfs** filesystem.

## How the native libs are built

The libraries are compiled from rift's `crates/ffi` (a `cdylib` exporting
`rift_ffi_call` / `rift_ffi_free`). Because this is a vendored binding, the rift
ref we build against is the release tag itself (`v0.0.8` → rift `v0.0.8`); CI
derives it from `resources/VERSION`.

- **Release (canonical):** `.github/workflows/deploy.yml` runs on every `v*`
  tag. It builds all four targets on native GitHub runners (`ubuntu-latest`,
  `ubuntu-24.04-arm`, `macos-13`, `macos-14`), drops each `librift_ffi.*` into
  `resources/prebuilds/<platform>/`, and deploys the resulting jar — with all
  four binaries inside it — to Clojars. The binaries live only in CI and in the
  published artifact; they are never committed.
- **CI:** `.github/workflows/ci.yml` builds the runner's own lib and runs the
  binding test on every push / PR.
- **Locally:** run `scripts/build-natives.sh` once to populate
  `resources/prebuilds/` for your machine (macOS natively; Linux via Docker).
  The directory is git-ignored.

## Develop

```
clojure -X:test                      # FFM round-trip (needs --enable-native-access, set in :test)
clojure -T:build jar                 # build the jar
clojure -T:build install             # install to ~/.m2
```

## The ABI (for the curious)

```c
char* rift_ffi_call(const char* json_request);  // heap-allocated JSON reply
void  rift_ffi_free(char* reply);
```

Request `{"command":"create","from":"…"}` → reply
`{"status":"ok","value":…}` or `{"status":"error","error":{…}}`. That's the
whole contract; `com.blockether.rift` marshals Clojure maps ↔ JSON across it.

## License

MIT (matching rift). Vendored `librift_ffi` binaries are © the rift authors,
also MIT.

(ns com.blockether.rift
  "Clojure binding to rift — copy-on-write development workspaces, a faster
   alternative to `git worktree` — via the native `librift_ffi` shared library
   loaded in-process through the JDK Foreign Function & Memory API
   (`java.lang.foreign`, stable since JDK 22).

   The native ABI is exactly two C symbols, JSON in / JSON out:

     char* rift_ffi_call(const char* json_request)   ;; heap-allocated reply
     void  rift_ffi_free(char* reply)

   A request is `{\"command\" \"create\" ...}`; a reply is either
   `{\"status\" \"ok\" \"value\" ...}` or
   `{\"status\" \"error\" \"error\" {\"code\" .. \"message\" .. \"path\" ..}}`.

   Native loading checks, in order:

     1. `RIFT_NATIVE_PATH` / `com.blockether.rift.native.path`,
     2. a bundled `resources/prebuilds/<os>-<arch>/...` classpath resource,
     3. the matching `com.blockether/rift-native-<os>-<arch>` Clojars artifact
        downloaded into `~/.cache/clj-rift`.

   Supported platforms: darwin-arm64, darwin-x64, linux-x64, linux-arm64.

   REQUIRES the JVM flag `--enable-native-access=ALL-UNNAMED`; without it the
   first native call prints a restricted-method warning (and on a future JDK
   default of `--illegal-native-access=deny` would fail outright)."
  (:refer-clojure :exclude [list ancestors])
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File InputStream]
           [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.net URL]
           [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.util.jar JarFile]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Platform → bundled library
;; ---------------------------------------------------------------------------

(defn- platform
  "Return `[os arch]` in rift's prebuild vocabulary, e.g. `[\"darwin\" \"arm64\"]`.
   Throws on an unsupported OS/arch."
  []
  (let [os   (.toLowerCase ^String (System/getProperty "os.name"))
        arch (.toLowerCase ^String (System/getProperty "os.arch"))
        os*  (cond
               (or (.contains os "mac") (.contains os "darwin")) "darwin"
               (.contains os "linux")                            "linux"
               (.contains os "win")                              "windows"
               :else (throw (ex-info (str "Unsupported OS for rift: " os) {:os os})))
        arch* (cond
                (#{"aarch64" "arm64"} arch) "arm64"
                (#{"x86_64" "amd64"}  arch) "x64"
                :else (throw (ex-info (str "Unsupported arch for rift: " arch) {:arch arch})))]
    [os* arch*]))

(defn- lib-file-name [os]
  (case os
    "darwin"  "librift_ffi.dylib"
    "linux"   "librift_ffi.so"
    "windows" "rift_ffi.dll"))

(defn- configured-native-path ^Path []
  (when-let [p (or (System/getenv "RIFT_NATIVE_PATH")
                   (System/getProperty "com.blockether.rift.native.path"))]
    (.toPath (io/file p))))

(defn- bundled-library-path ^Path [res fname]
  (when-let [^URL url (io/resource res)]
    (if (= "file" (.getProtocol url))
      (.toPath (io/file url))
      (let [tmp (doto (File/createTempFile "librift_ffi" (subs fname (.lastIndexOf ^String fname ".")))
                  .deleteOnExit)]
        (with-open [in (io/input-stream url)]
          (io/copy in tmp))
        (.toPath tmp)))))

(defn- artifact-version []
  ;; Read a NAMESPACED resource. An unqualified "VERSION" at the jar root
  ;; collides with every other lib that ships one (fff, svar, …) — whichever is
  ;; first on the classpath wins, so rift would resolve a FOREIGN version and try
  ;; to download a nonexistent rift-native-<that-version> (HTTP 404). `rift/VERSION`
  ;; is unique to this jar; read only it so a packaging mistake fails loudly here
  ;; rather than silently resolving someone else's version.
  (str/trim (slurp (io/resource "rift/VERSION"))))

(defn- cache-root ^Path []
  (if-let [p (or (System/getenv "RIFT_CACHE_DIR")
                 (System/getProperty "com.blockether.rift.cache-dir"))]
    (.toPath (io/file p))
    (.toPath (io/file (System/getProperty "user.home") ".cache" "clj-rift"))))

(defn- native-artifact [platform]
  (str "rift-native-" platform))

(defn- resolve-native-jar ^Path [version platform]
  "Resolve the per-platform native jar through `clojure.tools.deps` — the same
   resolver the `clojure` CLI uses, so configured Maven repositories, mirrors and
   `~/.m2/settings.xml` are honoured (no hand-rolled HTTP to a hardcoded repo).
   Returns the jar's path in the local Maven repository. tools.deps is loaded via
   `requiring-resolve` so it is only touched on this runtime download path."
  (let [lib          (symbol "com.blockether" (native-artifact platform))
        create-basis (or (requiring-resolve 'clojure.tools.deps/create-basis)
                         (throw (ex-info "org.clojure/tools.deps is not on the classpath; cannot resolve the rift native artifact. Add com.blockether/<artifact>, set RIFT_NATIVE_PATH, or add tools.deps."
                                  {:lib lib})))
        basis        (create-basis {:project nil :extra {:deps {lib {:mvn/version version}}}})
        path         (-> basis :libs (get lib) :paths first)]
    (when-not path
      (throw (ex-info (str "Could not resolve " lib " " version
                        " via Clojure's dependency resolver. Check your Maven repositories / mirrors.")
               {:lib lib :version version})))
    (.toPath (io/file path))))

(defn- extract-native! ^Path [^Path jar-path res ^Path dest]
  (Files/createDirectories (.getParent dest) (make-array java.nio.file.attribute.FileAttribute 0))
  (with-open [jar (JarFile. (.toFile jar-path))]
    (let [entry (.getEntry jar res)]
      (when-not entry
        (throw (ex-info (str "Native artifact is missing " res) {:jar (str jar-path) :resource res})))
      (with-open [^InputStream in (.getInputStream jar entry)]
        (let [^"[Ljava.nio.file.CopyOption;" opts (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])]
          (Files/copy in dest opts))))
    dest))

(defn- downloaded-library-path ^Path [platform res fname]
  (when-not (#{"1" "true" "yes"} (some-> (System/getenv "RIFT_DISABLE_DOWNLOAD") str/lower-case))
    (let [version (artifact-version)
          root (cache-root)
          lib-path (.resolve root (str version "/" platform "/" fname))]
      (if (Files/exists lib-path (make-array java.nio.file.LinkOption 0))
        lib-path
        (extract-native! (resolve-native-jar version platform) res lib-path)))))

(defn- library-path
  "Return a real filesystem `Path` to the native library for the running
   platform. Prefer an explicit native path, then a bundled classpath resource,
   then the matching per-platform Clojars native artifact."
  ^Path []
  (let [[os arch] (platform)
        platform (str os "-" arch)
        fname (lib-file-name os)
        res (str "prebuilds/" platform "/" fname)]
    (or (configured-native-path)
        (bundled-library-path res fname)
        (downloaded-library-path platform res fname)
        (throw (ex-info (str "No rift native library for " platform
                             ". Add com.blockether/" (native-artifact platform)
                             ", set RIFT_NATIVE_PATH, or enable runtime download.")
                        {:platform platform :resource res})))))

;; ---------------------------------------------------------------------------
;; Native binding (lazy, process-lifetime)
;; ---------------------------------------------------------------------------

(defn- bind!
  "Load the bundled library and bind `rift_ffi_call` / `rift_ffi_free`.
   Returns `{:call MethodHandle :free MethodHandle}`."
  []
  (let [linker ^Linker (Linker/nativeLinker)
        ;; ofAuto (GC-managed, process-lifetime via the `handles` defonce), NOT
        ;; ofShared: a SHARED arena is incompatible with Truffle runtime
        ;; compilation, so a native image that also embeds GraalPy (e.g. vis)
        ;; fails to build with "Arena.ofShared is not supported with runtime
        ;; compilations". ofAuto keeps the library mapped for the process and
        ;; needs no -H:+SharedArenaSupport flag.
        arena  (Arena/ofAuto)
        path   (library-path)
        lookup ^SymbolLookup (SymbolLookup/libraryLookup path arena)
        opts   (make-array Linker$Option 0)
        sym    (fn ^MemorySegment [name]
                 (.orElseThrow (.find lookup name)))
        call-h (.downcallHandle linker (sym "rift_ffi_call")
                                (FunctionDescriptor/of ValueLayout/ADDRESS
                                                       (into-array MemoryLayout [ValueLayout/ADDRESS]))
                                opts)
        free-h (.downcallHandle linker (sym "rift_ffi_free")
                                (FunctionDescriptor/ofVoid
                                 (into-array MemoryLayout [ValueLayout/ADDRESS]))
                                opts)]
    {:call call-h :free free-h}))

(defonce ^:private handles (delay (bind!)))

(defn- invoke-raw
  "Send one JSON request string to the native library and return its JSON reply
   string. Allocates the request in a confined arena, reads the reply C string,
   then frees the native reply buffer via `rift_ffi_free`."
  ^String [^String request]
  (let [{:keys [^MethodHandle call ^MethodHandle free]} @handles]
    (with-open [arena (Arena/ofConfined)]
      (let [in-seg (.allocateFrom arena request)
            ret    ^MemorySegment (.invokeWithArguments call (object-array [in-seg]))]
        (when (or (nil? ret) (zero? (.address ret)))
          (throw (ex-info "rift native library returned a null response"
                          {:type :rift/protocol :request request})))
        (try
          (.getString (.reinterpret ret Long/MAX_VALUE) 0)
          (finally
            (.invokeWithArguments free (object-array [ret]))))))))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defn- call*
  "Run one rift command map, returning the reply `:value` (a path string, a
   vector of path strings, or nil). Throws `ex-info` with
   `{:type :rift/error :code .. :path ..}` on an error reply."
  [request]
  (let [reply (json/read-json (invoke-raw (json/write-json-str request)) :key-fn keyword)]
    (case (:status reply)
      "ok"    (:value reply)
      "error" (let [{:keys [code message path]} (:error reply)]
                (throw (ex-info (or message "rift error")
                                {:type :rift/error :code code :path path
                                 :command (:command request)})))
      (throw (ex-info "Unexpected rift response shape"
                      {:type :rift/protocol :reply reply})))))

(defn- s [x] (when (some? x) (str x)))

(defn- here [] (System/getProperty "user.dir"))

;; ---------------------------------------------------------------------------
;; Platform-default registry location
;; ---------------------------------------------------------------------------

(defn- data-local-dir
  "Per-OS local data directory, matching the Rust `dirs` crate that rift uses
   for its own default — so the path we resolve points at the SAME registry
   rift would pick. Honours the platform's env overrides:
     - macOS:   ~/Library/Application Support
     - Windows: %LOCALAPPDATA%        (else ~/AppData/Local)
     - Linux:   $XDG_DATA_HOME        (else ~/.local/share)"
  ^java.io.File []
  (let [os   (.toLowerCase ^String (System/getProperty "os.name"))
        home (System/getProperty "user.home")
        env  (fn [k] (not-empty (System/getenv k)))]
    (cond
      (or (.contains os "mac") (.contains os "darwin"))
      (io/file home "Library" "Application Support")

      (.contains os "win")
      (io/file (or (env "LOCALAPPDATA") (str (io/file home "AppData" "Local"))))

      :else
      (io/file (or (env "XDG_DATA_HOME") (str (io/file home ".local" "share")))))))

(defn default-database
  "The platform-default rift registry (SQLite) path — the same one rift would
   choose when no `:database` is given (`dirs::data_local_dir()/rift/rift.sqlite`):
     - macOS:   ~/Library/Application Support/rift/rift.sqlite
     - Windows: %LOCALAPPDATA%\\rift\\rift.sqlite
     - Linux:   $XDG_DATA_HOME/rift/rift.sqlite  (else ~/.local/share/…)"
  ^String []
  (str (io/file (data-local-dir) "rift" "rift.sqlite")))

;; ---------------------------------------------------------------------------
;; Default registry (so callers don't repeat :database everywhere)
;; ---------------------------------------------------------------------------

(def ^:dynamic *database*
  "Override for the rift registry (SQLite) path used when a call omits
   `:database`. `nil` ⇒ fall back to the platform default (`default-database`),
   which is always resolved and passed explicitly — so the default is honoured
   on every platform, never left to chance.

   Precedence, highest first:
     1. an explicit `:database` on the call,
     2. a `(with-database path …)` scope,
     3. this var's root value (set once via `set-default-database!`),
     4. the platform default (`(default-database)`)."
  nil)

(defn set-default-database!
  "Set the process-wide default registry path (the root value of `*database*`),
   so every later call uses it without passing `:database`. Pass `nil` to
   revert to rift's built-in default. Returns the path."
  [path]
  (alter-var-root #'*database* (constantly (s path)))
  path)

(defmacro with-database
  "Evaluate `body` with `*database*` bound to `path` (a scoped default that
   wins over `set-default-database!` but not over an explicit per-call
   `:database`)."
  [path & body]
  ;; Inline the coercion (don't call the private `s`) so the expansion is legal
  ;; at any call site.
  `(binding [*database* (some-> ~path str)] ~@body))

(defn- ensure-parent!
  "Make sure `path`'s parent directory exists, then return `path`. rift's
   `Manager::open` (the code path hit whenever we pass an explicit database)
   does NOT create the parent dir — only `open_default` does — so we create it
   to keep first-run working regardless of which registry path is used."
  ^String [^String path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent))
  path)

(defn- add-db
  "Attach the resolved registry path to a request: explicit `database`, else
   the `*database*` override, else the platform `default-database`. Always
   resolves to a concrete path (so the per-OS default is honoured) and ensures
   its parent directory exists."
  [request database]
  (assoc request :database
         (ensure-parent! (or (s database) *database* (default-database)))))

;; ---------------------------------------------------------------------------
;; Public API — mirrors the rift-snapshot JS surface
;; ---------------------------------------------------------------------------

(defn init
  "Initialize / register a rift root at `:at` (default: current dir). On Linux
   this converts an ordinary btrfs directory into a subvolume; on macOS it
   registers the source directory for APFS clonefile. Returns nil.

   Options: `:at`, `:database` (defaults to `*database*`)."
  ([] (init {}))
  ([{:keys [at database]}]
   (call* (add-db {:command "init" :at (s (or at (here)))} database))
   nil))

(defn create
  "Create a copy-on-write workspace from `:from` (default: current dir) and
   return the new workspace path (string). When `:from` is a git repo the new
   workspace has a detached HEAD with index + working-tree state retained.

   Options: `:from`, `:name`, `:into` (parent storage dir), `:database`."
  ([] (create {}))
  ([{:keys [from name into database]}]
   (call* (-> {:command "create" :from (s (or from (here)))}
              (cond-> name (assoc :name (s name))
                      into (assoc :into (s into)))
              (add-db database)))))

(defn remove!
  "Remove a created workspace at `:at` (default: current dir). With `:all true`
   removes the whole created subtree and returns a vector of removed paths;
   otherwise returns nil. Removed storage is trashed adjacent to the root until
   `gc` deletes it.

   Options: `:at`, `:all`, `:database`."
  ([] (remove! {}))
  ([{:keys [at all database]}]
   (let [v (call* (-> {:command "remove" :at (s (or at (here)))}
                      (cond-> (some? all) (assoc :all (boolean all)))
                      (add-db database)))]
     (when all (vec v)))))

(defn list
  "List direct active child workspaces of `:of` (default: current dir).
   Returns a vector of paths. Options: `:of`, `:database`."
  ([] (list {}))
  ([{:keys [of database]}]
   (vec (call* (add-db {:command "list" :of (s (or of (here)))} database)))))

(defn ancestors
  "List ancestor workspaces of `:of` (default: current dir), nearest first.
   Returns a vector of paths. Options: `:of`, `:database`."
  ([] (ancestors {}))
  ([{:keys [of database]}]
   (vec (call* (add-db {:command "ancestors" :of (s (or of (here)))} database)))))

(defn excluded
  "List the paths `create` left out of the workspace at `:of` (default: current
   dir), relative to its root, as recorded in the workspace's `.rift` marker.

   A filtered clone omits regenerable artifact trees and whatever the source
   repository ignores, so a consumer must not read their absence as a deletion.
   Ask the workspace instead of reimplementing rift's rules. Workspaces created
   before rift v0.0.10-9 have no record and return `[]`.

   Returns a vector of paths. Options: `:of`, `:database`."
  ([] (excluded {}))
  ([{:keys [of database]}]
   (vec (call* (add-db {:command "excluded" :of (s (or of (here)))} database)))))

(defn gc
  "Physically delete trashed storage and prune missing registry entries.
   Returns a vector of collected paths. Options: `:database`."
  ([] (gc {}))
  ([{:keys [database]}]
   (vec (call* (add-db {:command "gc"} database)))))

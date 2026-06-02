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

   We bundle the prebuilt library for every supported platform under
   `resources/prebuilds/<os>-<arch>/`, extract the matching one to a temp file
   at first use (you cannot `dlopen` from inside a jar), open it, and bind the
   two symbols. The library is mapped for the lifetime of the process.

   Supported platforms: darwin-arm64, darwin-x64, linux-x64, linux-arm64.

   REQUIRES the JVM flag `--enable-native-access=ALL-UNNAMED`; without it the
   first native call prints a restricted-method warning (and on a future JDK
   default of `--illegal-native-access=deny` would fail outright)."
  (:refer-clojure :exclude [list ancestors])
  (:require [charred.api :as json]
            [clojure.java.io :as io])
  (:import [java.io InputStream]
           [java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

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

(defn- extract-library!
  "Copy the bundled library for the running platform out of the classpath into
   a temp file and return its `Path`. The temp file is deleted on JVM exit."
  ^Path []
  (let [[os arch] (platform)
        fname     (lib-file-name os)
        res       (str "prebuilds/" os "-" arch "/" fname)
        url       (io/resource res)]
    (when-not url
      (throw (ex-info (str "No bundled rift library for " os "-" arch
                        " (missing classpath resource " res ")")
               {:os os :arch arch :resource res})))
    (let [suffix (subs fname (.lastIndexOf ^String fname "."))
          tmp    (Files/createTempFile "librift_ffi" suffix (make-array FileAttribute 0))]
      (let [opts ^"[Ljava.nio.file.CopyOption;"
            (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])]
        (with-open [in ^InputStream (io/input-stream url)]
          (Files/copy in ^Path tmp opts)))
      (.deleteOnExit (.toFile tmp))
      tmp)))

;; ---------------------------------------------------------------------------
;; Native binding (lazy, process-lifetime)
;; ---------------------------------------------------------------------------

(defn- bind!
  "Load the bundled library and bind `rift_ffi_call` / `rift_ffi_free`.
   Returns `{:call MethodHandle :free MethodHandle}`."
  []
  (let [linker ^Linker (Linker/nativeLinker)
        arena  (Arena/ofShared)                ;; library stays mapped for the process
        path   (extract-library!)
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
;; Public API — mirrors the rift-snapshot JS surface
;; ---------------------------------------------------------------------------

(defn init
  "Initialize / register a rift root at `:at` (default: current dir). On Linux
   this converts an ordinary btrfs directory into a subvolume; on macOS it
   registers the source directory for APFS clonefile. Returns nil.

   Options: `:at`, `:database` (override the SQLite registry path)."
  ([] (init {}))
  ([{:keys [at database]}]
   (call* (cond-> {:command "init" :at (s (or at (here)))}
            database (assoc :database (s database))))
   nil))

(defn create
  "Create a copy-on-write workspace from `:from` (default: current dir) and
   return the new workspace path (string). When `:from` is a git repo the new
   workspace has a detached HEAD with index + working-tree state retained.

   Options: `:from`, `:name`, `:into` (parent storage dir), `:database`."
  ([] (create {}))
  ([{:keys [from name into database]}]
   (call* (cond-> {:command "create" :from (s (or from (here)))}
            name     (assoc :name (s name))
            into     (assoc :into (s into))
            database (assoc :database (s database))))))

(defn remove!
  "Remove a created workspace at `:at` (default: current dir). With `:all true`
   removes the whole created subtree and returns a vector of removed paths;
   otherwise returns nil. Removed storage is trashed adjacent to the root until
   `gc` deletes it.

   Options: `:at`, `:all`, `:database`."
  ([] (remove! {}))
  ([{:keys [at all database]}]
   (let [v (call* (cond-> {:command "remove" :at (s (or at (here)))}
                    (some? all) (assoc :all (boolean all))
                    database    (assoc :database (s database))))]
     (when all (vec v)))))

(defn list
  "List direct active child workspaces of `:of` (default: current dir).
   Returns a vector of paths. Options: `:of`, `:database`."
  ([] (list {}))
  ([{:keys [of database]}]
   (vec (call* (cond-> {:command "list" :of (s (or of (here)))}
                 database (assoc :database (s database)))))))

(defn ancestors
  "List ancestor workspaces of `:of` (default: current dir), nearest first.
   Returns a vector of paths. Options: `:of`, `:database`."
  ([] (ancestors {}))
  ([{:keys [of database]}]
   (vec (call* (cond-> {:command "ancestors" :of (s (or of (here)))}
                 database (assoc :database (s database)))))))

(defn gc
  "Physically delete trashed storage and prune missing registry entries.
   Returns a vector of collected paths. Options: `:database`."
  ([] (gc {}))
  ([{:keys [database]}]
   (vec (call* (cond-> {:command "gc"}
                 database (assoc :database (s database)))))))

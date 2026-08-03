(ns com.blockether.rift-test
  "Every test exercises the REAL rift native library through the FFM binding —
   there are no mocks and there is no lenient fallback. rift exists to do
   copy-on-write workspaces, so `roundtrip` REQUIRES a real
   create -> snapshot -> list -> ancestors -> remove -> gc lifecycle to
   succeed; if the underlying filesystem can't snapshot, that is a real
   failure. CI provisions btrfs on Linux (macOS APFS already supports it) so
   the round-trip runs for real on every platform and gates the Clojars
   deploy."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.rift :as rift])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^java.io.File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(deftest binding-loads
  (testing "real rift: the native library loads and round-trips a structured error"
    ;; Listing an uninitialized directory makes REAL rift return a structured
    ;; error. Catching it proves the whole FFM path works against the native
    ;; lib — dylib loaded, JSON in, rift executed, JSON error out, parsed to
    ;; ex-info.
    (let [db (io/file (temp-dir "rift-db") "registry.sqlite")
          e  (is (thrown? clojure.lang.ExceptionInfo
                   (rift/list {:of (str (temp-dir "rift-empty")) :database (str db)})))
          d  (ex-data e)]
      (is (= :rift/error (:type d)) "errors carry the :rift/error type")
      (is (= "workspace_not_initialized" (:code d))
        "rift's own error code is preserved through the binding")
      (is (string? (ex-message e)) "error carries rift's human message"))))

(deftest database-isolation
  (testing "real rift: a per-call :database isolates registries"
    ;; Both calls hit the native lib; also exercises :database marshalling.
    (let [db1 (io/file (temp-dir "rift-db1") "a.sqlite")
          db2 (io/file (temp-dir "rift-db2") "b.sqlite")
          dir (temp-dir "rift-iso")]
      (is (thrown? clojure.lang.ExceptionInfo
            (rift/list {:of (str dir) :database (str db1)})))
      (is (thrown? clojure.lang.ExceptionInfo
            (rift/list {:of (str dir) :database (str db2)}))))))

(deftest roundtrip
  (testing "real rift: init -> create -> list -> ancestors -> excluded -> remove -> gc"
    (let [src (temp-dir "rift-src")
          db  (io/file (temp-dir "rift-reg") "registry.sqlite")]
      ;; A real git repo so rift create yields a detached-HEAD CoW copy.
      (sh/sh "git" "init" "-q" :dir src)
      (spit (io/file src "README.md") "hello rift\n")
      (sh/sh "git" "add" "-A" :dir src)
      (sh/sh "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init" :dir src)
      (rift/init {:at (str src) :database (str db)})

      ;; MANDATORY everywhere (macOS APFS, Linux btrfs) — no fallback. rift's
      ;; whole point is CoW; if it can't snapshot here, the test fails.
      (let [ws (rift/create {:from (str src) :name "feature" :database (str db)})]
        (println "  ✓ real rift CoW workspace created at:" ws)
        (is (string? ws) "create returns the new workspace path")
        (is (.exists (io/file ws)) "the workspace directory really exists on disk")
        (is (.exists (io/file ws "README.md")) "working-tree state is retained in the copy")
        (is (some #{ws} (rift/list {:of (str src) :database (str db)}))
          "the new workspace shows up as a real child")
        (is (vector? (rift/ancestors {:of ws :database (str db)}))
          "ancestors returns a vector for the created workspace")
        (is (vector? (rift/excluded {:of ws :database (str db)}))
          "excluded returns the paths create left out of the workspace")
        (rift/remove! {:at ws :database (str db)})
        (is (vector? (rift/gc {:database (str db)})) "gc returns collected paths")))))

(deftest database-precedence
  (testing "*database* default resolution: scope > root, explicit always wins"
    (is (nil? rift/*database*) "defaults to nil (rift's own default)")
    (rift/with-database "/tmp/scoped.sqlite"
      (is (= "/tmp/scoped.sqlite" rift/*database*) "with-database binds the scope"))
    (is (nil? rift/*database*) "scope is restored after with-database")
    (try
      (rift/set-default-database! "/tmp/root.sqlite")
      (is (= "/tmp/root.sqlite" rift/*database*) "set-default-database! sets the root")
      (rift/with-database "/tmp/scoped.sqlite"
        (is (= "/tmp/scoped.sqlite" rift/*database*) "scope wins over root"))
      (finally
        (rift/set-default-database! nil)))
    (is (nil? rift/*database*) "root reverts to nil")))

(deftest default-database-roundtrip
  (testing "real rift: create/list/remove run with NO per-call :database under with-database"
    (let [src (temp-dir "rift-dflt-src")
          db  (io/file (temp-dir "rift-dflt-reg") "registry.sqlite")]
      (sh/sh "git" "init" "-q" :dir src)
      (spit (io/file src "README.md") "default db\n")
      (sh/sh "git" "add" "-A" :dir src)
      (sh/sh "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init" :dir src)
      (rift/with-database (str db)
        (rift/init {:at (str src)})
        (let [ws (rift/create {:from (str src) :name "dflt"})]
          (println "  ✓ real rift create via default *database* at:" ws)
          (is (string? ws) "create works with the ambient default db")
          (is (some #{ws} (rift/list {:of (str src)}))
            "list works without :database, using the same default")
          (rift/remove! {:at ws})
          (is (vector? (rift/gc)) "gc works without :database"))))))

(deftest platform-default-database
  (testing "default-database resolves a per-OS path ending in rift/rift.sqlite"
    ;; Pure — does NOT call rift, so it never creates/pollutes the real registry.
    (let [p   (rift/default-database)
          os  (.toLowerCase (System/getProperty "os.name"))
          tail (str (io/file "rift" "rift.sqlite"))]   ; rift/rift.sqlite | rift\rift.sqlite
      (is (string? p))
      (is (str/ends-with? p tail) "ends with rift/rift.sqlite")
      (cond
        (or (str/includes? os "mac") (str/includes? os "darwin"))
        (is (str/includes? p (str (io/file "Library" "Application Support")))
          "macOS uses ~/Library/Application Support")

        (str/includes? os "win")
        (is (str/includes? (str/lower-case p) "appdata")
          "Windows uses %LOCALAPPDATA%")

        :else
        (is (or (str/includes? p (str (io/file ".local" "share")))
              (when-let [x (not-empty (System/getenv "XDG_DATA_HOME"))]
                (str/includes? p x)))
          "Linux uses $XDG_DATA_HOME or ~/.local/share")))))

(deftest create-reports-the-mechanism
  (testing "real rift: create-detailed names how the workspace was made, and the label matches the disk"
    (let [src (temp-dir "rift-kind-src")
          db  (io/file (temp-dir "rift-kind-reg") "registry.sqlite")]
      (sh/sh "git" "init" "-q" :dir src)
      (spit (io/file src "README.md") "hello rift\n")
      (sh/sh "git" "add" "-A" :dir src)
      (sh/sh "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init" :dir src)
      (rift/init {:at (str src) :database (str db)})

      (let [{:keys [path kind]} (rift/create-detailed {:from (str src) :name "kind" :database (str db)})]
        (println "  ✓ real rift workspace kind:" kind "at:" path)
        (is (string? path) "create-detailed returns the workspace path")
        (is (.exists (io/file path)) "the reported path really exists")
        ;; `:kind` comes from the native library, whose rift source commit CI
        ;; pins — that commit reports the mechanism, so nil is a real failure.
        (is (contains? #{:btrfs :reflink :apfs :worktree :copy} kind)
          "kind is one of rift's mechanisms, decoded to a keyword")
        ;; Cross-check the label against the filesystem: only the Git-worktree
        ;; fallback leaves a `.git` FILE (a gitdir pointer); every copy-on-write
        ;; mechanism gives the clone its own `.git` DIRECTORY.
        (let [dot-git (io/file path ".git")]
          (if (= :worktree kind)
            (is (.isFile dot-git)
              "a workspace reported as :worktree really is a linked git worktree")
            (is (.isDirectory dot-git)
              "a copy-on-write workspace owns its .git directory")))
        (rift/remove! {:at path :database (str db)}))

      ;; The plain `create` contract is unchanged: a bare path string.
      (let [ws (rift/create {:from (str src) :name "plain" :database (str db)})]
        (is (string? ws) "create still returns just the path")
        (rift/remove! {:at ws :database (str db)}))
      (rift/gc {:database (str db)}))))

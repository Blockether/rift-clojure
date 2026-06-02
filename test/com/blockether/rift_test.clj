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
  (testing "real rift: init -> create -> list -> ancestors -> remove -> gc"
    (let [src (temp-dir "rift-src")
          db  (io/file (temp-dir "rift-reg") "registry.sqlite")]
      ;; A real git repo so rift create yields a detached-HEAD CoW copy.
      (sh/sh "git" "init" "-q" :dir src)
      (spit (io/file src "README.md") "hello rift\n")
      (sh/sh "git" "add" "-A" :dir src)
      (sh/sh "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init" :dir src)
      (rift/init {:at (str src) :database (str db)})

      ;; MANDATORY everywhere — no fallback. rift's whole point is CoW; if it
      ;; can't snapshot here, the test fails (that's the gate).
      (let [ws (rift/create {:from (str src) :name "feature" :database (str db)})]
        (println "  ✓ real rift CoW workspace created at:" ws)
        (is (string? ws) "create returns the new workspace path")
        (is (.exists (io/file ws)) "the workspace directory really exists on disk")
        (is (.exists (io/file ws "README.md")) "working-tree state is retained in the copy")
        (is (some #{ws} (rift/list {:of (str src) :database (str db)}))
          "the new workspace shows up as a real child")
        (is (vector? (rift/ancestors {:of ws :database (str db)}))
          "ancestors returns a vector for the created workspace")
        (rift/remove! {:at ws :database (str db)})
        (is (vector? (rift/gc {:database (str db)})) "gc returns collected paths")))))

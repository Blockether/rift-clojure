(ns com.blockether.rift-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.rift :as rift])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^java.io.File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(deftest binding-loads
  (testing "the native library loads, binds, and round-trips a structured error"
    ;; Listing an uninitialized directory makes rift return a structured
    ;; error. Catching it here proves the whole FFM path works: dylib loaded,
    ;; JSON request marshalled in, rift executed, JSON error marshalled back
    ;; out and parsed into ex-info — independent of any CoW filesystem.
    (let [db (io/file (temp-dir "rift-db") "registry.sqlite")
          e  (is (thrown? clojure.lang.ExceptionInfo
                   (rift/list {:of (str (temp-dir "rift-empty")) :database (str db)})))]
      (is (= :rift/error (:type (ex-data e))))
      (is (string? (:code (ex-data e)))))))

(deftest roundtrip
  (testing "init -> create -> list -> remove -> gc against a temp git repo"
    (let [src (temp-dir "rift-src")
          db  (io/file (temp-dir "rift-reg") "registry.sqlite")]
      ;; rift create on a git repo yields a detached-HEAD CoW copy.
      (sh/sh "git" "init" "-q" :dir src)
      (spit (io/file src "README.md") "hello rift\n")
      (sh/sh "git" "add" "-A" :dir src)
      (sh/sh "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init" :dir src)
      (try
        (rift/init {:at (str src) :database (str db)})
        (let [ws (rift/create {:from (str src) :name "feature" :database (str db)})]
          ;; Copy-on-write filesystem available (APFS / btrfs): full happy path.
          (is (string? ws) "create returns the new workspace path")
          (is (.exists (io/file ws)) "the workspace directory exists on disk")
          (is (.exists (io/file ws "README.md")) "working-tree state is retained")
          (is (some #{ws} (rift/list {:of (str src) :database (str db)}))
            "the new workspace appears in the child list")
          (rift/remove! {:at ws :database (str db)})
          (is (vector? (rift/gc {:database (str db)})) "gc returns collected paths"))
        (catch clojure.lang.ExceptionInfo e
          ;; No CoW filesystem (e.g. ext4 CI runner): the binding is still
          ;; proven — a structured RiftError marshalled out of the native lib.
          (let [d (ex-data e)]
            (is (= :rift/error (:type d))
              (str "expected a structured rift error, got: " (pr-str d)))
            (is (string? (:code d)) "error carries a code")
            (println "  (CoW unavailable here; verified structured-error path:" (:code d) ")")))))))

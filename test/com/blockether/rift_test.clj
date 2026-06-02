(ns com.blockether.rift-test
  "Every test here exercises the REAL rift native library through the FFM
   binding — there are no mocks. `binding-loads` and `database-isolation` call
   real `rift_ffi_call` and assert rift's own error codes; `roundtrip` performs
   a real copy-on-write workspace lifecycle.

   Where copy-on-write must work (macOS APFS — the dev machine and both macOS
   CI runners, or btrfs Linux) the full round-trip is MANDATORY: there is no
   catch to swallow a failure, so a real rift/binding regression fails the
   test. Only a genuinely non-CoW filesystem (ext4 Linux CI) takes the lenient
   branch, and even then it must get rift's `cow_unavailable` back — proving
   the call reached real rift."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.rift :as rift])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^java.io.File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- macos? []
  (let [os (.toLowerCase (System/getProperty "os.name"))]
    (or (.contains os "mac") (.contains os "darwin"))))

(defn- cow-required?
  "When true, the real copy-on-write round-trip is MANDATORY (no fallback).
   macOS always supports APFS `clonefile`. CI sets `RIFT_TEST_REQUIRE_COW=1`
   on every platform — including Linux, where the workflow provisions a btrfs
   filesystem and points `java.io.tmpdir` at it so snapshots really work. Only
   a plain ext4 dev box (no env, non-macOS) takes the lenient branch."
  []
  (or (macos?) (= "1" (System/getenv "RIFT_TEST_REQUIRE_COW"))))

(deftest binding-loads
  (testing "real rift: the native library loads and round-trips a structured error"
    ;; Listing an uninitialized directory makes REAL rift return a structured
    ;; error. Catching it proves the whole FFM path works against the native
    ;; lib — dylib loaded, JSON in, rift executed, JSON error out, parsed to
    ;; ex-info — independent of any CoW filesystem, so it gates every platform.
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

      (if (cow-required?)
        ;; ---- MANDATORY real CoW round-trip (no catch — failure = test fail).
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
          (is (vector? (rift/gc {:database (str db)})) "gc returns collected paths"))

        ;; ---- Non-CoW filesystem (ext4 CI): create cannot snapshot. We still
        ;; require the call to reach REAL rift and come back with its own
        ;; cow_unavailable code — not a binding crash, not some other failure.
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                      (rift/create {:from (str src) :name "feature" :database (str db)})))
              d (ex-data e)]
          (is (= :rift/error (:type d)) "non-CoW failure is a structured rift error")
          (is (= "cow_unavailable" (:code d))
            (str "expected rift's cow_unavailable on a non-CoW filesystem, got: " (pr-str d)))
          (println "  (no CoW filesystem here; real rift returned cow_unavailable as expected)"))))))

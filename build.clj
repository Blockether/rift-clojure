(ns build
  "Build/deploy for rift-clojure: ONE jar at `com.blockether/rift` that bundles
   the native `librift_ffi` libraries under resources/prebuilds/.

   The native libraries are NOT committed — CI builds them on every release tag
   (one native runner per platform) and drops them into resources/prebuilds/
   before this jar is assembled, so the published Clojars jar carries all four
   platform binaries. For local development run `scripts/build-natives.sh`
   first to populate resources/prebuilds/ for your machine.

   Mirrors the svar build/deploy flow.

     clojure -T:build jar       # build the jar
     clojure -T:build install   # build + install into ~/.m2
     clojure -T:build deploy    # build + deploy to Clojars
     clojure -T:build clean     # delete target/"
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/rift)

(def version
  "VERSION env (set by CI from the release tag) wins; otherwise the
   resources/VERSION file tagged `-SNAPSHOT` for local builds."
  (let [v (System/getenv "VERSION")]
    (cond
      (and v (str/starts-with? v "v")) (subs v 1)
      v                                v
      :else (str (str/trim (slurp "resources/VERSION")) "-SNAPSHOT"))))

(def class-dir "target/classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom
    {:class-dir class-dir
     :lib       lib
     :version   version
     :basis     @basis
     :src-dirs  ["src"]
     :pom-data  [[:description "Clojure binding to rift — copy-on-write workspaces — via the JDK Foreign Function & Memory API."]
                 [:url "https://github.com/Blockether/rift-clojure"]
                 [:licenses
                  [:license
                   [:name "MIT License"]
                   [:url "https://opensource.org/licenses/MIT"]]]
                 [:scm
                  [:url "https://github.com/Blockether/rift-clojure"]
                  [:connection "scm:git:https://github.com/Blockether/rift-clojure.git"]
                  [:developerConnection "scm:git:ssh://git@github.com/Blockether/rift-clojure.git"]]]})
  ;; src + resources (the CI-built native libs) both land in the jar.
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))

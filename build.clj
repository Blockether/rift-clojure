(ns build
  "Build/deploy for rift-clojure. The main `com.blockether/rift` jar is small;
   native libraries are published as per-platform artifacts such as
   `com.blockether/rift-native-linux-x64`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/rift)
(def native-platforms #{"linux-x64" "linux-arm64" "darwin-arm64" "darwin-x64"})
(def native-libs {"linux-x64" "librift_ffi.so"
                  "linux-arm64" "librift_ffi.so"
                  "darwin-arm64" "librift_ffi.dylib"
                  "darwin-x64" "librift_ffi.dylib"})

(def version
  "VERSION env (set by CI from the release tag) wins; otherwise the
   resources/VERSION file tagged `-SNAPSHOT` for local builds."
  (let [v (System/getenv "VERSION")]
    (cond
      (and v (str/starts-with? v "v")) (subs v 1)
      v v
      :else (str (str/trim (slurp "resources/VERSION")) "-SNAPSHOT"))))

(def class-dir "target/classes")
(def native-class-dir "target/native-classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- pom-data [description]
  [[:description description]
   [:url "https://github.com/Blockether/rift-clojure"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:scm
    [:url "https://github.com/Blockether/rift-clojure"]
    [:connection "scm:git:https://github.com/Blockether/rift-clojure.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Blockether/rift-clojure.git"]]])

(defn jar [_]
  (clean nil)
  (b/write-pom
   {:class-dir class-dir
    :lib lib
    :version version
    :basis @basis
    :src-dirs ["src"]
    :pom-data (pom-data "Clojure binding to rift — copy-on-write workspaces — via the JDK Foreign Function & Memory API.")})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  ;; Ship the GraalVM native-image config (FFM downcalls + prebuilds glob) so a
  ;; downstream native-image build (e.g. vis) picks it up automatically.
  (b/copy-dir {:src-dirs ["resources/META-INF"] :target-dir (str class-dir "/META-INF")})
  ;; Ship the version under a NAMESPACED resource path (rift/VERSION), not the
  ;; jar root, so it can't collide with other libs' `VERSION` on a shared
  ;; classpath (which made rift resolve a foreign version and 404 the native).
  (let [vfile (io/file class-dir "rift" "VERSION")]
    (io/make-parents vfile)
    (spit vfile version))
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn- native-lib [platform]
  (symbol "com.blockether" (str "rift-native-" platform)))

(defn native-jar [{:keys [platform]}]
  (let [platform (some-> platform name)]
    (when-not (native-platforms platform)
      (throw (ex-info (str "Unknown native platform: " platform) {:platform platform :known native-platforms})))
    (let [fname (native-libs platform)
          src (format "resources/prebuilds/%s/%s" platform fname)
          lib* (native-lib platform)
          jar* (format "target/%s.jar" (name lib*))]
      (b/delete {:path native-class-dir})
      (b/delete {:path jar*})
      (when-not (.exists (io/file src))
        (throw (ex-info (str "Native library not found: " src) {:platform platform :path src})))
      (b/write-pom {:class-dir native-class-dir
                    :lib lib*
                    :version version
                    :basis @basis
                    :src-dirs []
                    :pom-data (pom-data (format "Native rift-ffi library for %s." platform))})
      (b/copy-file {:src src :target (format "%s/prebuilds/%s/%s" native-class-dir platform fname)})
      (b/jar {:class-dir native-class-dir :jar-file jar*})
      (println "Built:" jar* "version:" version)
      jar*)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(defn deploy-native [{:keys [platform]}]
  (let [platform (some-> platform name)
        jar* (native-jar {:platform platform})
        lib* (native-lib platform)]
    (dd/deploy {:installer :remote
                :artifact jar*
                :pom-file (b/pom-path {:lib lib* :class-dir native-class-dir})})))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

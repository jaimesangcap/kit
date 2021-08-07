(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as jio]
            [clojure.edn :as edn]
            [weavejester.dependency :as dep]
            [deps-deploy.deps-deploy :as deploy]))

(def libs-dir "libs")
(def version (format "0.1.0"))
(def group-id "wake-clj")
(def src ["src"])
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean
  "Delete the build target directory"
  [{:keys [target-dir]}]
  (println (str "Cleaning " target-dir))
  (b/delete {:path target-dir}))

(defn make-jar
  "Create the jar from a source pom and source files"
  [{:keys [class-dir lib version  basis src jar-file] :as m}]
  (clojure.pprint/pprint (dissoc m :basis))
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :src-pom "pom.xml"
                :basis basis
                :src-dirs src})
  (b/copy-dir {:src-dirs src
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install
  "Install jar to local repo"
  [{:keys [basis lib version jar-file class-dir]}]
  (println "Installing... " jar-file)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn- dep-hm [{:keys [libs]}]
  (let [deps (map #(filter (fn [d] (= group-id (namespace d))) %) (map #(keys (:deps (edn/read-string (slurp (str % "/deps.edn"))))) libs))
        proj (into #{} (map #(symbol group-id (.getName %))) libs)]
    (zipmap proj deps)))

(defn- build-graph [{:keys [libs] :as m}]
  (let [dep-mappings (dep-hm m)]
    (loop [g (dep/graph) [[k vs :as entry] & m] dep-mappings]
      (if entry
        (recur (reduce #(dep/depend %1 k %2) g vs) m)
        {:graph g :dep-mappings dep-mappings}))))

(defn- topo-sort [{:keys [graph dep-mappings]}]
  (let [sorted (dep/topo-sort graph)]
    (concat sorted (reduce disj (set (keys dep-mappings)) sorted))))

(defn- build-data [lib]
  (let [l (str libs-dir "/" (name lib))
        src-dir [(str l "/src")]
        src-pom (str l "/pom.xml")
        target-dir (str l "/target")
        class-dir (str target-dir "/classes")
        basis (b/create-basis {:project (str l "/deps.edn")})
        jar-file (format "%s/%s-%s.jar" target-dir (name lib) version)]
    {:target-dir target-dir :class-dir class-dir :lib lib :version version :basis basis :src src-dir
     :src-pom src-pom :jar-file jar-file}))

(defn install-lib [{:keys [artifact-id]}]
  (let [libs (filter #(.isDirectory %) (.listFiles (jio/file libs-dir)))
        {:keys [graph dep-mappings]} (build-graph {:libs libs})]
    (let [lib (symbol group-id (name artifact-id))]
      (if (contains? dep-mappings lib)
        (if (not-empty (dep/transitive-dependencies graph lib))
          (doseq [lib (concat (dep/transitive-dependencies graph lib) [lib])]
            (let [bd (build-data lib)]
              (clean bd)
              (make-jar bd)
              (install bd)))
          (let [bd (build-data lib)]
            (clean bd)
            (make-jar bd)
            (install bd)))
        (println "Can't find: " artifact-id)))))

(defn install-libs [_]
  (let [libs (filter #(.isDirectory %) (.listFiles (jio/file libs-dir)))]
    (doseq [lib (topo-sort (build-graph {:libs libs}))]
      (let [bd (build-data lib)]
        (clean bd)
        (make-jar bd)
        (install bd)))))
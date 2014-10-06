(ns leiningen.grid.bundle
  (:require [leiningen.compile :as compile]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [leiningen.deps])
  (:use [leiningen.core.classpath]
        [leiningen.core.project]
        [leinjacker.eval :only (eval-in-project)])
  (:import [java.util List]
           [java.io File
                    BufferedOutputStream
                    FileOutputStream
                    ByteArrayInputStream]
           [java.nio.file Files 
                          StandardCopyOption]
           [java.util.jar Manifest 
                          JarFile 
                          JarEntry
                          JarOutputStream]))

(def osgi-manifest-map
  {:bundle-activator "Bundle-Activator"
   :import-package "Import-Package"
   :export-package "Export-Package"
   :bundle-symbolicname "Bundle-Symbolicname"})

(def default-grid-manifest
  {"Created-By" "Leiningen Grid Plugin"
   "Bundle-Activator" "grid.activator"
   "Built-By" (System/getProperty "user.name")
   "Build-Jdk" (System/getProperty "java.version")
   })

(defn to-manfiest-str
  [value]
  (if (string? value)
          value
          (name (symbol value))))

(defn to-manifest-value
  [value]
  (if (instance? List value)
      (string/join "," (map to-manfiest-str value))
      (to-manfiest-str value)))

(defn to-byte-stream [^String s]
    (ByteArrayInputStream. (.getBytes s)))

(defn qsym
  [activator-ns sym]
  (symbol (str activator-ns "/" sym)))

(defn make-manifest [user-manifest]
    (let [mf-data (merge default-grid-manifest user-manifest)
          mf-str (reduce (fn [prefix [k v]]
                                          (str prefix  k ": " v "\n"))
                                       "Manifest-Version: 1.0\n"
                                       mf-data)]
        (Manifest. (to-byte-stream mf-str))))

(defn skip-file? [project bundle-path file]
    (or (.endsWith (.toLowerCase (.getName file)) "-local.clj")
        (re-find #"^\.?#" (.getName file))
        (re-find #"~$" (.getName file))
        (.startsWith bundle-path "META-INF/resources/META-INF/resources/")
        (some #(re-find % bundle-path)
           (get-in project [:grid :osgi :bundle-exclusions] [#"(^|/)\."]))))

(defn write-entry [bundle bundle-path entry]
    (.putNextEntry bundle (JarEntry. bundle-path))
    (io/copy entry bundle))

(defn str-entry [bundle bundle-path content]
    (write-entry bundle bundle-path (to-byte-stream content)))

(defn bundle-relative-path [bundle-path root file]
    (str bundle-path
         (-> (.toURI (io/file root))
             (.relativize (.toURI file))
             (.getPath))))

(defn file-entry [project bundle bundle-path file]
    (when (and (.exists file)
               (.isFile file)
               (not (skip-file? project bundle-path file)))
    (write-entry bundle bundle-path file)))

(defn dir-entry [project bundle bundle-root dir-path]
    (doseq [file (file-seq (io/file dir-path))]
        (let [bundle-path (bundle-relative-path bundle-root dir-path file)]
            (file-entry project bundle bundle-path file))))

(defn source-file [project namespace]
   (io/file (:compile-path project)
   (-> (str namespace)
       (string/replace "-" "_")
       (string/replace "." java.io.File/separator)
       (str ".clj"))))

(defn user-osgi-manifest
  [project]
  (let [osgi-cfg (get-in project [:grid :osgi])]
    (loop [mm osgi-manifest-map m {}]
      (if (empty? mm)
          m
          (recur (rest mm)
                 (let [raw-value (get osgi-cfg (first (first mm)))]
                      (if raw-value 
                          (assoc m
                                 (second (first mm))
                                 (to-manifest-value raw-value)
                          m))))))))



(defn compile-form
  "Compile the supplied form into the target directory."
  [project namespace form]
  (leiningen.deps/deps project)
  (let [out-file (source-file project namespace)]
       (.mkdirs (.getParentFile out-file))
       (with-open [out (io/writer out-file)]
         (binding [*out* out] (prn form))))
         (eval-in-project project
            `(do (clojure.core/compile '~namespace) nil)
            nil))

(defn default-activator-class 
  [project]
  (let [ac-spec (get-in project [:grid :osgi :activator-class])
        ns-parts (-> (:name project)
                     (string/replace "-" "_")
                     (string/split #"\.")
                     (butlast)
                     (vec)
                     (conj "activator"))]
       (or ac-spec
           (if (> (count ns-parts) 1)
               (string/join "." ns-parts)
               (string/join "." (into ["grid"] (vec ns-parts)))))))

(defn activator-class 
  [project]
  (or (get-in project [:grid :osgi :activator-class])
      (default-activator-class project)))

(defn activator-ns 
  [project]
  (-> (activator-class project)
      (string/replace "_" "-")))

(defn default-bundle-name 
  [project]
  (or (get-in project [:grid :osgi :bundle-name])
      (str (:name project) "-" (:version project) "-bundle")))

(defn bundle-version
  [project]
    (first (string/split (:version project) #"-")))

(defn gen-osgi-manifest
  [project]
  (merge {"Bundle-ManifestVersion" 2
          "Bundle-Name" (default-bundle-name project)
          "Bundle-Version" (bundle-version project)
          "Bundle-Symbolicname" (:name project)}
         (user-osgi-manifest project)))

(defn bundle-file-path 
  [project]
  (let [target-dir (or (:target-dir project) 
                       (:target-path project))]
       (.mkdirs (io/file target-dir))
       (str target-dir "/" (default-bundle-name project) ".jar")))

(defn create-bundle 
  [project file-path]
    (-> (FileOutputStream. file-path)
        (BufferedOutputStream.)
        (JarOutputStream. (make-manifest (merge (gen-osgi-manifest project)
                                                (:manifest project))))))


(defn generate-activator 
  [project activator-ns]
  `(do (def ~(qsym activator-ns "grid-mods") (atom ~(or (mapv #(conj (list %) 'quote) (get-in project [:grid :modules]))
                                                        [])))
     (defn ~(qsym activator-ns "-start")
       [context#]
         (loop [m# (deref ~(qsym activator-ns "grid-mods"))]
           (if (empty? m#)
             nil
             (recur (do (.start (com.vnetpublishing.clj.grid.lib.grid.osgi.activator/get-grid-module context# 
                                         (first m#))
                                context#)
                        (rest m#))))))
     (defn ~(qsym activator-ns "-stop")
       [context#]
         (loop [m# ~(qsym activator-ns "grid-mods")]
           (if (empty? m#)
             nil
             (recur (do (.stop (com.vnetpublishing.clj.grid.lib.grid.osgi.activator/get-grid-module context#
                                        (first m#))
                               context#)
                        (rest m#)
                      )))))))

(defn compile-activator 
      [project]
      (let [activator-ns (symbol (activator-ns project))]
      (compile-form project activator-ns
        `(do (ns ~activator-ns
                 (:require com.vnetpublishing.clj.grid.lib.grid.osgi.activator)
                 (:gen-class :implements [org.osgi.framework.BundleActivator]))
             ~(generate-activator project activator-ns)))))

(defn add-bundle-dep
  "Add bundle dependencies"
  [project]
  project)

(defn bundle-source-paths
  "Return a distinct source paths."
  [project]
  (let [source-paths (if (not (:omit-source project))
                         (concat [(:source-path project)] (:source-paths project)))]
       (or (distinct source-paths)
           '())))

(defn bundle-resources-paths [project]
  [project]
    (let [source-paths (if (not (:omit-source project))
                           (concat [(:resources-path project)] (:resource-paths project)))
          meta-resource-paths (mapv #(str %1 "/META-INF/resources") (filter identity (distinct source-paths)))]
         (filter identity
                 (or (distinct (into source-paths meta-resource-paths))
                     '()))))

(defn write-bundle
  [project bundle-path]
  (with-open [bundle-stream (create-bundle project bundle-path)]
             (dir-entry project bundle-stream "" (:compile-path project))
             (doseq [path (bundle-source-paths project)
                     :when path]
                    (dir-entry project bundle-stream "" path))
             (doseq [path (bundle-resources-paths project)
                     :when path]
                    (dir-entry project bundle-stream  "META-INF/resources/" path))
                    bundle-stream))

(defn bundle
  "Create a $PROJECT-$VERSION.jar bundle file."
  ([project]
    (let [project (-> project
                      (unmerge-profiles [:default])
                      add-bundle-dep)
          result (compile/compile project)]
         (when-not (and (number? result) 
                        (pos? result))
           (let [bundle-path (bundle-file-path project)]
             (compile-activator project)
             (write-bundle project bundle-path) 
             (println "Done."))))))


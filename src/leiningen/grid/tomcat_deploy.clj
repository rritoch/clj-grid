(ns leiningen.grid.tomcat-deploy
  (:require [leiningen.compile :as compile]
            [clojure.java.io :as io])
  (:use leiningen.core.classpath)
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.util.jar JarFile JarEntry]))

; BRICK! tomcat-api-8.0.8.jar
(def default-blacklist-files ["org/apache/tomcat/InstanceManager.class"
                              "javax/servlet/Servlet.class"
                              "javax/el/Expression.class"])

(defn make-local-path
      [& args]
      (if args
          (clojure.string/join File/separator args)))

(defn get-grid-config-path
  [project]
    (if (contains? (:grid project) :config-path)
        (get-in project [:grid :config-path])
        "grid-config"))

(defn get-tomcat-deploy-path
  [project]
    (let [cfg-file (io/file (make-local-path (get-grid-config-path project)
                                              "tomcat_deploy.clj"))]
      (if (.exists cfg-file)
          (:deploy-path (read-string (slurp cfg-file))))))

(defn to-canonical-path
  [path]
    (.getCanonicalPath (io/file path)))

(defn set-tomcat-deploy-path 
  [project path]
    (let [cfg-path (io/file (get-grid-config-path project))
          cfg-file (make-local-path (get-grid-config-path project)
                                              "tomcat_deploy.clj")]
      (.mkdirs cfg-path)
      (spit cfg-file (str (assoc {} 
                                 :deploy-path 
                                 (to-canonical-path path))))))

(defn install-projectref
  [project]
     (let [project-file (.getCanonicalPath (io/file "project.clj"))
           projectref-filename (make-local-path (get-tomcat-deploy-path project)
                                                 "WEB-INF"
                                                 "projectref.clj")]
          (spit projectref-filename (str (assoc {} 
                                                :project-file 
                                                project-file
                                                :project-resource-paths
                                                (filter identity (concat [(:source-path project)] 
                                                                         (:source-paths project)
                                                                         [(:resources-path project)] 
                                                                         (:resource-paths project)
                                                                         [(:compile-path project)])))))))

(defn make-dest-dirs
   [project]
   (let [base-dir (get-tomcat-deploy-path project)
         cache-dir (make-local-path base-dir "WEB-INF" "cache")
         cache-path (io/file cache-dir)
         dirs (map io/file [(make-local-path base-dir "WEB-INF" "lib")
                            cache-dir])]
        (doall (map #(.mkdirs %) dirs))
        (.setExecutable cache-path true false)
        (.setReadable cache-path true false)
        (.setWritable cache-path true false)))

(defn update-copy
      [src dest]
      (let [p (.getParentFile dest)]
           (cond (and (.isFile src)
                      (or (not (.exists dest))
                          (> (.lastModified src) 
                             (.lastModified dest))))
                 (do (when-not (.exists p)
                       (.mkdirs p))
                     (println (str "Updating ... "
                                   (.toString dest)))
                     (io/copy src dest))
                 (and (.isDirectory src)
                      (not (.exists dest)))
                 #_(.mkdirs dest) nil)))
      
(defn get-deps
  "Get project dependencies"
  [project]
    (filter #(.isFile %) (map io/file (get-classpath project))))

(defn target-file 
  [dest src file]
  (-> (str dest
           File/separator
           (-> (.toURI src)
               (.relativize (.toURI file))
               (.getPath)))
       (io/file)))

(defn in? 
      "Test if sequence contains value"
      [seq value]  
      (some #(= value %) seq))

(defn blacklist-jar? [file entries]
  (with-open [jar-file (JarFile. file)]
    (some (partial in? entries)
          (map #(.getName ^JarEntry %)
               (enumeration-seq (.entries jar-file))))))

(defn get-blacklist-files
  [project]
  default-blacklist-files)

(defn deploy-dep?
  [project dep]
  (not (blacklist-jar? dep (get-blacklist-files project))))

(defn deploy-deps
  [project]
  (let [base-dir (get-tomcat-deploy-path project)
        dd (filter (partial deploy-dep? project) (get-deps project))]
       (println "Deploying dependencies...")
       (doall (for [i dd
                      :let [t (io/file (make-local-path base-dir "WEB-INF" "lib" (.getName i)))]
                      :when (update-copy i t)]
                    t))))

(defn deploy-webxml
      [project]
      (let [s (io/file "web.xml")
            dpath (io/file (make-local-path (get-tomcat-deploy-path project)
                                             "WEB-INF"))]
           (println "Deploying web.xml")
           (if (.exists s)
               (io/copy s (target-file dpath (io/file ".") s))
               (println "WARNING: web.xml not found"))))

(defn tomcat-deploy
  "Push updates to web-root"
  ([project]
     (if (get-tomcat-deploy-path project)
         (let [result (compile/compile project)]
              (if-not (and (number? result)
                           (pos? result))
                (do (deploy-deps project)
                    (deploy-webxml project))))
         (do (println "WARNING: Deployment path not defined.")
             (println "Use: lein grid tomcat-deploy path"))))
  ([project path]
     (let [f (io/file path)]
        (if (not (.isFile f))
            (do (set-tomcat-deploy-path project path)
                (make-dest-dirs project)
                (install-projectref project)
                (tomcat-deploy project))
            (println (str "Path " path " resolve to a file but must be a directory."))))))


(ns leiningen.grid.push
  (:require [leiningen.compile :as compile]
            [clojure.java.io :as io])
  (:use leiningen.core.classpath)
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.util.jar JarFile JarEntry]))

(def default-channel "devel")

(def default-deploy-channels [["devel" {:app-root "public_html"}]])

; BRICK! tomcat-api-8.0.8.jar
(def default-blacklist-files ["org/apache/tomcat/InstanceManager.class"
                              "javax/servlet/Servlet.class"
                              "javax/el/Expression.class"])

(defn make-local-path
      [& args]
      (if args
          (clojure.string/join File/separator args)))

(defn get-deploy-channels
      [project]
      default-deploy-channels)

(defn get-app-root
  [project channel]
  (let [channels (filter (partial #(= %1 (first %2)) channel) (get-deploy-channels project))]
    (or (:app-root (first channels))
        "public_html")))

(defn make-dest-dirs
   [project channel]
   (let [base-dir (get-app-root project channel)
         dirs (map io/file [(make-local-path base-dir "WEB-INF" "classes")
                            (make-local-path base-dir "WEB-INF" "lib")
                            #_(make-local-path base-dir "META-INF" "resources")])]
        (doall (map #(.mkdirs %) dirs))))
     
(defn get-default-channel
  "Get default channel"
  [project]
  (if (contains? (:grid project) :default-channel)
      (:default-channel (:grid project))
      default-channel))

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

(defn deploy-compile
      [project channel]
      (println "Deploying compile...")
      (let [result (compile/compile project)
            cpath (io/file (:compile-path project))
            tcpath (io/file (make-local-path (get-app-root project channel) 
                                             "WEB-INF" 
                                             "classes"))
            trpath (io/file (make-local-path (get-app-root project channel) 
                                             ;"META-INF" 
                                             ;"resources"
                     
                    ))
            jsppath (io/file (make-local-path (get-app-root project channel) 
                                             "WEB-INF" 
                                             "resources"))
            resource-paths (distinct (concat [(:resources-path project)] 
                              (:resource-paths project)))]
           (when-not (and (number? result)
                          (pos? result))
                     (do (doseq [f (file-seq cpath)]
                           (update-copy f (target-file tcpath cpath f)))
                         (doseq [srcdir (map io/file 
                                             (filter #(if % true) 
                                                     (distinct (concat [(:source-path project)] 
                                                                       (:source-paths project)))))]
                                (doseq [f (file-seq srcdir)]
                                  (cond (.endsWith (.getName f) ".clj")
                                        (update-copy f (target-file tcpath srcdir f))
                                        (.endsWith (.getName f) ".jsp")
                                        (update-copy f (target-file jsppath srcdir f))
                                        :else
                                       (update-copy f (target-file trpath srcdir f)))))
                         (doseq [resourcedir (map io/file 
                                             (filter #(if % true) 
                                                     resource-paths))]
                                (doseq [f (file-seq resourcedir)]
                                  (update-copy f (target-file trpath resourcedir f))))))))

(defn deploy-webxml
      [project channel]
      (let [s (io/file "web.xml")
            dpath (io/file (make-local-path (get-app-root project channel) 
                                             "WEB-INF"))]
           (println "Deploying web.xml")
           (if (.exists s)
               (io/copy s (target-file dpath (io/file ".") s))
               (println "WARNING: web.xml not found"))))

(defn deploy-deps
  [project channel]
  (let [base-dir (get-app-root project channel)
        dd (filter (partial deploy-dep? project) (get-deps project))]
       (println "Deploying dependencies...")
       (doall (for [i dd
                      :let [t (io/file (make-local-path base-dir "WEB-INF" "lib" (.getName i)))]
                      :when (update-copy i t)]
                    t))))

(defn push
  "Push updates to web-root"
  ([project] 
     (push project (get-default-channel project)))
  ([project channel]
     (make-dest-dirs project channel)
     (deploy-deps project channel)
     (deploy-compile project channel)
     (deploy-webxml project channel)
     (println "Done.")))


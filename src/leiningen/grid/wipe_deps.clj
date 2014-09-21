(ns leiningen.grid.wipe-deps
  (:require [leiningen.compile :as compile]
            [clojure.java.io :as io])
  (:use leiningen.core.classpath)
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.util.jar JarFile JarEntry]))

(def default-channel "devel")

(def default-deploy-channels [["devel" {:app-root "public_html"}]])

; BRICK! tomcat-api-8.0.8.jar
(def default-blacklist-files ["org/apache/tomcat/InstanceManager.class"])

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

(defn wipe
      [src dest]
      (if (and (.exists dest)
               (.isFile dest))
          (do 
              (println (str "Removing ... "
                            (.toString dest)))
              (.delete dest))))
      
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

(defn wipe-deps
  [project channel]
  (let [base-dir (get-app-root project channel)
        dd (filter (partial deploy-dep? project) (get-deps project))]
       (println "Wiping dependencies...")
       (doall (for [i dd
                      :let [t (io/file (make-local-path base-dir "WEB-INF" "lib" (.getName i)))]
                      :when (wipe i t)]
                    t))))

(defn wipe-deps
  "Push updates to web-root"
  ([project] 
     (push project (get-default-channel project)))
  ([project channel]
     (wipe-deps project channel)
     (println "Done.")))


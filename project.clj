(defproject grid "0.1.0-SNAPSHOT"
  :description "Grid Leiningen/Tomcat Integration"
  :url "http://example.com/FIXME"
  :license {:name "VNet PL"
            :url "http://www.vnetpublishing.com"}
  
  :dependencies [[leinjacker "0.4.1"]]
  :repositories [["releases" {:url "http://home.vnetpublishing.com/artifactory/libs-release-local"
                              :creds :gpg}]
                 ["snapshots" {:url "http://home.vnetpublishing.com/artifactory/libs-snapshot-local"
                               :creds :gpg}]]  
  ;:aot :all
  :eval-in-leiningen true)


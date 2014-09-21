;leiningen.grid
; <br />Author: Ralph Ritoch <rritoch@gmail.com>

(ns ^{:author "Ralph Ritoch <rritoch@gmail.com>"
      :doc "Grid Plugin"
    } leiningen.grid
  (:use [leiningen.help :only (help-for subtask-help-for)]
        [leiningen.grid.push :only (push)]
        [leiningen.grid.bundle :only (bundle)]))

(defn grid
  "Manage a Grid Platform based application."
  {:help-arglists '([push bundle])
   :subtasks [#'push #'bundle]}
  
  ([project]
     (println (help-for project "grid")))
  ([project subtask & args]
     (case subtask
        "push" (apply push project args)
        "bundle" (apply bundle project args)
        (println "Subtask" (str \" subtask \") "not found."
                                  (subtask-help-for *ns* #'grid)))))

;; End of namespace leiningen.grid
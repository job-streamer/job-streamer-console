(ns cljs.flowchart
  (:require [devtools.core :as devtools]
            [om.core :as om :include-macros true]
            [job-streamer.console.flowchart :as flowchart :refer [render]]))

(js/console.info "Starting in development mode")

(enable-console-print!)

(devtools/install!)

(defn log [& args]
  (.apply js/console.log js/console (apply array args)))

(render)
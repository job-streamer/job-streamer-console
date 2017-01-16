(ns job-streamer.console.config
  (:require [environ.core :refer [env]]))

(def defaults
  {:http {:port 3000}
   :console {:control-bus-url "http://localhost:45102"
             :control-bus-url-from-backend "http://localhost:45102"}})

(def environ
  {:http {:port (some-> env :console-port Integer.)}
   :console {:control-bus-url (:control-bus-url env)
             :control-bus-url-from-backend (:control-bus-url-from-backend env)}})


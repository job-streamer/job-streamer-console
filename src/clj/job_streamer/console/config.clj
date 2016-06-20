(ns job-streamer.console.config
  (:require [environ.core :refer [env]]))

(def defaults
  {:http {:port 3000}
   :console {:control-bus-url "http://localhost:45102"}})

(def environ
  {:http {:port (some-> env :port Integer.)}})


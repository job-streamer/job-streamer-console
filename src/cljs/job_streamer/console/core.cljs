(ns job-streamer.console.core
  (:require [om.core :as om :include-macros true])
  (:use [job-streamer.console.components.root :only [root-view]]))

(def app-state (atom {:query nil
                      :jobs []
                      :agents []
                      :mode [:jobs]}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})

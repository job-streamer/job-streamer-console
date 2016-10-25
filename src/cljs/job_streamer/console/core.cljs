(ns job-streamer.console.core
  (:require [om.core :as om :include-macros true])
  (:use [job-streamer.console.components.root :only [root-view]]))

(def app-state (atom {:query ""
                      :job-sort-order nil
                      :calendar-sort-order nil
                      :jobs nil
                      :agents nil
                      :system-error nil
                      :stats {:jobs-count 0 :agents-count 0}
                      :mode [:jobs]}
                      :version {:console-version 0 :control-bus-version 0}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})

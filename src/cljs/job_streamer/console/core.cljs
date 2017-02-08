(ns job-streamer.console.core
  (:require [om.core :as om :include-macros true]
            [job-streamer.console.common :refer [app-name]])
  (:use [job-streamer.console.components.root :only [root-view login-view]]))

(def app-state (atom {:query ""
                      :job-sort-order nil
                      :calendar-sort-order nil
                      :jobs nil
                      :agents nil
                      :system-error nil
                      :stats {:jobs-count 0 :agents-count 0}
                      :mode [:jobs]}
                      :version {:console-version 0 :control-bus-version 0}))

(if (.getElementById js/document "app")
  (om/root root-view app-state
           {:target (.getElementById js/document "app")})
  (om/root login-view app-state
           {:target (.getElementById js/document "login")}))

(ns cljs.user
  (:require [devtools.core :as devtools]
            [figwheel.client :as figwheel]
            [om.core :as om :include-macros true]
            [job-streamer.console.components.root :refer [root-view login-view]]))

(js/console.info "Starting in development mode")

(enable-console-print!)

(devtools/install!)

(def app-state (atom {:query ""
                      :jobs nil
                      :agents nil
                      :system-error nil
                      :stats {:jobs-count 0 :agents-count 0}
                      :mode [:jobs]}))

(if (.getElementById js/document "app")
  (om/root root-view app-state
           {:target (.getElementById js/document "app")})
  (om/root login-view app-state
           {:target (.getElementById js/document "login")}))


(figwheel/start {:websocket-url "ws://localhost:3449/figwheel-ws"})

(defn log [& args]
  (.apply js/console.log js/console (apply array args)))

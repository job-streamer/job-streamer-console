(ns job-streamer.console.routing
  (:require [om.core :as om :include-macros true]
            [clojure.browser.net :as net]
            [secretary.core :as sec :include-macros true]
            [goog.events :as events]
            [goog.History]
            [goog.history.EventType :as HistoryEventType]
            [goog.net.EventType :as EventType])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.History]))


(def control-bus-url (.. js/document
                         (querySelector "meta[name=control-bus-url]")
                         (getAttribute "content")))

(defn- setup-routing [app-state]
  (sec/set-config! :prefix "#")
  (sec/defroute "/" []
    (let [xhrio (net/xhr-connection)]
      (events/listen xhrio EventType/SUCCESS
                     (fn [e]
                       (om/update! app-state :agents
                                   (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/agents") "get"))
    (om/update! app-state :mode [:jobs :list]))

  (sec/defroute #"/jobs/new" []
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :new])))

  (sec/defroute #"/job/(\w+)" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :current]
                          :job-id job-name)))

  (sec/defroute #"/job/(\w+)/edit" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :current :edit]
                          :job-id job-name)))

  (sec/defroute #"/job/(\w+)/history" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :history]
                          :job-id job-name)))

  (sec/defroute #"/job/(\w+)/settings" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :settings]
                          :job-id job-name)))

  (sec/defroute "/jobs/timeline" []
    (om/update! app-state :mode [:jobs :timeline]))

  (sec/defroute "/agents" []
    (om/update! app-state :mode [:agents])))

(defn- setup-history [owner]
  (let [history (goog.History.)
      navigation HistoryEventType/NAVIGATE]
    (events/listen history
                   navigation
                   #(-> % .-token sec/dispatch!))
    (.setEnabled history true)))

(defn init [app-state owner]
  (setup-routing app-state)
  (setup-history owner))




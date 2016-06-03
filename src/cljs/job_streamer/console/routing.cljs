(ns job-streamer.console.routing
  (:require [om.core :as om :include-macros true]
            [clojure.browser.net :as net]
            [secretary.core :as sec :include-macros true]
            [goog.events :as events]
            [goog.History]
            [goog.history.EventType :as HistoryEventType]
            [goog.net.EventType :as EventType]
            [job-streamer.console.api :as api])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.History]))

(enable-console-print!)

(def control-bus-url (.. js/document
                         (querySelector "meta[name=control-bus-url]")
                         (getAttribute "content")))

(defn fetch-calendars [cb]
  (api/request "/calendars"
               {:handler (fn [response]
                           (cb response))}))


(defn- setup-routing [app-state]
  (sec/set-config! :prefix "#")
  (sec/defroute "/" []
    (om/update! app-state :mode [:jobs :list]))

  (sec/defroute #"/jobs/new" []
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :new])))

  (sec/defroute #"/job/([\w\-]+)" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :current]
                          :job-name job-name)))

  (sec/defroute #"/job/([\w\-]+)/edit" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :current :edit]
                          :job-name job-name)))

  (sec/defroute #"/job/([\w\-]+)/history" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :history]
                          :job-name job-name)))

  (sec/defroute #"/job/([\w\-]+)/settings" [job-name]
    (om/transact! app-state
                  #(assoc %
                          :mode [:jobs :detail :settings]
                          :job-name job-name)))

  (sec/defroute "/jobs/timeline" []
    (om/update! app-state :mode [:jobs :timeline]))

  (sec/defroute "/calendars" []
    (if (:calendars @app-state)
      (om/update! app-state :mode [:calendars])
      (fetch-calendars
       (fn [response]
         (om/transact! app-state (fn [cursor]
                                   (assoc cursor
                                          :calendars response
                                          :mode [:calendars])))))))

  (sec/defroute "/calendars/new" []
    (if (:calendars @app-state)
      (om/update! app-state :mode [:calendars :new])
      (fetch-calendars
       (fn [response]
         (om/transact! app-state (fn [cursor]
                                   (assoc cursor
                                          :cal-name nil
                                          :calendars response
                                          :mode [:calendars :new])))))))

  (sec/defroute #"/calendar/([\w\-]+)" [cal-name]
    (if (:calendars @app-state)
      (om/transact! app-state #(assoc % :mode [:calendars :detail] :cal-name cal-name))
      (fetch-calendars
       (fn [response]
         (om/transact! app-state (fn [cursor]
                                   (assoc cursor
                                          :calendars response
                                          :cal-name cal-name
                                          :mode [:calendars :detail])))))))

  (sec/defroute #"/calendar/([\w\-]+)/edit" [cal-name]
    (if (:calendars @app-state)
      (om/transact! app-state #(assoc % :mode [:calendars :edit] :cal-name cal-name))
      (fetch-calendars
       (fn [response]
         (om/transact! app-state (fn [cursor]
                                   (assoc cursor
                                          :calendars response
                                          :cal-name cal-name
                                          :mode [:calendars :edit])))))))

  (sec/defroute "/agents" []
    (om/update! app-state :mode [:agents]))

  (sec/defroute #"/agent/([a-z0-9\\-]+)" [instance-id]
    (om/transact! app-state
                  #(assoc %
                          :mode [:agents :detail]
                          :agent/instance-id instance-id))))

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


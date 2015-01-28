(ns job-streamer.console.agents
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.net.EventType]
           [goog.events EventType]
           [goog.i18n DateTimeFormat]))

(defcomponent agents-view [app owner]
  (render [_]
    (html
     [:div.ui.grid
      [:div.ui.row
       [:div.ui.column
        [:h2.ui.purple.header
         [:i.laptop.icon]
         [:div.content
          "Agent"
          [:div.sub.header "Agents for executing jobs."]]]]]
      [:div.ui.row
       [:div.ui.column
        [:table.ui.celled.striped.table
         [:thead
          [:tr
           [:th "Name"]
           [:th "CPU"]]]
         [:tbody
          (for [agt (:agents app)]
            [:tr
             [:td
              [:i.icon (case (:os-name agt)
                         "Linux" {:class "linux"}
                         {:class "help"})] (:name agt)]
             [:td (str (:cpu-core agt) "core")]])]]]]])))


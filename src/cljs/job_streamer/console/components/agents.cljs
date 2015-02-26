(ns job-streamer.console.components.agents
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component]
            (job-streamer.console.api :as api))
  (:use [cljs.reader :only [read-string]])
  (:import [goog.net.EventType]
           [goog.events EventType]
           [goog.i18n DateTimeFormat]))

(defcomponent agent-detail-view [agt owner]
  (render [_]
    ))

(defcomponent no-agents-view [app owner]
  (render [_]
    (html
     [:div.ui.icon.message
      [:i.child.icon]
      [:div.content
       [:div.header "Let's setup agents!"]
       [:ol.ui.list
        [:li
         [:h4.ui.header "Clone repository."]
         [:pre
          [:code "% git clone https://github.com/job-streamer/job-streamer-agent.git"]]]
        [:li
         [:h4.ui.header "Build docker container."]
         [:pre
          [:code "% cd job-streamer-agent\n"
           "% docker build -t job-streamer/agent:0.1.0 ."]]]
        [:li
         [:h4.ui.header "Run docker container."]
         [:pre
          [:code "% docker run job-streamer/agent:0.1.0"]]]]]])))

(defcomponent agent-list-view [agents owner]
  (render [_]
    (html
     [:table.ui.celled.striped.table
      [:thead
       [:tr
        [:th "Name"]
        [:th "CPU"]]]
      [:tbody
       (for [agt agents]
         [:tr
          [:td
           [:i.icon (case (:os-name agt)
                      "Linux" {:class "linux"}
                      {:class "help"})]
           [:a {:href (str "#/agent/" (:instance-id agt))}
            (:name agt)]]
          [:td (str (:cpu-core agt) "core")]])]])))

(defcomponent agents-view [app owner]
  (will-mount [_]
    (api/request "/agents"
                 {:handler (fn [response]
                             (om/update! app :agents response))}))
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
        (let [mode (second (:mode app))]
          (case mode
            :detail (om/build agent-detail-view (:agent/instance-id app))
            ;; default
            (cond
              (nil? (:agents app)) [:img {:src "/img/loader.gif"}]
              (empty? (:agents app)) (om/build no-agents-view app)
              :default (om/build agent-list-view (:agents app)))))]]])))


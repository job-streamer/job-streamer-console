(ns job-streamer.console.components.timeline
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan]]
            [clojure.browser.net :as net]
            [goog.ui.Component]
            [goog.object :as o]
            (job-streamer.console.format :as fmt))
  (:use [cljs.reader :only [read-string]])
  (:import [goog.i18n DateTimeFormat]))

(enable-console-print!)

(def Timeline (.-Timeline js/vis))
(def DataSet  (.-DataSet js/vis))

(defcomponent timeline-view [app owner]
  (will-mount [_]
    (om/set-state! owner :data-set
                   (DataSet.
                    (clj->js (->> (get-in app [:jobs :results])
                                  (map (fn [job]
                                         (map #(assoc % :job/name (:job/name job)) (:job/executions job))))
                                  (apply concat)
                                  (map (fn [exe]
                                         {:id (:db/id exe)
                                          :content (:job/name exe)
                                          :title (:job/name exe)
                                          :start (:job-execution/start-time exe)
                                          :end (:job-execution/end-time exe)
                                          :className (name (get-in exe [:job-execution/batch-status :db/ident]))}))
                                  (filter #(and (:start %))))))))

  (render-state [_ {:keys [selected-job]}]
    (html
     [:div.ui.grid
      [:div.ui.row
       [:div.ui.column
        [:div#timeline-inner]]]
      (when selected-job
        (let [data-set (om/get-state owner :data-set)
              item (js->clj (.get data-set selected-job))]
          [:div.ui.row
           [:div.ui.column
            [:div.ui.cards
             [:div.card
              [:div.content
               [:div.header
                (get item "content")]
               [:div.meta
                [:span (fmt/date-medium (get item "start")) ]
                [:span (fmt/date-medium (get item "end"))]]]]]]]))]))

  (did-mount
   [_]
   (.. (Timeline.
        (.getElementById js/document "timeline-inner")
        (om/get-state owner :data-set)
        (clj->js {:minHeight 400}))
       (on "select" (fn [e]
                      (om/set-state! owner :selected-job (-> e (o/get "items") (aget 0))))))))


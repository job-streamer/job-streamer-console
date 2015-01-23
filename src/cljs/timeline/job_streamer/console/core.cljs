(ns job-streamer.console.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events])
  (:import [goog.net.EventType]
           [goog.events EventType]))

(enable-console-print!)

(def Timeline (.-Timeline js/vis))
(def DataSet  (.-DataSet js/vis))

(def app-state
  {:job-executions [{:id 1 :content "Job-1" :start "2014/12/22 09:18:00" :end "2014/12/22 09:33:35"}]})

(defcomponent timeline-view [app owner]
  (render [_]
    (html [:div#timeline-inner]))
  (did-mount
   [_]
   (Timeline.
    (.getElementById js/document "timeline-inner")
    (DataSet. (clj->js (:job-executions app)))
    (clj->js {}))))

(om/root timeline-view app-state
         {:target (.getElementById js/document "timeline")})

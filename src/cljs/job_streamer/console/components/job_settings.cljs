(ns job-streamer.console.components.job-settings
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [job-streamer.console.api :as api]))

(def app-name "default")

(defn delete-job [job-name]
  (api/request (str "/" app-name "/job/" job-name) :DELETE
               {:handler (fn [response]
                           (set! (.-href js/location) "#/"))}))

(defcomponent job-settings-view [app owner]
  (render [_]
    (html
     [:div.ui.segment
      [:div.ui.top.attached.label "Danger Zone"]
      [:div.content
       [:h4.ui.header "Delete this job"]
       "Once you delete a job, there is no going back."
       [:button.ui.red.button
        {:type "button"
         :on-click (fn [e]
                     (delete-job (:job-name app)))} "Delete this job"]]])))

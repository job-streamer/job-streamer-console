(ns job-streamer.console.components.job-util
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :refer [put! <! chan timeout close!]]
            (job-streamer.console.api :as api)))

(enable-console-print!)
(def app-name "default")

(defn stop-job [job message-channel]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/stop")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution
                                         [:job-execution/batch-status :db/ident]
                                         :batch-status/stopping))
                  :error-handler (fn [response]
                                   (when message-channel
                                     (put! message-channel {:type "error" :body (:message response)})))
                  :forbidden-handler (fn [response]
                                       (when message-channel
                                         (put! message-channel {:type "error" :body "You are unauthorized to stop execution job."})))})))

(defn abandon-job [job message-channel]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/abandon")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution
                                         [:job-execution/batch-status :db/ident]
                                         :batch-status/abandoned))
                  :error-handler (fn [response]
                                   (when message-channel
                                     (put! message-channel {:type "error" :body (:message response)})))
                  :forbidden-handler (fn [response]
                                       (when message-channel
                                         (put! message-channel {:type "error" :body "You are unauthorized to abandon execution job."})))})))

(defn job-execute-button-view [job job-function]
  (let [status (get-in job [:job/latest-execution :job-execution/batch-status :db/ident])]
    (cond
      (#{:batch-status/undispatched :batch-status/unrestarted :batch-status/queued :batch-status/started} status)
      [:div.ui.fade.reveal
       [:button.ui.circular.orange.icon.button.visible.content
        {:on-click (:progress job-function)}
        [:i.setting.loading.icon]]
       [:button.ui.circular.red.icon.basic.button.hidden.content
        (if (#{:batch-status/started} status)
          [:i.pause.icon]
          [:i.stop.icon])]]

      (#{:batch-status/stopped :batch-status/failed} status)
      (when (not (false? (:job/restartable? job)))
        [:div
         [:button.ui.circular.red.icon.inverted.button
          {:title "abandon"
           :on-click (:abandon job-function)}
          [:i.stop.icon]]
         [:button.ui.circular.yellow.icon.inverted.button
          {:title "restart"
           :on-click (:restart job-function)}
          [:i.play.icon]]])

      (#{:batch-status/starting  :batch-status/stopping} status)
      [:div]

      :else
      [:button.ui.circular.icon.green.inverted.button
       {:title "start"
        :on-click (:start job-function)}
       [:i.play.icon]])))


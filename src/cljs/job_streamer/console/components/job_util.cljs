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


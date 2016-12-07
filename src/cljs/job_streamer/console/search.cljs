(ns job-streamer.console.search
  (:require [om.core :as om :include-macros true]
            (job-streamer.console.api :as api)
            [goog.Uri.QueryData :as query-data]
            [clojure.string :as string]
            [cljs.core.async :refer [put! <! chan timeout]]
            [job-streamer.console.core :refer [app-name]])
  (:import [goog Uri]))

(defn search-jobs
  ([app query]
   (search-jobs app query nil))
  ([app query message-channel]
  (let [uri (.. (Uri. (str "/" app-name "/jobs"))
                (setQueryData (query-data/createFromMap (clj->js query))))]
    (api/request (.toString uri)
               :GET
               {:handler (fn [response]
                           (om/transact! app
                                         #(assoc %
                                                 :jobs response
                                                 :query (:q query))))
                :forbidden-handler (fn [response status]
                                     (when message-channel
                                       (put! message-channel {:type "error" :body "You are unauthorized to read jobs."}))
                                     (om/transact! app
                                         #(assoc %
                                                 :jobs []
                                                 :query (:q query))))}))))

(defn parse-sort-order [sort-order]
 (->> sort-order
      reverse
      (map (fn[m] (str (-> m first name) ":" (-> m second name))))
      (string/join ",")))


(defn toggle-sort-order[sort-order keyfn]
  (case (keyfn sort-order)
    :asc (assoc sort-order keyfn :desc)
    :desc (dissoc sort-order keyfn)
    ;now sort key must be one
    {keyfn :asc}))




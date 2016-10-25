(ns job-streamer.console.search
  (:require [om.core :as om :include-macros true]
            (job-streamer.console.api :as api)
            [goog.Uri.QueryData :as query-data]
            [clojure.string :as string]
            [linked.core :as linked])
  (:import [goog Uri]))

(def app-name "default")

(defn search-jobs [app query]
  (let [uri (.. (Uri. (str "/" app-name "/jobs"))
                (setQueryData (query-data/createFromMap (clj->js query))))]
    (api/request (.toString uri)
               :GET
               {:handler (fn [response]
                           (om/transact! app
                                         #(assoc %
                                                 :jobs response
                                            :query (:q query))))})))

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




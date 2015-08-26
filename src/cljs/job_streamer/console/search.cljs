(ns job-streamer.console.search
  (:require [om.core :as om :include-macros true]
            (job-streamer.console.api :as api)
            [goog.Uri.QueryData :as query-data])
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


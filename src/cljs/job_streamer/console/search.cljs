(ns job-streamer.console.search
  (:require [om.core :as om :include-macros true]
            (job-streamer.console.api :as api)))

(defn search-jobs [app job-query]
  (api/request (str "/jobs?q=" (js/encodeURIComponent job-query)) :GET
               {:handler (fn [response]
                           (om/update! app :jobs response))}))


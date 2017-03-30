(ns job-streamer.console.components.job-breadcrumb
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [clojure.string :as string]
            [goog.string :as gstring]))

(def breadcrumb-elements
  {:jobs {:name "Jobs" :href "#/"}
   :jobs.new {:name "New" :href "#/jobs/new"}
   :jobs.detail {:name "Job: %s" :href "#/job/%s"}
   :jobs.detail.current.edit {:name "Edit" :href "#/job/%s/edit"}
   :jobs.detail.history {:name "History" :href "#/job/%s/history"}
   :jobs.detail.settings {:name "Settings" :href "#/job/%s/settings"}
   :jobs.detail.progress {:name "Progress" :href "#/job/%s/progress"}})

(defcomponent breadcrumb-view [mode owner]
  (render-state [_ {:keys [job-name]}]
                (html
                 [:div.ui.breadcrumb
                  (drop-last
                   (interleave
                    (loop [i 1, items []]
                      (if (<= i (count mode))
                        (recur (inc i)
                               (conj items (if-let [item (get breadcrumb-elements
                                                              (->> mode
                                                                   (take i)
                                                                   (map name)
                                                                   (string/join ".")
                                                                   keyword))]
                                             [:div.section
                                              [:a {:href (gstring/format (:href item) job-name)
                                                   :title job-name}
                                               (gstring/format (:name item) job-name)]])))
                        (let [res (keep identity items)]
                          (conj (vec (drop-last res))
                                (into [:div.section.active] (-> res last (get-in [1 2])))))))
                    (repeat [:i.right.chevron.icon.divider])))])))

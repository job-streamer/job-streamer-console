(ns job-streamer.console.components.root
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [job-streamer.console.routing :as routing]
            [job-streamer.console.api :as api])
  (:use [job-streamer.console.components.jobs :only [jobs-view]]
        [job-streamer.console.components.agents :only [agents-view]]
        [job-streamer.console.search :only [search-jobs]]))

(def app-name "default")

(defcomponent right-menu-view [app owner]
  (will-mount [_]
    (api/request (str "/" app-name "/stats")
                 {:handler (fn [response]
                             (om/update-state! owner
                                               #(assoc %
                                                       :agents-count (:agents response)
                                                       :jobs-count (:jobs response))))}))
  (render-state [_ {:keys [agents-count jobs-count]}]
    (html 
     [:div.right.menu
      [:div#agent-stats.item
       (when (= (first (:mode app)) :agents) {:class "active"})
       [:a.ui.tiny.horizontal.statistics
        {:href "#/agents"}
        [:div.ui.inverted.statistic
         [:div.value agents-count]
         [:div.label (str "agent" (when (> agents-count 1) "s"))]]]]
      [:div#job-stats.item
       (when (= (first (:mode app)) :jobs) {:class "active"})
       [:a.ui.tiny.horizontal.statistics
        {:href "#/"}
        [:div.ui.inverted.statistic
         [:div.value jobs-count]
         [:div.label (str "job" (when (> jobs-count 1) "s"))]]]]
      [:div#job-search.item
       [:form {:on-submit (fn [e] (search-jobs app (.-value (.getElementById js/document "job-query"))) false)}
        [:div.ui.icon.transparent.inverted.input
         [:input#job-query {:type "text"}]
         [:i.search.icon]]]]])))

(defcomponent root-view [app owner]
  (will-mount [_]
    (routing/init app owner))
  (render [_]
    (html
     [:div
      [:div.ui.fixed.inverted.teal.menu
       [:div.title.item [:b "Job Streamer"]]
       (om/build right-menu-view app)]
      [:div.main.grid.content.full.height
       (case (first (:mode app))
        :jobs (om/build jobs-view app {:init-state {:mode (second (:mode app))}})
        :agents (om/build agents-view app))]])))

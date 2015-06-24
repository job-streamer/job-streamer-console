(ns job-streamer.console.components.root
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [job-streamer.console.routing :as routing]
            [job-streamer.console.api :as api])
  (:use [job-streamer.console.components.jobs :only [jobs-view]]
        [job-streamer.console.components.agents :only [agents-view]]
        [job-streamer.console.components.calendars :only [calendars-view]]
        [job-streamer.console.search :only [search-jobs]]))

(def app-name "default")

(defcomponent right-menu-view [app owner]
  (will-mount [_]
    (api/request (str "/" app-name "/stats")
                 {:handler (fn [response]
                             (om/transact! app :stats
                                           #(assoc %
                                                   :agents-count (:agents response)
                                                   :jobs-count (:jobs response))))
                  :error-handler
                  {:http-error (fn [res]
                                 (om/update! app :system-error "error"))}}))
  (render-state [_ {:keys [control-bus-not-found?]}]
    (let [{{:keys [agents-count jobs-count]} :stats}  app]
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
         [:form {:on-submit (fn [e] (search-jobs app {:q (.-value (.getElementById js/document "job-query"))}) false)}
          [:div.ui.icon.transparent.inverted.input
           [:input#job-query {:type "text"}]
           [:i.search.icon]]]]]))))

(defcomponent system-error-view [app owner]
  (render [_]
    (html
     [:div.ui.dimmer.modals.transition.visible.active
      [:div.ui.basic.modal.transition.visible.active {:style {:margin-top "-142.5px;"}}
       [:div.header "A Control bus is NOT found."]
       [:div.content
        [:div.image [:i.announcement.icon]]
        [:div.description [:p "Run a control bus first and reload this page."]]]]])))

(defcomponent root-view [app owner]
  (will-mount [_]
    (routing/init app owner))
  (render [_]
    (html
     [:div.ui.page
      (if-let [system-error (:system-error app)]
        (om/build system-error-view app)
        (list
         [:div.ui.fixed.inverted.teal.menu
          [:div.title.item [:img {:alt "JobStreamer" :src "img/logo.png"}]]
          (om/build right-menu-view app)]
         [:div.main.grid.content.full.height
          (case (first (:mode app))
            :jobs (om/build jobs-view app {:init-state {:mode (second (:mode app))}})
            :agents (om/build agents-view app)
            :calendars (om/build calendars-view app))]))])))

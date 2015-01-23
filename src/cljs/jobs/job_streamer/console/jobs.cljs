(ns job-streamer.console.jobs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component])
  (:use [cljs.reader :only [read-string]]
        [job-streamer.console.agents :only [agents-view]])
  (:import [goog.net.EventType]
           [goog.events EventType]
           [goog.i18n DateTimeFormat]))

(enable-console-print!)

(def date-format (DateTimeFormat. goog.i18n.DateTimeFormat.Format.SHORT_DATETIME
                                  (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))
(def control-bus-url "http://localhost:45102")
(def app-state (atom {:page :jobs
                      :query nil
                      :jobs []
                      :agents []}))

(defn execute-job [job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio goog.net.EventType.SUCCESS
                   (fn [e]))
    (.send xhrio (str control-bus-url "/job/" job-id "/executions") "post")))

(defn search-jobs [app job-query]
  (let [xhrio (net/xhr-connection)]
      (events/listen xhrio goog.net.EventType.SUCCESS
                     (fn [e]
                       (om/update! app :jobs
                                   (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/jobs?q=" (js/encodeURIComponent job-query)) "get")))

(defn search-execution [last-execution job-id execution-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio goog.net.EventType.SUCCESS
                   (fn [e]
                     (let [steps (-> (.getResponseText xhrio)
                                    (read-string)
                                    :job-execution/step-executions)]
                       (om/transact! last-execution
                                     #(assoc % :job-execution/step-executions steps)))))
    (.send xhrio (str control-bus-url "/job/" job-id "/execution/" execution-id))))

(defcomponent execution-view [step-executions owner]
  (render [_]
    (html [:div.ui.list.step-view
           (for [step-execution step-executions]
             [:div.item
              [:div.top.aligned.image
               [:i.play.icon]]
              [:div.content
               [:div.header (get-in step-execution [:step-execution/step :step/id])]
               (if-let [start (:step-execution/start-time step-execution)]
                  (.format date-format start))
               "-"
               (if-let [end   (:step-execution/end-time step-execution)]
                 (.format date-format end))
               [:div.log.list
                (for [log (:step-execution/logs step-execution)]
                  [:div.item
                   [:div.content
                    [:div.description
                     [:span.date (when-let [log-date (:execution-log/date log)]
                                   (.format date-format log-date))]
                     (let [level (name (get-in log [:execution-log/level :db/ident]))]
                       [:span.ui.horizontal.label {:class (case level
                                                            "error" "red"
                                                            "warn" "yellow"
                                                            "info" "blue"
                                                            "")}
                        level])
                     [:span (:execution-log/message log)]]
                    (when-let [exception (:execution-log/exception log)]
                      [:div.description
                       [:pre.exception exception]])]])]]])])))

(defcomponent job-list-view [app owner]
  (will-mount [_]
    (search-jobs app ""))
  (render [_]
    (html [:div.ui.grid
           [:div.ui.two.column.row
            [:div.column
             [:button.ui.button
              {:type "button"
               :on-click (fn [e]
                           (set! (.-href js/location) "/job/new"))}
              [:i.plus.icon] "New"]]
            [:div.ui.right.aligned.column
             [:button.ui.circular.icon.button
              {:type "button"
               :on-click (fn [e]
                           (search-jobs app ""))}
              [:i.refresh.icon]]]]
           [:div.row
            [:div.column
             [:table.ui.table
              [:thead
               [:tr
                [:th {:rowSpan 2} "Job name"]
                [:th {:colSpan 3} "Last execution"]
                [:th "Next execution"]
                [:th {:rowSpan 2} "Operations"]]
               [:tr
                [:th "start"]
                [:th "end"]
                [:th "status"]
                [:th "start"]]]
              [:tbody
               (apply concat
                      (for [{job-id :job/id :as job} (:jobs app)]
                        [[:tr
                          [:td (:job/id job)]
                          (if-let [last-execution (:job/last-execution job)]
                            (if (= (get-in last-execution [:job-execution/batch-status :db/ident]) :batch-status/registered)
                              [:td.center.aligned {:colSpan 3} "Wait for an execution..."]
                              (list
                               [:td (if-let [start (:job-execution/start-time last-execution)]
                                      (let [id (:db/id last-execution)]
                                        [:a {:on-click (fn [e] (search-execution last-execution job-id id))}
                                         (.format date-format start)]))]
                               [:td (if-let [end (:job-execution/end-time  last-execution)]
                                      (.format date-format end))]
                               (let [status (name (get-in last-execution [:job-execution/batch-status :db/ident]))]
                                      [:td {:class (condp =
                                                       "completed" "positive"
                                                       "failed" "negative")} 
                                       status])))
                            [:td.center.aligned {:colSpan 3} "No executions"])
                          (if-let [next-execution (:next-execution job)]
                            [:td (:start  next-execution)]
                            [:td.center.aligned
                             [:a {:on-click (fn [e]
                                              (om/update! job :scheduling true)
                                              (events/listenOnce js/document "click"
                                                             (fn [e] (om/update! job :scheduling false))))}
                              [:i.calendar.large.link.icon]]
                             [:div.ui.top.left.flowing.popup
                              {:class (when (:scheduling job) "visible")
                               :on-click (fn [e]
                                           (.stopPropagation e)
                                           (.. e -nativeEvent stopImmediatePropagation)) }
                              [:div.ui.form
                               [:div.field
                                [:label "format"]
                                [:input {:type "text"}]]]]])
                          [:td (if (some #{:batch-status/registered :batch-status/starting :batch-status/started :batch-status/stopping}
                                         [(get-in job [:job/last-execution :job-execution/batch-status :db/ident])])
                                 [:div.ui.circular.icon.orange.button
                                    [:i.setting.loading.icon]]
                                 [:button.ui.circular.icon.green.button
                                    {:on-click (fn [e]
                                                 (om/update! job :job/last-execution {:job-execution/batch-status {:db/ident :batch-status/registered}})
                                                 (execute-job job-id))}
                                    [:i.play.icon]])]]
                         (when-let [step-executions (not-empty (get-in job [:job/last-execution :job-execution/step-executions]))]
                           [:tr
                            [:td {:colSpan 8}
                             (om/build execution-view step-executions)]])]))]]]]])))


(def Timeline (.-Timeline js/vis))
(def DataSet  (.-DataSet js/vis))

(defcomponent timeline-view [app owner]
  (render-state [_ {:keys [selected-job]}]
    (html [:div.ui.grid
           [:div.ui.row
              [:div.ui.column
               [:div#timeline-inner]]]
           (when selected-job
             (let [data-set (om/get-state owner :data-set)
                      item (js->clj (.get data-set selected-job))]
               [:div.ui.row
                [:div.ui.column
                 [:div.ui.cards
                  [:div.card
                   [:div.content
                    [:div.header
                     (get item "content")]
                    [:div.meta
                     [:span (when-let [start (get item "start")]
                              (.format date-format start)) ]
                     [:span (when-let [end (get item "end")]
                              (.format date-format end))]]]]]]]))]))
  (will-mount [_]
    (om/set-state! owner :data-set
                   (DataSet.
                    (clj->js (->> (:jobs app)
                                  (map (fn [job]
                                         (map #(assoc % :job/id (:job/id job)) (:job/executions job))))
                                  (apply concat)
                                  (map (fn [exe]
                                         {:id (:db/id exe)
                                          :content (:job/id exe)
                                          :title (:job/id exe)
                                          :start (:job-execution/start-time exe) 
                                          :end (:job-execution/end-time exe)})))))))
  (did-mount
   [_]
   (.. (Timeline.
        (.getElementById js/document "timeline-inner")
        (om/get-state owner :data-set)
        (clj->js {}))
       (on "select" (fn [e]
                      (om/set-state! owner :selected-job (-> e (aget "items") (aget 0))))))))

(defcomponent jobs-view [app owner]
  (init-state [_]
    {:mode :list})
  (render-state [_ {:keys [mode]}]
    (html [:div
           [:h2.ui.purple.header
            [:i.setting.icon]
            [:div.content
             "Job"
            [:div.sub.header "Edit and execute a job."]]]
           [:div.ui.top.attached.tabular.menu
            [:a (merge {:class "item"
                        :on-click #(om/set-state! owner :mode :list)}
                       (when (= mode :list) {:class "item active"}))
             "list"]
            [:a (merge {:class "item"
                        :on-click #(om/set-state! owner :mode :timeline)}
                       (when (= mode :timeline) {:class "item active"}))
             "timeline"]]
           [:div.ui.bottom.attached.active.tab.segment
            [:div#tab-content
             (om/build (case mode
                        :timeline timeline-view
                        :list     job-list-view)
                      app)]]])))

(defcomponent main-view [app owner]
  (will-mount [_]
    (let [xhrio (net/xhr-connection)]
      (events/listen xhrio goog.net.EventType.SUCCESS
                     (fn [e]
                       (om/update! app :agents
                                   (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/agents") "get")))
  (render [_]
    (html [:div
           
           (case (:page app)
             :agents (om/build agents-view app)
             :jobs (om/build jobs-view app))])))

(defcomponent right-menu-view [app owner]
  (render [_]
    (html 
     [:div
      [:div#agent-stats.item
       (when (= (:page app) :agents) {:class "active"})
       [:a.ui.tiny.horizontal.statistics
        {:on-click (fn [e]
                     (om/update! app :page :agents))}
        [:div.ui.inverted.statistic
         [:div.value (count (:agents app))]
         [:div.label (str "agent" (when (> (count (:agents app)) 1) "s"))]]]]
      [:div#job-stats.item
       (when (= (:page app) :jobs) {:class "active"})
       [:a.ui.tiny.horizontal.statistics
        {:on-click (fn [e]
                     (om/update! app :page :jobs))}
        [:div.ui.inverted.statistic
         [:div.value (count (:jobs app))]
         [:div.label (str "job" (when (> (count (:jobs app)) 1) "s"))]]]]
      [:div#job-search.item
       [:form {:on-submit (fn [e] (search-jobs app (.-value (.getElementById js/document "job-query"))) false)}
        [:div.ui.icon.transparent.inverted.input
         [:input#job-query {:type "text"}]
         [:i.search.icon]]]]])))

(om/root main-view app-state
         {:target (.getElementById js/document "main")})

(om/root right-menu-view app-state
         {:target (.getElementById js/document "menu")})


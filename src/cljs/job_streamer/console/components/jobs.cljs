(ns job-streamer.console.components.jobs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component]
            (job-streamer.console.format :as fmt))
  (:use [cljs.reader :only [read-string]]
        (job-streamer.console.components.agents :only [agents-view])
        (job-streamer.console.components.timeline :only [timeline-view])
        (job-streamer.console.components.job-detail :only [job-new-view job-detail-view])
        (job-streamer.console.components.execution :only [execution-view]))
  (:import [goog.net EventType]
           [goog.events KeyCodes]))

(enable-console-print!)

(def control-bus-url (.. js/document
                         (querySelector "meta[name=control-bus-url]")
                         (getAttribute "content")))



(defn execute-job [job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]))
    (.send xhrio (str control-bus-url "/job/" job-id "/executions") "post")))

(defn search-jobs [app job-query]
  (let [xhrio (net/xhr-connection)]
      (events/listen xhrio EventType.SUCCESS
                     (fn [e]
                       (om/update! app :jobs
                                   (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/jobs?q=" (js/encodeURIComponent job-query)) "get")))

(defn search-execution [latest-execution job-id execution-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (let [steps (-> (.getResponseText xhrio)
                                    (read-string)
                                    :job-execution/step-executions)]
                       (om/transact! latest-execution
                                     #(assoc % :job-execution/step-executions steps)))))
    (.send xhrio (str control-bus-url "/job/" job-id "/execution/" execution-id))))

(defcomponent job-list-view [app owner]
  (will-mount [_]
    (search-jobs app ""))
  (render [_]
    (html
     (if (empty? (:jobs app))
       [:div.ui.grid
        [:div.ui.one.column.row
         [:div.column
          [:div.ui.icon.message
           [:i.child.icon]
           [:div.content
            [:div.header "Let's create a job!"]
            [:p [:button.ui.primary.button
                 {:type "button"
                  :on-click (fn [e]
                              (set! (.-href js/location) "#/jobs/new"))}
                 [:i.plus.icon] "Create the first job"]]]]]]]
       [:div.ui.grid
        [:div.ui.two.column.row
         [:div.column
          [:button.ui.button
           {:type "button"
            :on-click (fn [e]
                        (set! (.-href js/location) "#/jobs/new"))}
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
             [:th "Started at"]
             [:th "Duration"]
             [:th "Status"]
             [:th "Start"]]]
           [:tbody
            (apply concat
                   (for [{job-id :job/id :as job} (:jobs app)]
                     [[:tr
                       [:td 
                        [:a {:href (str "#/job/" job-id)} job-id]]
                       (if-let [latest-execution (:job/latest-execution job)]
                         (if (= (get-in latest-execution [:job-execution/batch-status :db/ident]) :batch-status/registered)
                           [:td.center.aligned {:colSpan 3} "Wait for an execution..."]
                           (let [start (:job-execution/start-time latest-execution)
                                 end (:job-execution/end-time  latest-execution)]
                             (list
                              [:td (when start
                                     (let [id (:db/id latest-execution)]
                                       [:a {:on-click (fn [e] (search-execution latest-execution job-id id))}
                                        (fmt/date-medium start)]))]
                              [:td (fmt/duration-between start end)]
                              (let [status (name (get-in latest-execution [:job-execution/batch-status :db/ident]))]
                                [:td {:class (condp = status
                                               "completed" "positive"
                                               "failed" "negative"
                                               "")} 
                                 status]))))
                         [:td.center.aligned {:colSpan 3} "No executions"])
                       [:td
                        (if-let [next-execution (:job/next-execution job)]
                          (fmt/date-medium (:job-execution/start-time next-execution))
                          "-")]
                       [:td (if (some #{:batch-status/registered :batch-status/starting :batch-status/started :batch-status/stopping}
                                      [(get-in job [:job/latest-execution :job-execution/batch-status :db/ident])])
                              [:div.ui.circular.icon.orange.button
                               [:i.setting.loading.icon]]
                              [:button.ui.circular.icon.green.button
                               {:on-click (fn [e]
                                            (om/update! job :job/latest-execution {:job-execution/batch-status {:db/ident :batch-status/registered}})
                                            (execute-job job-id))}
                               [:i.play.icon]])]]
                      (when-let [step-executions (not-empty (get-in job [:job/latest-execution :job-execution/step-executions]))]
                        [:tr
                         [:td {:colSpan 8}
                          (om/build execution-view step-executions)]])]))]]]]]))))


(defcomponent jobs-view [app owner]
  (render [_]
    (let [mode (second (:mode app) )]
      (print mode)
      (html
     [:div
      [:h2.ui.purple.header
       [:i.setting.icon]
       [:div.content
        "Job"
        [:div.sub.header "Edit and execute a job."]]]
      (case mode
        :create
        (om/build job-new-view (select-keys app [:job-id :mode]))

        :detail
        (om/build job-detail-view (select-keys app [:job-id :mode]))

        ;; default
        [:div
         [:div.ui.top.attached.tabular.menu
          [:a (merge {:class "item"
                      :href "#/"}
                     (when (= mode :list) {:class "item active"}))
           [:i.list.icon] "list"]
          [:a (merge {:class "item"
                      :href "#/jobs/timeline"}
                     (when (= mode :timeline) {:class "item active"}))
           [:i.wait.icon] "timeline"]]
         [:div.ui.bottom.attached.active.tab.segment
          [:div#tab-content
           (om/build (case mode
                       :timeline timeline-view
                       ;; default
                       job-list-view)
                     app)]]])]))))

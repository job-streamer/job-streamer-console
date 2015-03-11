(ns job-streamer.console.components.jobs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            (job-streamer.console.format :as fmt)
            (job-streamer.console.api :as api))
  (:use (job-streamer.console.components.timeline :only [timeline-view])
        (job-streamer.console.components.job-detail :only [job-new-view job-detail-view])
        (job-streamer.console.components.execution :only [execution-view])
        [job-streamer.console.search :only [search-jobs]]))

(enable-console-print!)
(def app-name "default")

(defn execute-job [job-name]
  (api/request (str "/" app-name "/job/" job-name "/executions") :POST
               {:handler (fn [response])}))

(defn stop-job [job]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name 
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/stop")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution :job-execution/batch-status :batch-status/stopped))})))

(defn search-execution [latest-execution job-name execution-id]
  (api/request (str "/" app-name "/job/" job-name "/execution/" execution-id)
               {:handler (fn [response]
                           (let [steps (:job-execution/step-executions response)]
                             (om/transact! latest-execution
                                           #(assoc % :job-execution/step-executions steps))))}))

(defcomponent job-list-view [app owner]
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
                   (for [{job-name :job/name :as job} (:jobs app)]
                     [[:tr
                       [:td 
                        [:a {:href (str "#/job/" job-name)} job-name]]
                       (if-let [latest-execution (:job/latest-execution job)]
                         (if (= (get-in latest-execution [:job-execution/batch-status :db/ident]) :batch-status/registered)
                           [:td.center.aligned {:colSpan 3} "Wait for an execution..."]
                           (let [start (:job-execution/start-time latest-execution)
                                 end (:job-execution/end-time  latest-execution)]
                             (list
                              [:td (when start
                                     (let [id (:db/id latest-execution)]
                                       [:a {:on-click (fn [e] (search-execution latest-execution job-name id))}
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
                              [:button.ui.circular.icon.orange.button
                               {:on-click (fn [e]
                                            (stop-job job))}
                               [:i.setting.loading.icon]]
                              [:button.ui.circular.icon.green.button
                               {:on-click (fn [e]
                                            (om/update! job :job/latest-execution {:job-execution/batch-status {:db/ident :batch-status/registered}})
                                            (execute-job job-name))}
                               [:i.play.icon]])]]
                      (when-let [step-executions (not-empty (get-in job [:job/latest-execution :job-execution/step-executions]))]
                        [:tr
                         [:td {:colSpan 8}
                          (om/build execution-view step-executions)]])]))]]]]]))))


(defcomponent jobs-view [app owner]
  (will-mount [_]
    (search-jobs app ""))
  (render [_]
    (let [mode (second (:mode app) )]
      (html
     [:div
      [:h2.ui.purple.header
       [:i.setting.icon]
       [:div.content
        "Job"
        [:div.sub.header "Edit and execute a job."]]]
      (case mode
        :new
        (om/build job-new-view (select-keys app [:job-name :mode]))

        :detail
        (om/build job-detail-view (select-keys app [:job-name :mode]))

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
           (if (nil? (:jobs app))
             [:img {:src "/img/loader.gif"}]
             (om/build (case mode
                       :timeline timeline-view
                       ;; default
                       job-list-view)
                     app))]]])]))))

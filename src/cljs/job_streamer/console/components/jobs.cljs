(ns job-streamer.console.components.jobs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            (job-streamer.console.format :as fmt)
            (job-streamer.console.api :as api))
  (:use (job-streamer.console.components.timeline :only [timeline-view])
        (job-streamer.console.components.job-detail :only [job-new-view job-detail-view])
        (job-streamer.console.components.execution :only [execution-view])
        (job-streamer.console.components.pagination :only [pagination-view])
        (job-streamer.console.components.dialog :only[dangerously-action-dialog])
        [job-streamer.console.search :only [search-jobs]]))

(enable-console-print!)
(def app-name "default")

(defn execute-job [job-name parameters channel]
  (api/request (str "/" app-name "/job/" job-name "/executions") :POST
               parameters
               {:handler (fn [response]
                           (put! channel [:close-dialog nil]))}))

(defn stop-job [job]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/stop")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution
                                         [:job-execution/batch-status :db/ident]
                                         :batch-status/stopping))})))

(defn abandon-job [job]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/abandon")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution
                                         [:job-execution/batch-status :db/ident]
                                         :batch-status/abandoned))})))

(defn restart-job [job parameters channel]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/restart")
                 :PUT
                 parameters
                 {:handler (fn [response]
                             (put! channel [:close-dialog nil]))})))

(defn search-execution [latest-execution job-name execution-id]
  (api/request (str "/" app-name "/job/" job-name "/execution/" execution-id)
               {:handler (fn [response]
                           (let [steps (:job-execution/step-executions response)]
                             (om/transact! latest-execution
                                           #(assoc % :job-execution/step-executions steps))))}))

(defcomponent job-execution-dialog [[type job] owner]
  (init-state [_]
              {:params {}})
  (render-state [_ {:keys [jobs-view-channel params]}]
                (html
                  [:div.ui.dimmer.modals.transition.visible.active
                   [:div.ui.standard.modal.transition.visible.active.scrolling
                    [:i.close.icon {:on-click (fn [e] (put! jobs-view-channel [:close-dialog nil]))}]
                    [:div.header (:job/name job)]
                    [:div.content
                     (if-let [param-names (not-empty (:job/dynamic-parameters job))]
                       [:form.ui.form
                        [:fields
                         (for [param-name param-names]
                           [:field
                            [:label param-name]
                            [:input {:type "text"
                                     :name param-name
                                     :value (get params (keyword param-name))
                                     :on-change (fn [e] (om/update-state! owner :params
                                                                          #(assoc % (keyword param-name) (.. e -target -value))))}]])]]
                       [:div (case type
                               :execute "Execute now?"
                               :restart "Restart now?")])]
                    [:div.actions
                     [:div.ui.two.column.grid
                      [:div.left.aligned.column
                       [:button.ui.black.button
                        {:type "button"
                         :on-click (fn [e] (put! jobs-view-channel [:close-dialog nil]))} "Cancel"]]
                      [:div.right.aligned.column
                       [:button.ui.positive.button
                        {:type "button"
                         :on-click (fn [e]
                                     (case type
                                       :execute (execute-job (:job/name job) params jobs-view-channel)
                                       :restart (restart-job job params jobs-view-channel)))}
                        (case type
                          :execute "Execute!"
                          :restart "Restart!")]]]]]])))

(defcomponent job-list-view [app owner]
  (init-state [ctx] {:now (js/Date.)
                     :per 20})
  (will-mount [_]
              (go-loop []
                       (<! (timeout 1000))
                       (om/set-state! owner :now (js/Date.))
                       (recur)))

  (did-mount [ctx]
             (go-loop []
                      (<! (timeout 5000))
                      (if (->> (get-in @app [:jobs :results])
                               (filter #(#{:batch-status/started :batch-status/starting :batch-status/undispatched :batch-status/queued}
                                                                 (get-in % [:job/latest-execution :job-execution/batch-status :db/ident])))
                               not-empty)
                        (let [page ((:page ctx) 1)
                              per  (om/get-state owner :per)]
                          (search-jobs app {:q (:query app) :offset (inc (* (dec page) per)) :limit per})
                          {:page page}))
                      (recur)))
  (render-state [ctx{:keys [jobs-view-channel now page per]}]
                (html
                  (if (= (get-in app [:stats :jobs-count]) 0)
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
                       [:button.ui.basic.green.button
                        {:type "button"
                         :on-click (fn [e]
                                     (set! (.-href js/location) "#/jobs/new"))}
                        [:i.plus.icon] "New"]]
                      [:div.ui.right.aligned.column
                       [:button.ui.circular.basic.orange.icon.button
                        {:type "button"
                         :on-click (fn [e]
                                     (search-jobs app {:q (:query app) :offset (inc (* (dec page) per)) :limit per}))}
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
                                (for [{job-name :job/name :as job} (get-in app [:jobs :results])]
                                  [[:tr
                                    [:td
                                     [:a {:href (str "#/job/" job-name)} job-name]]
                                    (if-let [latest-execution (:job/latest-execution job)]
                                      (if (#{:batch-status/undispatched :batch-status/queued} (get-in latest-execution [:job-execution/batch-status :db/ident]))
                                        [:td.center.aligned {:colSpan 3} "Wait for an execution..."]
                                        (let [start (:job-execution/start-time latest-execution)
                                              end (or (:job-execution/end-time  latest-execution) now)]
                                          (list
                                            [:td.log-link
                                             (when start
                                               (let [id (:db/id latest-execution)]
                                                 [:a {:on-click (fn [_]
                                                                  (if (not-empty (:job-execution/step-executions latest-execution))
                                                                    (om/update! latest-execution :job-execution/step-executions nil)
                                                                    (search-execution latest-execution job-name id)))}
                                                  (fmt/date-medium start)]))]
                                            [:td (let [duration (fmt/duration-between start end)]
                                                   (if (= duration 0) "-" duration)) ]
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
                                    [:td (let [status (get-in job [:job/latest-execution :job-execution/batch-status :db/ident])]
                                           (cond
                                             (#{:batch-status/undispatched :batch-status/queued :batch-status/started} status)
                                             [:div.ui.fade.reveal
                                              [:button.ui.circular.orange.icon.button.visible.content
                                               {:on-click (fn [_]
                                                            (if (#{:batch-status/started} status)
                                                              (stop-job job)
                                                              (abandon-job job)))}
                                               [:i.setting.loading.icon]]
                                              [:button.ui.circular.red.icon.basic.button.hidden.content
                                               (if (#{:batch-status/started} status)
                                                 [:i.pause.icon]
                                                 [:i.stop.icon])]]

                                             (#{:batch-status/stopped :batch-status/failed} status)
                                             [:div
                                              [:button.ui.circular.red.icon.basic.button
                                               {:on-click (fn [_]
                                                            (abandon-job job))}
                                               [:i.stop.icon]]
                                              [:button.ui.circular.yellow.icon.basic.button
                                               {:title "restart"
                                                :on-click (fn [_]
                                                            (api/request (str "/" app-name "/job/" job-name)
                                                                         {:handler (fn [job]
                                                                                     (put! jobs-view-channel [:restart-dialog job]))}))}
                                               [:i.play.icon]]]

                                             (#{:batch-status/starting  :batch-status/stopping} status)
                                             [:div]

                                             :else
                                             [:button.ui.circular.icon.green.basic.button
                                              {:on-click (fn [_]
                                                           (api/request (str "/" app-name "/job/" job-name)
                                                                        {:handler (fn [job]
                                                                                    (put! jobs-view-channel [:execute-dialog job]))}))}
                                              [:i.play.icon]]))]]
                                   (when-let [step-executions (not-empty (get-in job [:job/latest-execution :job-execution/step-executions]))]
                                     [:tr
                                      [:td {:colSpan 8}
                                       (om/build execution-view step-executions)]])]))]]]]
                     [:div.row
                      [:div.column
                       (om/build pagination-view {:hits (get-in app [:jobs :hits])
                                                  :page (:page ctx)
                                                  :per per}
                                 {:init-state {:link-fn (fn [pn]
                                                          (search-jobs app {:q (:query app) :offset (inc (* (dec pn) per)) :limit per}))
                                               :jobs-view-channel jobs-view-channel}})]]]))))


(defcomponent jobs-view [app owner {:keys [stats-channel jobs-channel]}]
  (init-state [_]
              {:dangerously-action-data nil
               :page 1})
  (will-mount [_]
              (search-jobs app {:q (:query app) :p 1})
              (go-loop []
                       (let [[cmd msg] (<! jobs-channel)]
                         (try
                           (case cmd
                             :execute-dialog  (om/set-state! owner :executing-job [:execute msg])
                             :restart-dialog  (om/set-state! owner :executing-job [:restart msg])
                             :close-dialog (do (om/set-state! owner :executing-job nil)
                                             (search-jobs app {:q (:query app)}))
                             :refresh-jobs (do (search-jobs app {:q (:query app)})
                                             (put! stats-channel true))
                             :delete-job (do
                                           (println (get-in app [:jobs :results]))
                                           (fn [results]
                                             (remove #(= % msg) results))
                                           (put! jobs-channel [:refresh-jobs true]))
                             :change-page (do {:page msg}
                                            (println "change page"))
                             :open-dangerously-dialog (om/set-state! owner :dangerously-action-data msg))
                           (catch js/Error e))
                         (recur))))
  (render-state [_ {:keys [executing-job dangerously-action-data]}]
                (let [this-mode (second (:mode app))]
                  (html
                    [:div
                     [:h2.ui.violet.header
                      [:i.setting.icon]
                      [:div.content
                       "Job"
                       [:div.sub.header "Edit and execute a job."]]]
                     (case this-mode
                       :new
                       (om/build job-new-view (get-in app [:jobs :results])
                                 {:state {:mode (:mode app)}
                                  :opts {:jobs-channel jobs-channel}})

                       :detail
                       (if (:jobs app)
                         (let [idx (->> (get-in app [:jobs :results])
                                        (keep-indexed #(if (= (:job/name %2) (:job-name app)) %1))
                                        first)]
                           (om/build job-detail-view (get-in app [:jobs :results idx])
                                     {:opts {:jobs-channel jobs-channel}
                                      :state {:mode (:mode app)}}))
                         [:img {:src "/img/loader.gif"}])



                       ;; default
                       [:div
                        [:div.ui.top.attached.tabular.menu
                         [:a (merge {:class "item"
                                     :href "#/"}
                                    (when (= this-mode :list) {:class "item active"}))
                          [:i.list.icon] "list"]
                         [:a (merge {:class "item"
                                     :href "#/jobs/timeline"}
                                    (when (= this-mode :timeline) {:class "item active"}))
                          [:i.wait.icon] "timeline"]]
                        [:div.ui.bottom.attached.active.tab.segment
                         [:div#tab-content
                          (if (nil? (:jobs app))
                            [:img {:src "/img/loader.gif"}]
                            (om/build (case this-mode
                                        :timeline timeline-view
                                        ;; default
                                        job-list-view)
                                      app {:init-state {:jobs-view-channel jobs-channel}}))]]
                        (when executing-job
                          (om/build job-execution-dialog executing-job {:init-state {:jobs-view-channel jobs-channel}}))])
                     (when dangerously-action-data
                       (om/build dangerously-action-dialog nil
                                 {:opts (assoc dangerously-action-data
                                          :ok-handler (fn []
                                                        (om/set-state! owner :dangerously-action-data nil)
                                                        ((:ok-handler dangerously-action-data)))
                                          :cancel-handler (fn [] (om/set-state! owner :dangerously-action-data nil))
                                          :delete-type "job")}))]))))

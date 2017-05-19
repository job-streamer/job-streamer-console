(ns job-streamer.console.components.jobs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout close!]]
            (job-streamer.console.format :as fmt)
            (job-streamer.console.api :as api))
  (:use (job-streamer.console.components.timeline :only [timeline-view])
        (job-streamer.console.components.job-detail :only [job-detail-view])
        (job-streamer.console.components.execution :only [execution-view])
        (job-streamer.console.components.pagination :only [pagination-view])
        (job-streamer.console.components.dialog :only[dangerously-action-dialog])
        [job-streamer.console.search :only [search-jobs parse-sort-order toggle-sort-order]]))

(enable-console-print!)
(def app-name "default")

(defn execute-job [job-name parameters channel message-channel]
  (api/request (str "/" app-name "/job/" job-name "/executions") :POST
               parameters
               {:handler (fn [response]
                           (put! channel [:close-dialog nil]))
                :error-handler (fn [response]
                                 (when message-channel
                                   (put! message-channel {:type "error" :body (:message response)}))
                                 (put! channel [:close-dialog nil]))
                :forbidden-handler (fn [response]
                                     (when message-channel
                                       (put! message-channel {:type "error" :body "You are unauthorized to execute job."}))
                                     (put! channel [:close-dialog nil]))}))

(defn stop-job [job message-channel]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/stop")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution
                                         [:job-execution/batch-status :db/ident]
                                         :batch-status/stopping))
                  :error-handler (fn [response]
                                   (when message-channel
                                     (put! message-channel {:type "error" :body (:message response)})))
                  :forbidden-handler (fn [response]
                                       (when message-channel
                                         (put! message-channel {:type "error" :body "You are unauthorized to stop execution job."})))})))

(defn abandon-job [job message-channel]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/abandon")
                 :PUT
                 {:handler (fn [response]
                             (om/update! latest-execution
                                         [:job-execution/batch-status :db/ident]
                                         :batch-status/abandoned))
                  :error-handler (fn [response]
                                   (when message-channel
                                     (put! message-channel {:type "error" :body (:message response)})))
                  :forbidden-handler (fn [response]
                                       (when message-channel
                                         (put! message-channel {:type "error" :body "You are unauthorized to abandon execution job."})))})))

(defn restart-job [job parameters channel message-channel]
  (when-let [latest-execution (:job/latest-execution job)]
    (api/request (str "/" app-name
                      "/job/" (:job/name job)
                      "/execution/" (:db/id latest-execution) "/restart")
                 :PUT
                 parameters
                 {:handler (fn [response]
                             (put! channel [:close-dialog nil]))
                  :error-handler (fn [response]
                                   (when message-channel
                                     (put! message-channel {:type "error" :body (:message response)}))
                                   (put! channel [:close-dialog nil]))
                  :forbidden-handler (fn [response]
                                       (when message-channel
                                         (put! message-channel {:type "error" :body "You are unauthorized to restart execution job."}))
                                       (put! channel [:close-dialog nil]))})))

(defn search-execution [latest-execution job-name execution-id]
  (api/request (str "/" app-name "/job/" job-name "/execution/" execution-id)
               {:handler (fn [response]
                           (let [steps (:job-execution/step-executions response)]
                             (om/transact! latest-execution
                                           #(assoc % :job-execution/step-executions steps))))}))

(defcomponent job-execution-dialog [[type {:keys [job backto]}] owner opts]
  (init-state [_]
    {:params {}})
  (render-state [_ {:keys [jobs-view-channel params message-channel]}]
    (html
     [:div.ui.dimmer.modals.page.transition.visible.active
      [:div.ui.modal.scrolling.transition.visible.active
       [:i.close.icon {:on-click (fn [e]
                                   (when backto (set! (.-href js/location) backto))
                                   (put! jobs-view-channel [:close-dialog nil]))}]
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
                        :on-change (fn [e]
                                     (om/update-state! owner :params
                                                             #(assoc % (keyword param-name) (.. e -target -value))))}]])]]
          [:div (case type
                  :execute "Execute now?"
                  :restart "Restart now?")])]
       [:div.actions
        [:div.ui.two.column.grid
         [:div.left.aligned.column
          [:button.ui.black.button
           {:type "button"
            :on-click (fn [e]
                        (when backto (set! (.-href js/location) backto))
                        (put! jobs-view-channel [:close-dialog nil]))} "Cancel"]]
         [:div.right.aligned.column
          [:button.ui.positive.button
           {:type "button"
            :on-click (fn [e]
                        (case type
                          :execute (execute-job (:job/name job) params jobs-view-channel message-channel)
                          :restart (restart-job job params jobs-view-channel message-channel)))}
           (case type
             :execute "Execute!"
             :restart "Restart!")]]]]]])))

(defcomponent job-list-view [app owner {:keys [message-channel]}]
  (init-state [_] {:now (js/Date.)
                   :per 20})
  (will-mount [_]
    (let [ch (chan)]
      (om/set-state! owner :now-timer ch)
      (go-loop []
        (when-let [_ (<! ch)]
          (<! (timeout 1000))
          (om/set-state! owner :now (js/Date.))
          (put! ch :continue)
          (recur)))
      (put! ch :start)))

  (did-mount [_]
    (let [ch (chan)]
      (om/set-state! owner :refresh-timer ch)
      (go-loop []
        (when-let [_ (<! ch)]
          (<! (timeout 5000))
          (if (->> (get-in @app [:jobs :results])
                   (filter #(#{:batch-status/started :batch-status/starting
                               :batch-status/undispatched :batch-status/queued
                               :batch-status/unrestarted :batch-status/stopping}
                             (get-in % [:job/latest-execution :job-execution/batch-status :db/ident])))
                   not-empty)
            (let [page (om/get-state owner :page)
                  per  (om/get-state owner :per)]
              (search-jobs app {:q (:query @app) :sort-by (-> @app :job-sort-order parse-sort-order) :offset (inc (* (dec page) per)) :limit per} message-channel)
              {:page page}))
          (put! ch :continue)
          (recur)))
      (put! ch :start)))

  (will-unmount [_]
    (when-let [now-timer (om/get-state owner :now-timer)]
      (close! now-timer))
    (when-let [refresh-timer (om/get-state owner :refresh-timer)]
      (close! refresh-timer)))

  (render-state [_ {:keys [jobs-view-channel now page per]}]
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
                              (let [w (js/window.open (str "/" app-name "/jobs/new") "New" "width=1200,height=800")]
                                (js/setTimeout (fn [] (.addEventListener w "unload" (fn [] (js/setTimeout (fn [] (put! jobs-view-channel [:refresh-jobs true]))) 10))) 10)))}
                 [:i.plus.icon] "Create the first job"]]]]]]]
       [:div.ui.grid
        [:div.ui.two.column.row
         [:div.column
          [:button.ui.basic.green.button
           {:type "button"
            :on-click (fn [e]
                        (let [w (js/window.open (str "/" app-name "/jobs/new") "New" "width=1200,height=800")]
                          (js/setTimeout (fn [] (.addEventListener w "unload" (fn [] (js/setTimeout (fn [] (put! jobs-view-channel [:refresh-jobs true]))) 10))) 10)))}
           [:i.plus.icon] "New"]]
         [:div.ui.right.aligned.column
          [:button.ui.circular.basic.orange.icon.button
           {:type "button"
            :on-click (fn [e]
                        (search-jobs app {:q (:query app) :sort-by (-> app :job-sort-order parse-sort-order) :offset (inc (* (dec page) per)) :limit per} message-channel))}
           [:i.refresh.icon]]]]
        [:div.row
         [:div.column
          [:table.ui.table.job-list
           [:thead
            [:tr
             [:th.can-sort
              {:rowSpan 2
               :on-click (fn [e]
                           (search-jobs app {:q (:query app)
                                             :sort-by (-> app :job-sort-order (toggle-sort-order :name) parse-sort-order)
                                             :offset (inc (* (dec page) per))
                                             :limit per} message-channel)
                           (om/transact! app :job-sort-order #(toggle-sort-order % :name)))}
              "Job name"
              [:i.sort.icon
                          {:class (when-let [sort-order (get-in app [:job-sort-order :name])]
                                    (if (= sort-order :asc)
                                      "ascending"
                                      "descending"))}]]
             [:th {:colSpan 3} "Last execution"]
             [:th "Next execution"]
             [:th {:rowSpan 2} "Operations"]]
            [:tr
             [:th.can-sort
              {
                :on-click (fn [e]
                           (search-jobs app {:q (:query app)
                                             :sort-by (-> app :job-sort-order (toggle-sort-order :last-execution-started) parse-sort-order)
                                             :offset (inc (* (dec page) per))
                                             :limit per} message-channel)
                           (om/transact! app :job-sort-order #(toggle-sort-order % :last-execution-started)))}
              "Started at"
              [:i.sort.icon
                          {:class (when-let [sort-order (get-in app [:job-sort-order :last-execution-started])]
                                    (if (= sort-order :asc)
                                      "ascending"
                                      "descending"))}]]
             [:th.can-sort
              {:on-click (fn [e]
                           (search-jobs app {:q (:query app)
                                             :sort-by (-> app :job-sort-order (toggle-sort-order :last-execution-duration) parse-sort-order)
                                             :offset (inc (* (dec page) per))
                                             :with "execution"
                                             :limit per} message-channel)
                           (om/transact! app :job-sort-order #(toggle-sort-order % :last-execution-duration)))}
              "Duration"
              [:i.sort.icon
                          {:class (when-let [sort-order (get-in app [:job-sort-order :last-execution-duration])]
                                    (if (= sort-order :asc)
                                      "ascending"
                                      "descending"))}]]
             [:th.can-sort
              {:on-click (fn [e]
                           (search-jobs app {:q (:query app)
                                             :sort-by (-> app :job-sort-order (toggle-sort-order :last-execution-status) parse-sort-order)
                                             :offset (inc (* (dec page) per))
                                             :limit per} message-channel)
                           (om/transact! app :job-sort-order #(toggle-sort-order % :last-execution-status)))}
              "Status"
              [:i.sort.icon
                          {:class (when-let [sort-order (get-in app [:job-sort-order :last-execution-status])]
                                    (if (= sort-order :asc)
                                      "ascending"
                                      "descending"))}]]
             [:th.can-sort
              {:on-click (fn [e]
                           (search-jobs app {:q (:query app)
                                             :sort-by (-> app :job-sort-order (toggle-sort-order :next-execution-start) parse-sort-order)
                                             :offset (inc (* (dec page) per))
                                             :limit per} message-channel)
                           (om/transact! app :job-sort-order #(toggle-sort-order % :next-execution-start)))}
              "Start"
              [:i.sort.icon
                          {:class (when-let [sort-order (get-in app [:job-sort-order :next-execution-start])]
                                    (if (= sort-order :asc)
                                      "ascending"
                                      "descending"))}]]]]
           [:tbody
            (apply concat
                   (for [{job-name :job/name :as job} (get-in app [:jobs :results])]
                     [[:tr {:key (str "tr-1-" job-name)}
                       [:td.job-name
                        [:div
                         [:a {:href (str "#/job/" job-name)
                              :title job-name} job-name]]]
                       (if-let [latest-execution (:job/latest-execution job)]
                         (if (#{:batch-status/undispatched :batch-status/unrestarted :batch-status/queued}
                              (get-in latest-execution [:job-execution/batch-status :db/ident]))
                           [:td.center.aligned {:colSpan 3} "Wait for an execution..."]
                           (let [start (:job-execution/start-time latest-execution)
                                 end (or (:job-execution/end-time  latest-execution) now)]
                             (list
                              [:td.log-link {:key "td-1"}
                               (when start
                                 (let [id (:db/id latest-execution)]
                                   [:a {:on-click (fn [_]
                                                    (if (not-empty (:job-execution/step-executions latest-execution))
                                                      (om/update! latest-execution :job-execution/step-executions nil)
                                                      (search-execution latest-execution job-name id)))}
                                    (fmt/date-medium start)]))]
                              [:td {:key "td-2"}
                               (let [duration (fmt/duration-between start end)]
                                 (if (= duration 0) "-" duration)) ]
                              (let [status (name (get-in latest-execution [:job-execution/batch-status :db/ident]))]
                                [:td {:key "td-3"
                                      :class (condp = status
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
                                (#{:batch-status/undispatched :batch-status/unrestarted :batch-status/queued :batch-status/started} status)
                                [:div.ui.fade.reveal
                                 [:button.ui.circular.orange.icon.button.visible.content
                                  {:on-click (fn [_]
                                               (if (#{:batch-status/started} status)
                                                 (stop-job job message-channel)
                                                 (abandon-job job message-channel)))}
                                  [:i.setting.loading.icon]]
                                 [:button.ui.circular.red.icon.basic.button.hidden.content
                                  (if (#{:batch-status/started} status)
                                    [:i.pause.icon]
                                    [:i.stop.icon])]]

                                (#{:batch-status/stopped :batch-status/failed} status)
                                (when (not (false? (:job/restartable? job)))
                                [:div
                                 [:button.ui.circular.red.icon.inverted.button
                                  {:title "abandon"
                                   :on-click (fn [_]
                                               (:job-util/abandon-job job message-channel))}
                                  [:i.stop.icon]]
                                 [:button.ui.circular.yellow.icon.inverted.button
                                  {:title "restart"
                                   :on-click (fn [_]
                                               (api/request (str "/" app-name "/job/" job-name)
                                                            {:handler (fn [job]
                                                                        (put! jobs-view-channel [:restart-dialog {:job job}]))}))}
                                  [:i.play.icon]]])

                                (#{:batch-status/starting  :batch-status/stopping} status)
                                [:div]

                                :else
                                [:button.ui.circular.icon.green.inverted.button
                                 {:title "start"
                                  :on-click (fn [_]
                                              (api/request (str "/" app-name "/job/" job-name)
                                                           {:handler (fn [job]
                                                                       (put! jobs-view-channel [:execute-dialog {:job job}]))}))}
                                 [:i.play.icon]]))]]
                      (when-let [step-executions (not-empty (get-in job [:job/latest-execution :job-execution/step-executions]))]
                        [:tr {:key (str "tr-2-" job-name)}
                         [:td {:colSpan 8}
                          (om/build execution-view step-executions {:react-key "job-execution"})]])]))]]]]
        [:div.row
         [:div.column
          (om/build pagination-view {:hits (get-in app [:jobs :hits])
                                     :page page
                                     :per per
                                     :jobs-view-channel jobs-view-channel}
                    {:init-state {:link-fn (fn [pn]
                                             (search-jobs app {:q (:query app)
                                                               :sort-by (-> app :job-sort-order parse-sort-order)
                                                               :offset (inc (* (dec pn) per))
                                                               :limit per} message-channel))}
                     :react-key "job-pagination"})]]]))))


(defcomponent jobs-view [app owner {:keys [header-channel jobs-channel message-channel]}]
  (init-state [_]
    {:dangerously-action-data nil
     :page 1})
  (will-mount [_]
    (search-jobs app {:q (:query @app) :sort-by (-> @app :job-sort-order parse-sort-order) :p 1} message-channel)
    (go-loop []
      (let [[cmd msg] (<! jobs-channel)]
        (try
          (case cmd
            :execute-dialog  (om/set-state! owner :executing-job [:execute msg])
            :restart-dialog  (om/set-state! owner :executing-job [:restart msg])
            :close-dialog (do (om/set-state! owner :executing-job nil)
                              (search-jobs app {:q (:query @app) :sort-by (-> @app :job-sort-order parse-sort-order) } message-channel))
            :refresh-jobs (do (search-jobs app {:q (:query @app) :sort-by (-> @app :job-sort-order parse-sort-order) } message-channel)
                            (put! header-channel [:refresh-stats true]))
            :delete-job (do
                          (fn [results]
                            (remove #(= % msg) results))
                          (put! jobs-channel [:refresh-jobs true]))
            :change-page (om/set-state! owner :page msg)
            :open-dangerously-dialog (om/set-state! owner :dangerously-action-data msg))
          (catch js/Error e))
        (when (not= cmd :close-chan-listener)
          (recur)))))
  (render-state [_ {:keys [executing-job dangerously-action-data page]}]
    (let [this-mode (second (:mode app))]
      (html
       [:div
        [:h2.ui.violet.header
         [:i.setting.icon]
         [:div.content
          "Job"
          [:div.sub.header "Edit and execute a job."]]]
        (case this-mode
          :detail
          (if (:jobs app)
            (let [idx (->> (get-in app [:jobs :results])
                           (keep-indexed #(if (= (:job/name %2) (:job-name app)) %1))
                           first)]
              (om/build job-detail-view (get-in app [:jobs :results idx])
                        {:opts {:jobs-channel jobs-channel}
                         :state {:mode (:mode app)}
                         :react-key "job-detail"}))
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
                         app {:init-state {:jobs-view-channel jobs-channel
                                           :message-channel message-channel}
                              :state {:page page}
                              :react-key "job-mode"}))]]
           (when executing-job
             (om/build job-execution-dialog executing-job {:init-state {:jobs-view-channel jobs-channel
                                                                        :message-channel message-channel}
                                                           :state {:page page}
                                                           :react-key "job-execution-dialog"}))])
        (when dangerously-action-data
          (om/build dangerously-action-dialog nil
                    {:opts (assoc dangerously-action-data
                                  :ok-handler (fn []
                                                (om/set-state! owner :dangerously-action-data nil)
                                                ((:ok-handler dangerously-action-data)))
                                  :cancel-handler (fn [] (om/set-state! owner :dangerously-action-data nil))
                                  :delete-type "job")
                     :react-key "job-dialog"}))])))
  (will-unmount [_]
    (put! jobs-channel [:close-chan-listener true])))

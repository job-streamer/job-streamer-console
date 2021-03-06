(ns job-streamer.console.components.job-detail
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan close! pub sub unsub-all]]
            [clojure.browser.net :as net]
            [clojure.string :as string]
            [goog.string :as gstring]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [goog.Uri.QueryData :as query-data]
            [job-streamer.console.api :as api]
            [job-streamer.console.validators :as cv]
            [job-streamer.console.format :as fmt]
            [job-streamer.console.utils.job-util :as job-util])
  (:use [cljs.reader :only [read-string]]
        [clojure.walk :only [postwalk]]
        [job-streamer.console.components.job-settings :only [job-settings-view]]
        [job-streamer.console.components.pagination :only [pagination-view]]
        [job-streamer.console.components.execution :only [execution-view]])
  (:import [goog.ui.tree TreeControl]
           [goog Uri]))

(enable-console-print!)

;; Now, app-name is static.
(def app-name "default")

(defn search-executions [job-name query cb]
  (let [uri (.. (Uri. (str "/" app-name "/job/" job-name "/executions"))
                (setQueryData (query-data/createFromMap (clj->js query))))]
    (api/request (.toString uri)
                 {:handler cb})))

(defn search-execution [owner job-name execution-id idx]
  (api/request (str "/" app-name "/job/" job-name "/execution/" execution-id)
               {:handler (fn [response]
                           (let [steps (:job-execution/step-executions response)]
                             (om/update-state! owner [:executions :results idx]
                                               #(assoc % :job-execution/step-executions steps))))}))

(defn delete-executions [job-name owner error-ch]
  (api/request (str "/" app-name "/job/" job-name "/executions") :DELETE
               {:handler (fn [response]
                           (om/set-state! owner :executions []))
                :forbidden-handler (fn [response]
                                     (put! error-ch {:message "You are unauthorized to delete executions."}))
                :error-handler (fn [response]
                                 (put! error-ch response))}))

(defn schedule-job [job schedule refresh-job-ch scheduling-ch error-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule") :POST
               schedule
               {:handler (fn [response]
                           (put! refresh-job-ch (:job/name job))
                           (put! scheduling-ch false))
                :forbidden-handler (fn [response]
                                     (put! error-ch {:message "You are unauthorized to change schedule."}))
                :error-handler (fn [response]
                                 (put! error-ch response))}))

(defn pause-schedule [job owner success-ch error-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule/pause") :PUT
               {:handler (fn [response]
                           (put! success-ch (:job/name job)))
                :forbidden-handler (fn [response]
                                     (put! error-ch {:message "You are unauthorized to pause schedule."}))}))

(defn resume-schedule [job owner success-ch error-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule/resume") :PUT
               {:handler (fn [response]
                           (put! success-ch (:job/name job)))
                :forbidden-handler (fn [response]
                                     (put! error-ch {:message "You are unauthorized to redume schedule."}))}))

(defn drop-schedule [job owner success-ch error-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule") :DELETE
               {:handler (fn [response]
                           (put! success-ch (:job/name job)))
                :forbidden-handler (fn [response]
                                     (put! error-ch {:message "You are unauthorized to drop schedule."}))}))

(defn status-color [status]
  (case status
    :batch-status/completed "green"
    :batch-status/failed "red"
    ""))

(defn to-cron-expression-daily [hour]
  (if (not-empty hour)
    (str "0 0 " hour " * * ?")
    ""))

(defn to-cron-expression-weekly [hour day-of-weeks]
  (if (and (not-empty hour) (not-empty day-of-weeks))
    (letfn [(get-week-day-sort-key [day-of-week] (get {"Sun" 0 "Mon" 1 "Tue" 2 "Wed" 3 "Thu" 4 "Fri" 5 "Sat" 6} day-of-week))]
      (str "0 0 " hour " ? * " (->> day-of-weeks (sort-by get-week-day-sort-key) (clojure.string/join ","))))
    ""))

(defn to-cron-expression-monthly[hour day]
  (if (and (not-empty hour) (not-empty day))
    (str "0 0 " hour " " day " * ?")
  ""))

(defn to-cron-expression [owner]
  (let [{:keys [schedule scheduling-type scheduling-hour scheduling-date scheduling-day-of-weeks]} (om/get-state owner)]
    (condp = scheduling-type
      "Daily"
      (to-cron-expression-daily scheduling-hour)
      "Weekly"
      (to-cron-expression-weekly nil [])
      "Monthly"
      (to-cron-expression-monthly scheduling-hour scheduling-date)
      (:schedule/cron-notation schedule))))

;;;
;;; Om view components
;;;

(def breadcrumb-elements
  {:jobs {:name "Jobs" :href "#/"}
   :jobs.new {:name "New" :href "#/jobs/new"}
   :jobs.detail {:name "Job: %s" :href "#/job/%s"}
   :jobs.detail.current.edit {:name "Edit" :href "#/job/%s/edit"}
   :jobs.detail.history {:name "History" :href "#/job/%s/history"}
   :jobs.detail.settings {:name "Settings" :href "#/job/%s/settings"}})

(defcomponent breadcrumb-view [mode owner]
  (render-state [_ {:keys [job-name]}]
                (html
                 [:div.ui.breadcrumb
                  (drop-last
                   (interleave
                    (loop [i 1 items []]
                      (if (<= i (count mode))
                        (recur (inc i)
                               (conj items (if-let [item (get breadcrumb-elements
                                                              (->> mode
                                                                   (take i)
                                                                   (map name)
                                                                   (string/join ".")
                                                                   keyword))]
                                             [:div.section {:key (str "div-" i)}
                                              [:a {:href (gstring/format (:href item) job-name)
                                                   :title job-name}
                                               (gstring/format (:name item) job-name)]])))
                        (let [res (keep identity items)]
                          (conj (vec (drop-last res))
                                (into [:div.section.active {:key "div-active"}] (-> res last (get-in [2 2])))))))
                    (map #(vec [:i.right.chevron.icon.divider {:key (str "i-" %)}]) (range))))])))

(defcomponent job-history-view [job owner opts]
  (init-state [_]
              {:page 1
               :per 20
               :error-ch (chan)
               :has-error false})
  (will-mount [_]
              (go
                (let [{message :message} (<! (om/get-state owner :error-ch))]
                  (om/set-state! owner :has-error message)))
              (search-executions (:job/name @job) {:offset 1 :limit (om/get-state owner :per)}
                                 (fn [response]
                                   (om/set-state! owner :executions response))))
  (render-state [_ {:keys [executions page per error-ch has-error roles]}]
                (html
                 [:div.ui.grid
                  (when has-error
                    [:div.row
                   [:div.center.aligned.column
                     [:div.ui.error.message
                      [:p has-error]]]])
                  (if (some #(= :admin %) roles)
                    [:div.row
                     [:div.right.aligned.column
                      [:button.ui.right.red.button
                       {:type "button"
                        :on-click (fn [e]
                                      (delete-executions (:job/name job) owner error-ch))}
                       "Delete all"]]])
                  [:div.row
                   [:div.column
                    [:table.ui.compact.table
                     [:thead
                      [:tr
                       [:th "#"]
                       [:th "Agent"]
                       [:th "Started at"]
                       [:th "Duration"]
                       [:th "Status"]]]
                     [:tbody
                      (map-indexed
                       (fn [idx {:keys [job-execution/start-time job-execution/end-time] :as execution}]
                         (list
                          [:tr {:key "tr-1"}
                           [:td.log-link
                            [:a {:on-click
                                 (fn [_]
                                   (if (not-empty (:job-execution/step-executions execution))
                                     (om/set-state! owner [:executions :results idx :job-execution/step-executions] nil)
                                     (search-execution owner (:job/name @job) (:db/id execution) idx)))}
                             (:db/id execution)]]
                           [:td (get-in execution [:job-execution/agent :agent/name] "Unknown")]
                           [:td (fmt/date-medium (:job-execution/start-time execution))]
                           [:td (let [duration (fmt/duration-between
                                                (:job-execution/start-time execution)
                                                (:job-execution/end-time execution))]
                                  (if (= duration 0) "-" duration))]
                           (let [status (name (get-in execution [:job-execution/batch-status :db/ident]))]
                             [:td {:class (condp = status
                                            "completed" "positive"
                                            "failed" "error"
                                            "abadoned" "warning"
                                            "stopped"  "warning"
                                            "")}
                              status])]
                          (when-let [step-executions (not-empty (:job-execution/step-executions execution))]
                            [:tr {:key "tr-1"}
                             [:td {:colSpan 5}
                              (om/build execution-view step-executions {:react-key "job-histry-execution"})]])))
                       (:results executions))]]]]

                  [:div.row
                   [:div.column
                    (om/build pagination-view {:hits (:hits executions)
                                               :page page
                                               :per per}
                              {:init-state {:link-fn (fn [pn]
                                                       (om/set-state! owner :page pn)
                                                       (search-executions (:job/name @job) {:offset (inc (* (dec pn) per)) :limit per}
                                                                          (fn [executions]
                                                                            (om/set-state! owner :executions executions))))}
                               :react-key "job-histry-pagination"})]]])))


(defn weekday-checkbox-view [owner label]
  (let [day-of-weeks (om/get-state owner :scheduling-day-of-weeks)]
    [:div.ui.checkbox {}
      [:div.field {}
        [:input
          {:type "checkbox"
           :checked (some #(= label %) day-of-weeks)
           :on-change (fn [e]
                        (let [hour (om/get-state owner :scheduling-hour)
                              day-of-weeks (if (.. e -target -checked)
                                             (-> day-of-weeks (conj label) set vec)
                                             (filter #(not= label %) day-of-weeks))]
                          (om/set-state! owner :scheduling-day-of-weeks day-of-weeks)
                          (om/set-state! owner [:schedule :schedule/cron-notation]
                                         (to-cron-expression-weekly hour day-of-weeks))))}]
        [:label {} label]]]))

(defcomponent scheduling-view [job owner]
  (init-state [_]
    (let [cron-expressions (some-> job
                                   (get-in [:job/schedule :schedule/cron-notation])
                                   (string/split #"\s"))
          scheduling-type (if cron-expressions
                            (cond (and (= (nth cron-expressions 0) "0")
                                       (= (nth cron-expressions 1) "0")
                                       (= (nth cron-expressions 3) "*")
                                       (= (nth cron-expressions 4) "*")
                                       (= (nth cron-expressions 5) "?")) "Daily"
                                  (and (= (nth cron-expressions 0) "0")
                                       (= (nth cron-expressions 1) "0")
                                       (= (nth cron-expressions 3) "?")
                                       (= (nth cron-expressions 4) "*")
                                       (not= (nth cron-expressions 5) "?")) "Weekly"
                                  (and (= (nth cron-expressions 0) "0")
                                       (= (nth cron-expressions 1) "0")
                                       (not= (nth cron-expressions 3) "*")
                                       (= (nth cron-expressions 4) "*")
                                       (= (nth cron-expressions 5) "?")) "Monthly") "")
          day-of-weeks (if (= scheduling-type "Weekly")
                         (some-> cron-expressions
                                 (nth 5)
                                 (string/split #","))
                         [])]
      {:error-ch (chan)
       :has-error false
       :schedule (:job/schedule job)
       :scheduling-type scheduling-type
       :scheduling-hour (some-> cron-expressions (nth 2))
       :scheduling-date (if (= scheduling-type "Monthly") (some-> cron-expressions (nth 3)) "")
       :scheduling-day-of-weeks day-of-weeks}))

  (will-mount [_]
    (go
      (let [{message :message} (<! (om/get-state owner :error-ch))]
        (om/set-state! owner :has-error message)))
    (api/request "/calendars" :GET
                 {:handler (fn [response]
                             (om/set-state! owner :calendars response))}))

  (render-state [_ {:keys [schedule scheduling-ch calendars refresh-job-ch error-ch has-error scheduling-type scheduling-hour scheduling-date scheduling-day-of-weeks] :as state}]
    (html
     [:form.ui.form
      (merge {:on-submit (fn [e]
                           (.preventDefault e)
                           (schedule-job job
                                         schedule
                                         refresh-job-ch scheduling-ch error-ch))}
             (when has-error {:class "error"}))
      (when has-error
        [:div.ui.error.message
         [:p has-error]])
      [:div.fields
         [:div.field (when has-error {:class "error"})
          [:label "Easy Scheduling"]
          [:select {:value (or scheduling-type "")
                    :on-change (fn [e]
                                 (let [value (.. e -target -value)]
                                   (om/set-state! owner :scheduling-type value)
                                   (om/set-state! owner [:schedule :schedule/cron-notation] (to-cron-expression owner))))}
           (for [v ["" "Daily" "Weekly" "Monthly"]]
             [:option {:value v :key v} v])]
          (when (= scheduling-type "Daily")
            [:div
             "Fire at"
             [:input
              {:type "text"
               :value (or scheduling-hour "")
               :on-change (fn [e]
                            (let [hour (.. e -target -value)]
                              (om/set-state! owner :scheduling-hour hour)
                              (om/set-state! owner [:schedule :schedule/cron-notation] (to-cron-expression-daily hour))))}]
             "o'clock every day"])
          (when (= scheduling-type "Weekly")
            [:div
             "Fire at"
             [:input
              {:type "text"
               :value (or scheduling-hour "")
               :on-change (fn [e]
                            (let [hour (.. e -target -value)
                                  day-of-weeks (om/get-state owner :scheduling-day-of-weeks)]
                              (om/set-state! owner :scheduling-hour hour)
                              (om/set-state! owner [:schedule :schedule/cron-notation] (to-cron-expression-weekly hour day-of-weeks))))}]
             "o'clock at every"
               [:div.inline.fields {}
                (weekday-checkbox-view owner "Sun")
                (weekday-checkbox-view owner "Mon")
                (weekday-checkbox-view owner "Tue")
                (weekday-checkbox-view owner "Wed")]
               [:div.inline.fields {}
                (weekday-checkbox-view owner "Thu")
                (weekday-checkbox-view owner "Fri")
                (weekday-checkbox-view owner "Sat")]])
          (when (= scheduling-type "Monthly")
            [:div
             "Fire at"
             [:input
              {:type "text"
               :value (or scheduling-hour "")
               :on-change (fn [e]
                            (let [hour (.. e -target -value)
                                  date (om/get-state owner :scheduling-date)]
                              (om/set-state! owner :scheduling-hour hour)
                              (om/set-state! owner [:schedule :schedule/cron-notation] (to-cron-expression-monthly hour date))))}]
             "o'clock every"
             [:input
              {:type "text"
               :value (or scheduling-date "")
               :on-change (fn [e]
                            (let [hour (om/get-state owner :scheduling-hour)
                                  date (.. e -target -value)]
                              (om/set-state! owner :scheduling-date date)
                              (om/set-state! owner [:schedule :schedule/cron-notation] (to-cron-expression-monthly hour date))))}]])
          [:label "Quartz format"]
          [:input {:type "text" :placeholder "Quartz format"
                   :value (or (:schedule/cron-notation schedule) "")
                   :on-change (fn [e]
                                (let [value (.. e -target -value)]
                                  (om/set-state! owner [:schedule :schedule/cron-notation] value)))}]]
       (when calendars
         [:div.field
          [:label "Calendar"]
          [:select {:value (or (get-in schedule [:schedule/calendar :calendar/name]) "")
                    :on-change (fn [e]
                                 (let [value (.. e -target -value)]
                                   (om/set-state! owner [:schedule :schedule/calendar :calendar/name] value)))}
           [:option {:value "" :key ""} ""]
           (for [{:keys [calendar/name]} calendars]
             [:option {:value name :key name} name])]
          (when-not (empty? (om/get-state owner [:schedule :schedule/calendar :calendar/name]))
            [:div
             [:div
              [:label "Substitution"]]
             [:div.ui.fitted.toggle.checkbox {:id "substitution-div"}
              [:input (merge {:id "substitution"
                              :type "checkbox"
                              :on-change (fn [e]
                                           (let [value (.. e -target -checked)]
                                             (om/set-state! owner [:schedule :schedule/substitution?] value)))}
                             (when (:schedule/substitution? schedule)
                               {:checked "checked"}))]
              [:label]]])])]
      [:div.ui.buttons
       [:button.ui.button
        {:type "button"
         :on-click (fn [e]
                     (put! scheduling-ch false))}
        "Cancel"]
       [:div.or]
       [:button.ui.positive.button {:type "submit"} "Save"]]])))

(defcomponent next-execution-view [job owner]
  (init-state [_]
    {:scheduling-ch (chan)
     :has-error false
     :error-ch (chan)
     :scheduling? false})

  (will-mount [_]
    (let [ch (chan)]
      (om/set-state! owner :scheduling-monitor ch)
      (go-loop []
        (when-let [_ (<! ch)]
          (om/set-state! owner :scheduling? (<! (om/get-state owner :scheduling-ch)))
          (put! ch :continue)
          (recur)))
      (put! ch :start))
    (go
      (let [{message :message} (<! (om/get-state owner :error-ch))]
        (om/set-state! owner :has-error message))))

  (will-unmount [_]
    (when-let [scheduling-monitor (om/get-state owner :scheduling-monitor)]
      (close! scheduling-monitor)))

  (render-state [_ {:keys [refresh-job-ch scheduling-ch scheduling? error-ch has-error roles]}]
    (html
     [:div.ui.raised.segment (when has-error {:class "error"})
      [:h3.ui.header "Next"]
      (when has-error
        [:div.ui.error.message
         [:p has-error]])
      (if scheduling?
        (om/build scheduling-view job
                  {:init-state {:scheduling-ch scheduling-ch
                                :refresh-job-ch refresh-job-ch}
                   :react-key "job-detail-scheduling"})
        (if-let [schedule (:job/schedule job)]
          (let [exe (:job/next-execution job)]
            [:div
             [:div.ui.list
              (if exe
                [:div.item
                 [:i.wait.icon]
                 [:div.content
                  [:div.description (fmt/date-medium (:job-execution/start-time exe))]]]
                [:div.item
                 [:div.content
                  [:div.header "Pausing"]
                  [:div.description (:schedule/cron-notation schedule)]]])]
             (if (some #(or (= :admin %) (= :operator %)) roles)
               [:div.ui.labeled.icon.compact.menu
                (if exe
                  [:a.item {:on-click (fn [e]
                                          (pause-schedule job owner refresh-job-ch error-ch))}
                   [:i.pause.icon] "Pause"]
                  [:a.item {:on-click (fn [e]
                                          (resume-schedule job owner refresh-job-ch error-ch))}
                   [:i.play.icon] "Resume"])
                [:a.item {:on-click (fn [e]
                                        (drop-schedule job owner refresh-job-ch error-ch))}
                 [:i.remove.icon] "Drop"]
                [:a.item {:on-click (fn [e]
                                        (om/set-state! owner :scheduling? true))}
                 [:i.calendar.icon] "Edit"]])])

          (if (some #(or (= :admin %) (= :operator %)) roles)
            [:div
             [:div.header "No schedule"]
             [:button.ui.primary.button
              {:on-click (fn [e]
                             (om/set-state! owner :scheduling? true))}
              "Schedule this job"]])))])))

(defcomponent job-structure-view [{:keys [job/name job/svg-notation] :as job-detail} owner]
  (render-state [_ {:keys [refresh-job-ch dimmed? roles]}]
    (html
     [:div.dimmable.image.dimmed
      {:on-mouse-enter (fn [e]
                         (om/set-state! owner :dimmed? true))
       :on-mouse-leave (fn [e]
                         (om/set-state! owner :dimmed? false))}
      (if (some #(= :admin %) roles)
        [:div.ui.inverted.dimmer (when dimmed? {:class "visible"})
         [:div.content
          [:div.center
           [:button.ui.primary.button
            {:type "button"
             :on-click (fn [e]
                           (let [w (js/window.open (str "/" app-name "/job/" name "/edit") name "width=1200,height=800")]
                                (js/setTimeout (fn [] (.addEventListener w "unload" (fn [] (js/setTimeout (fn [] (put! refresh-job-ch true))) 10))) 10)))}
            "Edit"]]]])
      [:div {:style {:height "200px"
                     :width "100%"
                     :background-repeat "no-repeat"
                     :background-size "contain"
                     :background-image (str "url(\"data:image/svg+xml;base64," (js/window.btoa svg-notation) "\")")}}]])))

(defcomponent current-job-view [job owner opts]
  (init-state [_]
              {:refresh-job-ch (chan)})
  (will-mount [_]
              (go-loop []
                       (let [_ (<! (om/get-state owner :refresh-job-ch))]
                         (api/request (str "/" app-name "/job/" (:job/name job))
                                      {:handler (fn [response]
                                                  (om/set-state! owner :job-detail response))})
                         (recur)))
              (put! (om/get-state owner :refresh-job-ch) true))
  (render-state [_ {:keys [job-detail refresh-job-ch mode roles]}]
                (let [this-mode (->> mode (drop 3) first)]
                  (html
                   (case this-mode
                     ;;default
                     [:div.ui.stackable.two.column.grid
                      [:div.column
                       [:div.ui.special.cards
                        [:div.job-detail.card
                         (when job-detail
                           (om/build job-structure-view job-detail {:init-state {:refresh-job-ch refresh-job-ch}
                                                                    :state {:roles roles}
                                                                    :react-key "job-structure"}))
                         [:div.content
                          [:div.header.name (:job/name job)]
                          [:div.description
                           [:div.ui.tiny.statistics
                            [:div.statistic
                             [:div.value (get-in job-detail [:job/stats :total])]
                             [:div.label "Total"]]
                            [:div.statistic
                             [:div.value (get-in job-detail [:job/stats :success])]
                             [:div.label "Success"]]
                            [:div.statistic
                             [:div.value (get-in job-detail [:job/stats :failure])]
                             [:div.label "Failed"]]
                            (if (some #(or (= :admin %) (= :operator %)) roles)
                              (job-util/job-execute-button-view job {:progress (fn [_]
                                                                                   (if (#{:batch-status/started} (get-in job [:job/latest-execution :job-execution/batch-status :db/ident]))
                                                                                     (job-util/stop-job job refresh-job-ch)
                                                                                     (job-util/abandon-job job refresh-job-ch)))
                                                                     :abandon (fn [_]
                                                                                  (job-util/abandon-job job refresh-job-ch))
                                                                     :restart (fn [_]
                                                                                  (put! (:jobs-channel opts) [:restart-dialog {:job job-detail :backto (.-href js/location)}])
                                                                                  (set! (.-href js/location) "#"))
                                                                     :start (fn  [_]
                                                                                 (put! (:jobs-channel opts) [:execute-dialog {:job job-detail :backto (.-href js/location)}])
                                                                                 (set! (.-href js/location) "#"))}))]
                           [:hr.ui.divider]
                           [:div.ui.tiny.horizontal.statistics
                            [:div.statistic
                             [:div.value (fmt/duration (get-in job-detail [:job/stats :average]))]
                             [:div.label "Average duration"]]]]]]]]
                      [:div.column
                       [:div.ui.raised.segment
                        [:h3.ui.header "Latest"]
                        (when-let [exe (:job/latest-execution job-detail)]
                          [:div.ui.list
                           [:div.item
                            (let [status (get-in exe [:job-execution/batch-status :db/ident])]
                              [:div.ui.huge.label {:class (status-color status)}
                               [:i.check.circle.icon] (name status)])]
                           [:div.item
                            [:i.calendar.outline.icon]
                            [:div.content
                             [:div.description (fmt/duration-between
                                                (:job-execution/start-time exe)
                                                (:job-execution/end-time exe))]]]
                           [:div.item
                            [:i.wait.icon]
                            [:div.content
                             [:div.description (fmt/date-medium (:job-execution/start-time exe))]]]
                           [:div.item
                            [:i.marker.icon]
                            [:div.content
                             [:div.description
                              [:a {:href (str "#/agent/" (get-in exe [:job-execution/agent :agent/instance-id]))}
                               (get-in exe [:job-execution/agent :agent/name])] ]]]])]
                       (om/build next-execution-view job-detail
                                 {:init-state {:refresh-job-ch refresh-job-ch}
                                  :state {:roles roles}
                                  :react-key "job-current-next-execution"})]])))))

(defcomponent job-detail-view [job owner opts]
  (render-state [_ {:keys [mode message breadcrumbs roles]}]
                (let [this-mode (->> mode (drop 2) first)]
                  (html
                   [:div
                    (om/build breadcrumb-view mode {:init-state {:job-name (:job/name job)}
                                                    :react-key "job-detail-breadcrumb"})
                    [:div.ui.top.attached.tabular.menu
                     [:a (merge {:class "item"
                                 :href (str "#/job/" (:job/name job))}
                                (when (= this-mode :current) {:class "item active"}))
                      [:i.tag.icon] "Current"]
                     [:a (merge {:class "item"
                                 :href (str "#/job/" (:job/name job) "/history")}
                                (when (= this-mode :history) {:class "item active"}))
                      [:i.wait.icon] "History"]
                     [:a (merge {:class "item"
                                 :href (str "#/job/" (:job/name job) "/settings")}
                                (when (= this-mode :settings) {:class "item active"}))
                      [:i.setting.icon] "Settings"]]
                    [:div.ui.bottom.attached.active.tab.segment
                     [:div#tab-content
                      (om/build (case this-mode
                                  :current current-job-view
                                  :history job-history-view
                                  :settings job-settings-view)
                                job
                                {:state {:mode mode
                                         :roles roles}
                                 :opts opts
                                 :react-key "job-detail-mode"})]]]))))

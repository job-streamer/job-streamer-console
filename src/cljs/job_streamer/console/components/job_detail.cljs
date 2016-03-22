(ns job-streamer.console.components.job-detail
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [job-streamer.console.utils :refer [defblock]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [clojure.string :as string]
            [goog.string :as gstring]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [goog.Uri.QueryData :as query-data]
            [job-streamer.console.api :as api]
            [job-streamer.console.validators :as cv]
            [job-streamer.console.format :as fmt]
            [job-streamer.console.blocks :as blocks])
  (:use [cljs.reader :only [read-string]]
        [clojure.walk :only [postwalk]]
        [job-streamer.console.blocks :only [job->xml]]
        [job-streamer.console.components.job-settings :only [job-settings-view]]
        [job-streamer.console.components.pagination :only [pagination-view]]
        [job-streamer.console.components.execution :only [execution-view]])
  (:import [goog.ui.tree TreeControl]
           [goog Uri]))

(enable-console-print!)

;; Now, app-name is static.
(def app-name "default")

(defn save-job-control-bus [job owner job-name jobs-channel]
  (if-let [messages (first (b/validate job
                                       :job/name [v/required [v/matches #"^[\w\-]+$"]]
                                       :job/components [cv/more-than-one]))]
    (om/set-state! owner :message {:class "error"
                                   :header "Invalid job format"
                                   :body [:ul
                                          (for [msg (->> messages
                                                         (postwalk #(if (map? %) (vals %) %))
                                                         flatten)]
                                            [:li msg])]})
    (api/request (str "/" app-name (if job-name (str "/job/" job-name) "/jobs"))
                 (if job-name :PUT :POST)
                 job
                 {:handler (fn [response]
                             (if job-name
                               (om/set-state! owner :message {:class "success"
                                                              :header "Save successful"
                                                              :body [:p "If you back to list, click a breadcrumb menu."]})
                               (put! jobs-channel [:refresh-jobs true]
                                     #(set! (.-href js/location) "#/"))))
                  :error-handler (fn [response]
                                   (om/set-state! owner :message {:class "error"
                                                                  :header "Save failed"
                                                                  :body [:p "Somethig is wrong."]}))})))

(defn save-job [xml owner job-name jobs-channel]
  (let [uri (goog.Uri. (.-href js/location))
        port (.getPort uri)]
    (api/request (str (.getScheme uri) "://" (.getDomain uri) (when port (str ":" port)) "/job/from-xml")
                 :POST
                 xml
                 {:handler (fn [response]
                             (save-job-control-bus response owner job-name jobs-channel))
                  :error-handler (fn [response]
                                   (println response))
                  :format :xml})))

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

(defn schedule-job [job schedule refresh-job-ch scheduling-ch error-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule") :POST
               schedule
               {:handler (fn [response]
                           (put! refresh-job-ch (:job/name job))
                           (put! scheduling-ch false))
                :error-handler (fn [response]
                                 (put! error-ch response))}))

(defn pause-schedule [job owner success-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule/pause") :PUT
               {:handler (fn [response]
                           (put! success-ch (:job/name job)))}))

(defn resume-schedule [job owner success-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule/resume") :PUT
               {:handler (fn [response]
                           (put! success-ch (:job/name job)))}))

(defn drop-schedule [job owner success-ch]
  (api/request (str "/" app-name "/job/" (:job/name job) "/schedule") :DELETE
               {:handler (fn [response]
                           (put! success-ch (:job/name job)))}))

(defn render-job-structure [job-name owner]
  (let [xhrio (net/xhr-connection)
        fetch-job-ch (chan)]
    #_(loop [node (.querySelectorAll js/document ".job-blocks-inner")]
        (when-let [first-child (.-firstChild node)]
          (.removeChild node first-child)
          (recur node)))

    (go
      (let [job (<! fetch-job-ch)
            xml (job->xml (read-string (:job/edn-notation job)))]
        (om/set-state! owner :job job)
        (.domToWorkspace (.-Xml js/Blockly) (.-mainWorkspace js/Blockly)
                         (.textToDom (.-Xml js/Blockly) (str "<xml>" xml "</xml>")))))

    (api/request (str "/" app-name "/job/" job-name)
                 {:handler (fn [response]
                             (put! fetch-job-ch response))})
    (.inject js/Blockly
             (.querySelector js/document ".job-blocks-inner")
             (clj->js {:toolbox "<xml></xml>"
                       :readOnly true}))))

(defn status-color [status]
  (case status
    :batch-status/completed "green"
    :batch-status/failed "red"
    ""))

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

(defn inject-and-dom-to-workspace[job owner]
  (when-let [edn (:job/edn-notation job)]
    (.inject js/Blockly
             (.. (om/get-node owner) (querySelector ".job-blocks-inner"))
             (clj->js {:toolbox (.getElementById js/document "job-toolbox")}))
    (let [xml (job->xml (read-string edn))]
      (.domToWorkspace (.-Xml js/Blockly)
                       (.-mainWorkspace js/Blockly)
                       (.textToDom (.-Xml js/Blockly) (str "<xml>" xml "</xml>"))))))

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
                                                [:a.section {:href (gstring/format (:href item) job-name)}
                                                 (gstring/format (:name item) job-name)])))
                           (let [res (keep identity items)]
                             (conj (vec (drop-last res))
                                   (into [:div.section.active] (rest (last res)))))))
                       (repeat [:i.right.chevron.icon.divider])))])))

(defcomponent job-edit-view [job owner {:keys [jobs-channel]}]
  (render-state [_ {:keys [message]}]
                (html [:div.ui.grid
                       [:div.row {:style {:display (if message "block" "none")}}
                        [:div.column
                         [:div.ui.message {:class (:class message)}
                          [:div.header (:header message)]
                          [:div (:body message)]]]]

                       [:div.row
                        [:div.column
                         [:div.job-blocks-inner {:style {:min-height "500px"}}]]]
                       [:div.row
                        [:div.column
                         [:div.ui.grid
                          [:div.right.aligned.column
                           [:div.icon.ui.buttons
                            [:button.ui.positive.button
                             {:on-click (fn [e]
                                          (let [xml (.workspaceToDom (.-Xml js/Blockly) (.-mainWorkspace js/Blockly))]
                                            (save-job (.domToText (.-Xml js/Blockly) xml)
                                                      owner (:job/name job) jobs-channel)))}
                             [:i.save.icon] "Save"]]]]]]]))
  (did-mount [_]
             (inject-and-dom-to-workspace job owner))
  (will-receive-props [_ next-props]
                      (inject-and-dom-to-workspace next-props owner)))


(defcomponent job-new-view [jobs owner opts]
  (render-state [_ {:keys [message mode]}]
                (html [:div
                       (om/build breadcrumb-view mode)
                       (om/build job-edit-view nil {:opts opts})]))
  (will-mount[_]
             (let [ch (chan)]
               (go-loop []
                        (let [_ (<! ch)]
                          (.inject js/Blockly
                                   (.. (om/get-node owner) (querySelector ".job-blocks-inner"))
                                   (clj->js {:toolbox (.getElementById js/document "job-toolbox")}))))
               (blocks/get-classes ch))))


(defcomponent job-history-view [job owner opts]
  (init-state [_]
              {:page 1
               :per 20})
  (will-mount [_]
              (search-executions (:job/name @job) {:offset 1 :limit (om/get-state owner :per)}
                                 (fn [response]
                                   (om/set-state! owner :executions response))))
  (render-state [_ {:keys [executions page per]}]
                (html
                  [:div.ui.grid
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
                             [:tr
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
                               [:tr
                                [:td {:colSpan 5}
                                 (om/build execution-view step-executions)]])))
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
                                                                             (om/set-state! owner :executions executions))))}})]]])))

(defcomponent scheduling-view [job owner]
  (init-state [_]
              {:error-ch (chan)
               :has-error false
               :schedule (:job/schedule job)})
  (will-mount [_]
              (go-loop []
                       (let [{message :message} (<! (om/get-state owner :error-ch))]
                         (om/set-state! owner :has-error message)))
              (api/request "/calendars" :GET
                           {:handler (fn [response]
                                       (om/set-state! owner :calendars response))}))
  (render-state [_ {:keys [schedule scheduling-ch calendars refresh-job-ch error-ch has-error]}]
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
                     [:label "Quartz format"]
                     [:input {:id "cron-notation" :type "text" :placeholder "Quartz format"
                              :value (:schedule/cron-notation schedule)
                              :on-change (fn [e]
                                           (let [value (.. js/document (getElementById "cron-notation") -value)]
                                             (om/set-state! owner [:schedule :schedule/cron-notation] value)))}]]
                    (when calendars
                      [:div.field
                       [:label "Calendar"]
                       [:select {:value (get-in schedule [:schedule/calendar :calendar/name])
                                 :on-change (fn [_]
                                              (let [value (.. (om/get-node owner) (querySelector "select") -value)]
                                                (om/set-state! owner [:schedule :schedule/calendar :calendar/name] value)))}
                        [:option {:value ""} ""]
                        (for [cal calendars]
                          [:option {:value (cal :calendar/name)} (cal :calendar/name)])]])]
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
               :scheduling?   false})
  (will-mount [_]
              (go-loop []
                       (om/set-state! owner :scheduling? (<! (om/get-state owner :scheduling-ch)))
                       (recur)))
  (render-state [_ {:keys [refresh-job-ch scheduling-ch scheduling?]}]
                (html
                  [:div.ui.raised.segment
                   [:h3.ui.header "Next"]
                   (if scheduling?
                     (om/build scheduling-view job
                               {:init-state {:scheduling-ch scheduling-ch
                                             :refresh-job-ch refresh-job-ch}})
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
                               [:div.description (:schedule/cron-notation schedule)]]])
                           ]
                          [:div.ui.labeled.icon.menu
                           (if exe
                             [:a.item {:on-click (fn [e]
                                                   (pause-schedule job owner refresh-job-ch))}
                              [:i.pause.icon] "Pause"]
                             [:a.item {:on-click (fn [e]
                                                   (resume-schedule job owner refresh-job-ch))}
                              [:i.play.icon] "Resume"])
                           [:a.item {:on-click (fn [e]
                                                 (drop-schedule job owner refresh-job-ch))}
                            [:i.remove.icon] "Drop"]
                           [:a.item {:on-click (fn [e]
                                                 (om/set-state! owner :scheduling? true))}
                            [:i.calendar.icon] "Edit"]]])

                       [:div
                        [:div.header "No schedule"]
                        [:button.ui.primary.button
                         {:on-click (fn [e]
                                      (om/set-state! owner :scheduling? true))}
                         "Schedule this job"]]))])))

(defcomponent job-structure-view [job-name owner]
  (render-state [_ {:keys [dimmed?]}]
                (html
                  [:div.dimmable.image.dimmed
                   {:on-mouse-enter (fn [e]
                                      (om/set-state! owner :dimmed? true))
                    :on-mouse-leave (fn [e]
                                      (om/set-state! owner :dimmed? false))}
                   [:div.ui.inverted.dimmer (when dimmed? {:class "visible"})
                    [:div.content
                     [:div.center
                      [:button.ui.primary.button
                       {:type "button"
                        :on-click (fn [e]
                                    (set! (.-href js/location) (str "#/job/" job-name "/edit")))}
                       "Edit"]]]]
                   [:div.job-blocks-inner.ui.big.image]]))
  (did-mount [_]
             (render-job-structure job-name owner)))

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
  (render-state [_ {:keys [job-detail refresh-job-ch mode]}]
                (let [this-mode (->> mode (drop 3) first)]
                  (html
                    (case this-mode
                      :edit
                      (om/build job-edit-view job-detail)

                      ;;default
                      [:div.ui.stackable.two.column.grid
                       [:div.column
                        [:div.ui.special.cards
                         [:div.job-detail.card
                          (om/build job-structure-view (:job/name job))
                          [:div.content
                           [:div.header (:job/name job)]
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
                              [:div.label "Failed"]]]
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
                                  {:init-state {:refresh-job-ch refresh-job-ch}})]])))))

(defcomponent job-detail-view [job owner opts]
  (render-state [_ {:keys [mode message breadcrumbs]}]
                (let [this-mode (->> mode (drop 2) first)]
                  (html
                    [:div
                     (om/build breadcrumb-view mode {:init-state {:job-name (:job/name job)}})
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
                                 {:state {:mode mode}
                                  :opts opts})]]]))))

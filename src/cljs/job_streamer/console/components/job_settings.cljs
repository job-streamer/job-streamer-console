(ns job-streamer.console.components.job-settings
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [job-streamer.console.api :as api]))

(def app-name "default")

(defn delete-job [job-name]
  (api/request (str "/" app-name "/job/" job-name) :DELETE
               {:handler (fn [response]
                           (set! (.-href js/location) "#/"))}))

(defn save-settings [job-name method owner category obj]
  (om/set-state! owner [:save-status category] false)
  (api/request (str "/" app-name "/job/" job-name "/settings/" (name category)) method obj
               {:handler (fn [_]
                           (om/set-state! owner [:save-status category] true))}))

(defcomponent job-settings-view [app owner]
  (init-state [_]
    {:save-status {:status-notifiction false
                   :time-monitor {:duration 0 :action "" :notification-type ""}
                   :exclusive false}})
  (will-mount [_]
    (api/request (str "/" app-name "/job/" (:job-name app) "/settings")
                 {:handler (fn [response]
                             (om/set-state! owner :settings response))}))
  (render-state [_ {:keys [settings save-status time-monitor]}]
    (html
     [:div
      [:div.ui.segment
       [:div.ui.top.attached.label "Notification"]
       [:div.content
        [:div.ui.input.block
         "When the status has been changed to "
         [:select {:id "notification-status"}
          [:option {:value ""} ""]
          [:option {:value "abandoned"} "abandoned"]
          [:option {:value "completed"} "completed"]
          [:option {:value "failed"} "failed"]
          [:option {:value "completed"} "completed"]
          [:option {:value "started"} "stated"]]
         ", send notification by "
         [:input {:id "notification-type" :type "text"}]
         [:button.ui.positive.button
          {:type "button"
           :on-click #(let [status (.-value (.getElementById js/document "notification-status")) 
                            type (.-value (.getElementById js/document "notification-type"))
                            status-notification {:status-notification/batch-status (keyword "batch-status" status)
                                                 :status-notification/type type}]
                        (om/update-state! owner [:settings :job/status-notifications]
                                          (fn [st] (conj st status-notification)))
                        (save-settings (:job-name app)
                                       :PUT
                                       owner
                                       :status-notification
                                       status-notification))}
          "Add"]]
        (if (not-empty (:job/status-notifications settings)) 
          [:table.ui.compact.table
           [:thead
            [:tr
             [:th "Status"]
             [:th "Notification"]
             [:th ""]]]
           (for [notification (:job/status-notifications settings)]
             [:tr
              [:td (name (get-in notification [:status-notification/batch-status :db/ident] "")) ]
              [:td (:status-notification/type notification)]
              [:td [:a
                    [:i.remove.red.icon
                     {:on-click (fn [_]
                                  (om/update-state! owner [:settings :job/status-notifications]
                                                    (fn [st] (remove #(= (:db/id %) (:db/id notification)) st)))
                                  (save-settings (:job-name app)
                                                 :PUT
                                                 owner
                                                 :status-notification
                                                 {:db/id (:db/id notification)}))}]]]])])]]
      [:div.ui.segment
       [:div.ui.top.attached.label "Schedule settings"]
       [:div.content
        [:h4.ui.header "Exclusive execution"]
        [:div.ui.toggle.checkbox
         (merge
          {:on-click (fn [e]
                       (let [cb (.getElementById js/document "exclusive-checkbox")
                             checked (not (.-checked cb))]
                         (om/set-state! owner [:settings :job/exclusive?] checked)
                         (save-settings (:job-name app) (if checked :PUT :DELETE)
                                        owner :exclusive
                                        {:job/exclusive? checked})))}
          (when (:job/exclusive? settings) {:class "checked"}))
         [:input {:id "exclusive-checkbox" :type "checkbox" :checked (:job/exclusive? settings)}]
         [:label "If this job should be executed exclusively, check this"
          (when (:exclusive save-status) [:i.checkmark.green.icon])]]
        
        [:h4.ui.header "Execution constraints"]
        (if-let [settings-time-monitor (not-empty (:job/time-monitor settings))]
          [:div
           "When it's passed for "
           (:time-monitor/duration settings-time-monitor) "minutes,"
           (case (get-in settings-time-monitor [:time-monitor/action :db/ident])
             :action/alert (str "send an alert by \"" (:time-monitor/notification-type settings-time-monitor) "\"")
             :action/abort (str "abort the job."))
           
           [:a {:on-click (fn [_]
                            (save-settings (:job-name app) :DELETE
                                           owner :time-monitor {})
                            (om/set-state! owner [:settings :job/time-monitor] nil))}
            [:i.remove.red.icon]]]
          [:div.ui.right.labeled.block.input
           [:input {:id "time-monitor-duration"
                    :type "number"
                    :value (:duration time-monitor)
                    :on-change (fn [_]
                                 (let [duration (js/parseInt (.-value (.getElementById js/document "time-monitor-duration")))]
                                   (om/update-state! owner :time-monitor #(assoc % :duration (if (> duration 0) duration 0)))))}]
           [:div.ui.label "minutes"]

           "Action:"
           [:select {:id "time-monitor-action"
                     :on-change (fn [_]
                                  (let [action (.-value (.getElementById js/document "time-monitor-action"))]
                                    (om/update-state! owner :time-monitor #(assoc % :action action))))}
            [:option {:value ""} ""]
            [:option {:value "alert"} "Alert"]
            [:option {:value "abort"} "Abort"]]
           (when (= (:action time-monitor) "alert")
             [:input {:type "text" :id "time-monitor-notification-type"
                      :value (:notification-type time-monitor)
                      :placeholder "Notification"
                      :on-change (fn [_]
                                   (let [notification-type (.-value (.getElementById js/document "time-monitor-notification-type"))]
                                     (om/update-state! owner :time-monitor #(assoc % :notification-type notification-type))))}])
           [:button.ui.tiny.positive.button
            (merge {:type "button"
                    :on-click (fn [_]
                                (let [settings-time-monitor (->> (update-in time-monitor [:action] #(keyword "action" %))
                                                                 (map (fn [[k v]] [(keyword "time-monitor" (name k)) v]))
                                                                 (reduce (fn [m [k v]] (assoc m k v)) {}))]
                                  (save-settings (:job-name app) :PUT
                                               owner :time-monitor
                                               settings-time-monitor)
                                  (om/set-state! owner [:settings :job/time-monitor] settings-time-monitor)))}
                   (when (or (= (:duration time-monitor) 0)
                             (empty? (:action time-monitor))
                             (and (= (:action time-monitor) "alert") (empty? (:notification-type time-monitor))))
                     {:class "disabled"}))
            "Save"]])]]
      
      [:div.ui.segment
       [:div.ui.top.attached.label "Danger Zone"]
       [:div.content
        [:h4.ui.header "Delete this job"]
        "Once you delete a job, there is no going back."
        [:button.ui.red.button
         {:type "button"
          :on-click (fn [e]
                      (delete-job (:job-name app)))} "Delete this job"]]]])))

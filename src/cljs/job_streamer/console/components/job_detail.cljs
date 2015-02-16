(ns job-streamer.console.components.job-detail
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [job-streamer.console.utils :refer [defblock]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [clojure.string :as string]
            [goog.events :as events]
            [goog.ui.Component]
            [goog.string :as gstring]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [job-streamer.console.format :as fmt])
  (:use [cljs.reader :only [read-string]]
        [job-streamer.console.blocks :only [job->xml]]
        [job-streamer.console.components.execution :only [execution-view]])
  (:import [goog.net EventType]
           [goog.events KeyCodes]
           [goog.ui.tree TreeControl]))

(enable-console-print!)

(def control-bus-url (.. js/document
                         (querySelector "meta[name=control-bus-url]")
                         (getAttribute "content")))

(defn save-job-control-bus [edn owner job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (case (.getStatus xhrio)
                       201 (om/set-state! owner :message {:class "success"
                                                          :header "Save successful"
                                                          :body "If you back to list"})
                       (om/set-state! owner :message {:class "error"
                                                      :header "Save failed"
                                                      :body "Somethig is wrong."}))))
    (let [job (read-string edn)]
      (if-let [message (first (b/validate job
                                          :job/id v/required))]
        (om/set-state! owner :message {:class "error"
                                       :header "Invalid job format"
                                       :body message})
        (.send xhrio (str "http://localhost:45102"
                        (if job-id (str "/job/" job-id) "/jobs"))
             (if job-id "put" "post") 
             edn 
             (clj->js {:content-type "application/edn"}))))))

(defn save-job [xml owner job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (let [edn (.getResponseText xhrio)]
                       (save-job-control-bus edn owner job-id))))
    (.send xhrio (str "/job/from-xml") "post" xml
           (clj->js {:content-type "application/xml"}))))



(defn search-execution [owner job-id execution-id idx]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (let [steps (-> (.getResponseText xhrio)
                                    (read-string)
                                    :job-execution/step-executions)]
                       (om/update-state! owner [:executions idx] 
                                         #(assoc % :job-execution/step-executions steps)))))
    (.send xhrio (str control-bus-url "/job/" job-id "/execution/" execution-id))))

(defn schedule-job [job cron-notation success-ch error-ch]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (put! success-ch [:success cron-notation])))
    (events/listen xhrio EventType.ERROR
                   (fn [e]
                     (put! error-ch (read-string (.getResponseText xhrio)))))
    (.send xhrio (str control-bus-url "/job/" (:job/id job) "/schedule") "post"
           {:job/id (:job/id job) :schedule/cron-notation cron-notation}
           (clj->js {:content-type "application/edn"}))))

(defn drop-schedule [job owner]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (om/update-state! owner :job
                                       #(dissoc % :job/schedule))))
    (.send xhrio (str control-bus-url "/job/" (:job/id job) "/schedule") "delete")))

;;;
;;; Om view components
;;;

(def breadcrumb-elements
  {:jobs {:name "Jobs" :href "#/"}
   :jobs.detail.current {:name "Job: %s" :href "#/job/%s"}
   :jobs.detail.current.edit {:name "Edit" :href "#/job/%s/edit"}
   :jobs.detail.history {:name "History" :href "#/job/%s/history"}})

(defcomponent breadcrumb-view [mode owner]
  (render-state [_ {:keys [job-id]}]
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
                                 [:a.section {:href (gstring/format (:href item) job-id)}
                                  (gstring/format (:name item) job-id)])))
            (keep identity items)))
        (repeat [:i.right.chevron.icon.divider])))])))

(defcomponent job-edit-view [job owner]
  (render-state [_ {:keys [message]}]
    (html [:div
           (when message
             [:div.ui.message {:class (:class message)}
              [:div.header (:header message)]
              [:p (:body message)]])
           [:div.ui.menu
            [:div.item
             [:div.icon.ui.buttons
              [:button.ui.primary.button
               {:on-click (fn [e]
                            (let [xml (.. js/Blockly -Xml (workspaceToDom (.-mainWorkspace js/Blockly)))]
                              (save-job (.. js/Blockly -Xml (domToText xml))
                                        owner (:job/id job))))} [:i.save.icon]]]]]
           [:div#job-blocks-inner]]))
  (did-mount [_]
    (.inject js/Blockly
             (.getElementById js/document "job-blocks-inner")
             (clj->js {:toolbox (.getElementById js/document "job-toolbox")}))
    (when job
      (let [xml (job->xml (read-string (:job/edn-notation job)))]
        (.. js/Blockly -Xml (domToWorkspace
                             (.-mainWorkspace js/Blockly)
                             (.. js/Blockly -Xml (textToDom (str "<xml>" xml "</xml>") ))))))))

(defcomponent job-new-view [app owner]
  (render-state [_ {:keys [message]}]
    (html [:div
           (om/build breadcrumb-view app)
           (om/build job-edit-view (:job-id app))])))

(defcomponent job-history-view [{:keys [job-id]} owner]
  (will-mount [_]
    (let [xhrio (net/xhr-connection)]
      (events/listen xhrio EventType.SUCCESS
                     (fn [e]
                       (om/set-state! owner :executions
                                      (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/job/" job-id "/executions") "get")))
  (render-state [_ {:keys [executions]}]
    (html
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
            [:td [:a {:on-click
                      (fn [e] (search-execution owner job-id (:db/id execution) idx))}
                  (:db/id execution)]]
            [:td (get-in execution [:job-execution/agent :agent/name])]
            [:td (fmt/date-medium (:job-execution/start-time execution))]
            [:td (fmt/duration-between
                  (:job-execution/start-time execution)
                  (:job-execution/end-time execution))]
            [:td (name (get-in execution [:job-execution/batch-status :db/ident]))]]
           (when-let [step-executions (not-empty (:job-execution/step-executions execution))]
             [:tr
              [:td {:colSpan 5}
               (om/build execution-view step-executions)]])))
        executions)]])))

(defcomponent scheduling-view [job owner]
  (init-state [_]
    {:error-ch (chan)
     :has-error false})
  (will-mount [_]
    (go-loop []
      (let [{message :message} (<! (om/get-state owner :error-ch))]
        (om/set-state! owner :has-error message))))
  (render-state [_ {:keys [scheduling-ch error-ch has-error]}]
    (html
     [:form.ui.form 
      (merge {:on-submit (fn [e]
                           (.. e -nativeEvent preventDefault)
                           (schedule-job job
                                         (.. js/document (getElementById "cron-notation") -value)
                                         scheduling-ch error-ch)
                           false)}
             (when has-error {:class "error"}))
      (when has-error
        [:div.ui.error.message
         [:p has-error]])
      [:div.fields
       [:div.field (when has-error {:class "error"})
        [:input {:id "cron-notation" :type "text" :placeholder "Quartz format"
                 :value (get-in job [:job/schedule :schedule/cron-notation])}]]]
      [:div.ui.buttons
        [:button.ui.button
         {:type "button"
          :on-click (fn [e] (put! scheduling-ch [:cancel nil]))}
         "Cancel"]
        [:div.or]
        [:button.ui.positive.button {:type "submit"} "Save"]]])))

(defn render-job-structure [job-id owner]
  (let [xhrio (net/xhr-connection)
        fetch-job-ch (chan)]
    (loop [node (.getElementById js/document "job-blocks-inner")]
      (when-let [first-child (.-firstChild node)]
        (.removeChild node first-child)
        (recur node))) 
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (put! fetch-job-ch  (read-string (.getResponseText xhrio)))))
    (go
      (let [job (<! fetch-job-ch)
            xml (job->xml (read-string (:job/edn-notation job)))]
        (om/set-state! owner :job job)
        (.. js/Blockly -Xml (domToWorkspace
                             (.-mainWorkspace js/Blockly)
                             (.. js/Blockly -Xml (textToDom (str "<xml>" xml "</xml>")))))))
    (.send xhrio (str control-bus-url "/job/" job-id) "get")
    (.inject js/Blockly
             (.getElementById js/document "job-blocks-inner")
             (clj->js {:toolbox "<xml></xml>"
                       :readOnly true}))))

(defcomponent current-job-view [{:keys [job-id] :as app} owner]
  (init-state [_]
    {:scheduling-ch (chan)})
  (will-mount [_]
    (go-loop []
      (let [[type cron-notation] (<! (om/get-state owner :scheduling-ch))]
        (case type
          :success
          (let [xhrio (net/xhr-connection)]
            (events/listen xhrio EventType.SUCCESS
                         (fn [e]
                           (om/set-state! owner :scheduling? false)
                           (om/set-state! owner :job (read-string (.getResponseText xhrio)))))
            (.send xhrio (str control-bus-url "/job/" job-id) "get"))

          :cancel
          (om/set-state! owner :scheduling? false))
        (recur))))

  (render-state [_ {:keys [job dimmed? scheduling? scheduling-ch]}]
    (let [mode (->> app :mode (drop 3) first)]
      (html
       (case mode
         :edit
         (om/build job-edit-view job)

         ;;default
         [:div.ui.stackable.two.column.grid
          [:div.column
           [:div.ui.special.cards
            [:div.card
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
                               (set! (.-href js/location) (str "#/job/" job-id "/edit")))}
                  "Edit"]]]]
              [:div#job-blocks-inner.ui.big.image]]
             [:div.content
              [:div.header (:job/id job)]
              [:div.description
               [:div.ui.tiny.statistics
                [:div.statistic
                 [:div.value (get-in job [:job/stats :total])]
                 [:div.label "Total"]]
                [:div.statistic
                 [:div.value (get-in job [:job/stats :success])]
                 [:div.label "Success"]]
                [:div.statistic
                 [:div.value (get-in job [:job/stats :failure])]
                 [:div.label "Faied"]]]
               [:hr.ui.divider]
               [:div.ui.tiny.horizontal.statistics
                [:div.statistic
                 [:div.value (fmt/duration (get-in job [:job/stats :average]))]
                 [:div.label "Average duration"]]]]]]]]
          [:div.column
           [:div.ui.raised.segment
            [:h3.ui.header "Latest"]
            (when-let [exe (:job/latest-execution job)]
              [:div.ui.list
               [:div.item
                [:div.ui.huge.label
                 [:i.check.circle.icon] (name (get-in exe [:job-execution/batch-status :db/ident]))]]
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
                 [:div.description (get-in exe [:job-execution/agent :agent/name])]]]])]
           [:div.ui.raised.segment
            [:h3.ui.header "Next"]
            (if-let [exe (:job/next-execution job)]
              (if scheduling?
                (om/build scheduling-view job
                          {:init-state {:scheduling-ch scheduling-ch}})
                [:div
                 [:div.ui.list
                  [:div.item
                   [:i.wait.icon]
                   [:div.content
                    [:div.description (fmt/date-medium (:job-execution/start-time exe))]]]]
                 [:div.ui.labeled.icon.menu
                  [:a.item {:on-click (fn [e]
                                        (drop-schedule job owner))}
                   [:i.remove.icon] "Drop"]
                  [:a.item {:on-click (fn [e]
                                        (om/set-state! owner :scheduling? true))}
                   [:i.calendar.icon] "Edit"]]])
              
              
              (if scheduling?
                (om/build scheduling-view job 
                          {:init-state {:scheduling-ch scheduling-ch}})
                [:div
                 [:div.header "No schedule"]
                 [:button.ui.primary.button
                  {:on-click (fn [e]
                               (om/set-state! owner :scheduling? true))}
                  "Schedule this job"]]))]]]))))
  (did-update [_ _ _]
    (when-not (.-firstChild (.getElementById js/document "job-blocks-inner"))
      (render-job-structure job-id owner)))
  (did-mount [_]
    (render-job-structure job-id owner)))

(defcomponent job-detail-view [{:keys [job-id] :as app} owner]
  (render-state [_ {:keys [message breadcrumbs]}]
    (let [mode (->> app :mode (drop 2) first)]
      (html 
       [:div
        (om/build breadcrumb-view (:mode app) {:init-state {:job-id job-id}})
        [:div.ui.top.attached.tabular.menu
         [:a (merge {:class "item"
                     :href (str "#/job/" job-id)}
                    (when (= mode :current) {:class "item active"}))
          [:i.tag.icon] "Current"]
         [:a (merge {:class "item"
                     :href (str "#/job/" job-id "/history")}
                    (when (= mode :history) {:class "item active"}))
          [:i.wait.icon] "History"]]
        [:div.ui.bottom.attached.active.tab.segment
         [:div#tab-content
          (om/build (case mode
                      :current current-job-view
                      :history job-history-view)
                    app)]]]))))

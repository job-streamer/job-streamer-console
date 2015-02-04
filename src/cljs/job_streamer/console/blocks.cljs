(ns job-streamer.console.blocks
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [job-streamer.console.utils :refer [defblock]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [job-streamer.console.format :as fmt])
  (:use [cljs.reader :only [read-string]]
        [job-streamer.console.execution :only [execution-view]])
  (:import [goog.net EventType]
           [goog.events KeyCodes]
           [goog.ui.tree TreeControl]))

(enable-console-print!)

(def control-bus-url "http://localhost:45102")

(def mutate-behavor
  {:mutationToDom (fn []
                    (this-as
                     this
                     (let [container (.createElement js/document "mutation")]
                       (.setAttribute container "items" (.-itemCount this))
                       container)))
   :domToMutation (fn [xml]
                    (this-as
                     this
                     (set! (.-itemCount this)
                           (js/parseInt (.getAttribute xml "items") 10))
                     (.updateShape this)))
   :decompose (fn [workspace]
                (this-as
                 this
                 (let [container-block (.. js/Blockly -Block (obtain workspace (str "property-container")))]
                   (.initSvg container-block)
                   (loop [connection (.. container-block (getInput "STACK") -connection)
                          i 0]
                     (when (< i (.-itemCount this))
                       (let [item-block (.. js/Blockly -Block (obtain workspace (str "property-item")))]
                         (.initSvg item-block)
                         (.connect connection (.-previousConnection item-block))
                         (recur (.-nextConnection item-block) (inc i)))))
                   container-block)))

   :compose (fn [container-block]
              (this-as
               this
               (let [connections (array)]
                 (loop [item-block (.getInputTargetBlock container-block "STACK")
                        i 0]
                   (if item-block
                     (do
                       (aset connections i (.-valueConnection item-block))
                       (recur (some-> item-block (.-nextConnection) (.targetBlock))
                              (inc i)))
                     (set! (.-itemCount this) i)))
                 (.updateShape this)
                 (loop [i 0]
                   (when (< i (.-itemCount this))
                     (if (aget connections i)
                       (.. this
                           (getInput (str "ADD" i))
                           (-connection)
                           (connect (aget connections i))))
                     (recur (inc i)))))))

   :saveConnections (fn [container-block]
                      (this-as
                       this
                       (loop [item-block (.getInputTargetBlock container-block "STACK")
                              i 0]
                         (when item-block
                           (let [input (.getInput this (str "ADD" i))]
                             (set! (.-valueConnection item-block)
                                   (some-> input (.-connection) (.-targetConnection)))
                             (recur (some-> item-block (.-nextConnection) (.targetBlock))
                                    (inc i)))))))
   :updateShape (fn []
                  (this-as
                   this
                   (if (.getInput this "EMPTY")
                     (.removeInput this "EMPTY")
                     (loop [i 0]
                       (when-let [add-input (.getInput this (str "ADD" i))]
                         (.removeInput this (str "ADD" i))
                         (recur (inc i)))))
                   (if (= (.-itemCount this) 0)
                     (.. this
                         (appendDummyInput "EMPTY")
                         (appendField "Property"))
                     (loop [i 0]
                       (when (< i (.-itemCount this))
                         (let [input (.appendValueInput this (str "ADD" i))]
                           (when (= i 0)
                             (.appendField input "Properties"))
                           (recur (inc i))))))))})

(aset
 (.-Blocks js/Blockly) "property-container"
 (clj->js {:init (fn []
                   (this-as
                    this
                    (.. this appendDummyInput (appendField "Properties"))
                    (doto this
                      (.setColour 180)
                      (.appendStatementInput "STACK"))
                    (set! (.-contextMenu this) false)))}))

(aset
 (.-Blocks js/Blockly) "property-item"
 (clj->js {:init (fn []
                   (this-as
                    this
                    (doto this
                      (.setColour 180)
                      (.setPreviousStatement true)
                      (.setNextStatement true))
                    (set! (.-contextMenu this) false)
                    (.. this appendDummyInput (appendField "Property"))))}))

(defblock job
  :colour 160
  :previous-statement? false
  :next-statement? false
  :mutator "property-item"
  :fields [{:type :text
            :name "id"
            :label "Job"}
           {:type :statement
            :name "steps"}])

(let [block (aget (.-Blocks js/Blockly) "job")]
  (doseq [[k f] mutate-behavor]
    (aset block (name k) f)))
  
(defblock step
  :colour 150
  :previous-statement? true
  :next-statement? true
  :mutator "property-item"
  :fields [{:type :text
            :name "id"
            :label "Step"}
           {:type :value-input
            :name "step-component"
            :label ""
            :acceptable ["Batchlet" "Chunk"]}])

(let [block (aget (.-Blocks js/Blockly) "step")]
  (doseq [[k f] mutate-behavor]
    (aset block (name k) f)))

(defblock batchlet
  :colour 316
  :output "Batchlet"
  :fields [{:type :text
            :name "ref"
            :label "Batchlet"}])

(defblock chunk
  :colour 234
  :output "Chunk"
  :fields [{:type :value-input
            :name "reader"
            :label "Reader"
            :acceptable ["Reader"]}
           {:type :value-input
            :name "processor"
            :label "Processor"
            :acceptable ["Processor"]}
           {:type :value-input
            :name "writer"
            :label "Writer"
            :acceptable ["Writer"]}])

(defblock reader
  :colour 45
  :output "Reader"
  :fields [{:type :text
            :name "ref"
            :label "Reader"}])

(defblock processor
  :colour 45
  :output "Processor"
  :fields [{:type :text
            :name "ref"
            :label "Processor"}])

(defblock writer
  :colour 45
  :output "Writer"
  :fields [{:type :text
            :name "ref"
            :label "Writer"}])

(defblock property
  :colour 30
  :output "Properties"
  :inline? true
  :fields [{:type :text
            :name "name"
            :label "Name"}
           {:type :text
            :name "value"
            :label "Value"}])

(defn emit-element [e]
  (if (= (type e) js/String)
    e
    (str "<" (name (:tag e))
         (when-let [attrs (:attrs e)]
           (->> attrs
                (map #(str " " (name (first %)) "='" (second %) "'"))
                (reduce str)))
         (if-let [content (:content e)]
           (apply str ">" (reduce str (map emit-element content)) "</" (name (:tag e)) ">")
           "/>"))))

(defn chunk-element->xml [chunk type]
  {:tag :value
    :attrs {:name type}
    :content [(when-let [el (get chunk (keyword "chunk" type))]
                  [{:tag :block
                    :attrs {:type type}
                    :content [{:tag :field
                               :attrs {:name "ref"}
                               :content [(get el (keyword type "ref"))]}]}])]})

(defn chunk->xml [chunk]
  [(chunk-element->xml chunk "reader")
   (chunk-element->xml chunk "processor")
   (chunk-element->xml chunk "writer")])

(defn step->xml [step steps]
  {:tag :block
   :attrs {:type "step"}
   :content (concat
             (when-let [props (:step/properties step)]
                [{:tag :mutation
                  :attrs {:items (count props)}}])
             (when-let [id (:step/id step)]
               [{:tag :field, :attrs {:name "id"}, :content [id]}])
             (when-let [batchlet (:step/batchlet step)]
               [{:tag :value
                 :attrs {:name "step-component"}
                 :content [{:tag :block
                            :attrs {:type "batchlet"}
                            :content [{:tag :field
                                       :attrs {:name "ref"}
                                       :content [(:batchlet/ref batchlet)]}]}]}])
             (when-let [chunk (:step/chunk step)]
               [{:tag :value
                 :attrs {:name "step-component"}
                 :content [{:tag :block
                            :attrs {:type "chunk"}
                            :content [(chunk->xml chunk)]}]}])
             (when-let [next-step (:next step)]
               [{:tag :next
                 :content [(step->xml (first (filter #(= (:id %) next-step) steps)) steps)]}])
             (when-let [props (:step/properties step)]
                (map-indexed
                 (fn [idx [k v]]
                   {:tag :value
                    :attrs {:name (str "ADD" idx)}
                    :content [{:tag :block
                               :attrs {:type "property"}
                               :content [{:tag :field
                                          :attrs {:name "name"}
                                          :content [(name k)]}
                                         {:tag :field
                                          :attrs {:name "value"}
                                          :content [v]}]}]})
                 props)))})
(defn job->xml [job]
  (emit-element
   {:tag :block
    :attrs {:type "job"}
    :content (concat 
              (when-let [props (:job/properties job)]
                [{:tag :mutation
                  :attrs {:items (count props)}}])
              (when-let [id (:job/id job)]
                [{:tag :field :attrs {:name "id"} :content [id]}])
              (when-let [restartable? (:job/restartable job)]
                [{:tag :field :attrs {:name "restartable?"} :content [restartable?]}])
              (when-let [steps (not-empty (:job/steps job))]
                [{:tag :statement
                  :attrs {:name "steps"} 
                  :content [(step->xml (first steps) steps)]}])
              (when-let [props (:job/properties job)]
                (map-indexed
                 (fn [idx [k v]]
                   {:tag :value
                    :attrs {:name (str "ADD" idx)}
                    :content [{:tag :block
                               :attrs {:type "property"}
                               :content [{:tag :field
                                          :attrs {:name "name"}
                                          :content [(name k)]}
                                         {:tag :field
                                          :attrs {:name "value"}
                                          :content [v]}]}]})
                 props)))}))

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

(defcomponent job-edit-view [job owner]
  (render-state [_ {:keys [comm message]}]
    (html [:div
           [:div.ui.breadcrumb
            [:a.section
             {:on-click (fn [e]
                          (put! comm [:list nil]))} "Jobs"]
            [:i.right.chevron.icon.divider]
            [:div.active.section
             (if job
               (str "Edit job: " (:job/id job))
               "New job")]]
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

(defcomponent job-history-view [job-id owner]
  (will-mount [_]
    (let [xhrio (net/xhr-connection)]
      (events/listen xhrio EventType.SUCCESS
                     (fn [e]
                       (om/set-state! owner :executions
                                      (read-string (.getResponseText xhrio)))))
      (.send xhrio (str control-bus-url "/job/" job-id "/executions") "get")))
  (render-state [_ {:keys [comm executions]}]
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

(defcomponent current-job-view [job-id owner]
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

  (render-state [_ {:keys [job comm dimmed? scheduling? scheduling-ch]}]
    (html [:div.ui.stackable.two.column.grid
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
                     :on-click (fn [e])}
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
                     "Schedule this job"]]))]]]))
  (did-mount [_]
    (let [xhrio (net/xhr-connection)
          fetch-job-ch (chan)]
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
                      :readOnly true})))))

(defcomponent job-detail-view [job-id owner]
  (init-state [_]
    {:mode-job :current})
  (render-state [_ {:keys [comm message mode-job]}]
    (html [:div
           [:div.ui.breadcrumb
            [:a.section
             {:on-click (fn [e]
                          (put! comm [:list nil]))} "Jobs"]
            [:i.right.chevron.icon.divider]
            [:div.active.section
             (if job-id
               (str "job: " job-id)
               "New job")]]
           [:div.ui.top.attached.tabular.menu
               [:a (merge {:class "item"
                           :on-click #(om/set-state! owner :mode-job :current)}
                          (when (= mode-job :current) {:class "item active"}))
                "Current"]
               [:a (merge {:class "item"
                           :on-click #(om/set-state! owner :mode-job :history)}
                          (when (= mode-job :history) {:class "item active"}))
                "History"]]
           [:div.ui.bottom.attached.active.tab.segment
               [:div#tab-content
                (om/build (case mode-job
                            :current current-job-view
                            :history job-history-view)
                          job-id {:init-state {:comm comm}})]]])))

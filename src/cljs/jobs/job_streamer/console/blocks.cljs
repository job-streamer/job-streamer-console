(ns job-streamer.console.blocks
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [job-streamer.console.utils :refer [defblock]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.ui.Component])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.net EventType]
           [goog.events KeyCodes]
           [goog.i18n DateTimeFormat]))

(enable-console-print!)

(defblock job
  :colour 160
  :previous-statement? false
  :next-statement? false
  :fields [{:type :text
            :name "id"
            :label "Job"}
           {:type :statement
            :name "steps"}])

(defblock step
  :colour 150
  :previous-statement? true
  :next-statement? true
  :inline? true
  :fields [{:type :text
            :name "id"
            :label "Step"}
           {:type :value-input
            :name "step-component"
            :label ""
            :acceptable ["Batchlet" "Chunk"]}])

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
            :label ""}])

(defblock processor
  :colour 45
  :output "Processor"
  :fields [{:type :text
            :name "ref"
            :label ""}])

(defblock writer
  :colour 45
  :output "Writer"
  :fields [{:type :text
            :name "ref"
            :label ""}])

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

(defn step->xml [step steps]
  {:tag :block
   :attrs {:type "step"}
   :content (concat (when-let [id (:step/id step)]
                      [{:tag :field, :attrs {:name "id"}, :content [id]}])
                    (when-let [batchlet (:step/batchlet step)]
                      [{:tag :value
                        :attrs {:name "step-component"}
                        :content [{:tag :block
                                   :attrs {:type "batchlet"}
                                   :content [{:tag :field
                                              :attrs {:name "ref"}
                                              :content [(:batchlet/ref batchlet)]}]}]}])
                    (when-let [next-step (:next step)]
                      [{:tag :next
                        :content [(step->xml (first (filter #(= (:id %) next-step) steps)) steps)]}]))})
(defn job->xml [job]
  (emit-element
   {:tag :block
    :attrs {:type "job"}
    :content (concat (when-let [id (:job/id job)]
                       [{:tag :field :attrs {:name "id"} :content [id]}])
                     (when-let [restartable? (:job/restartable job)]
                       [{:tag :field :attrs {:name "restartable?"} :content [restartable?]}])
                     (when-let [steps (not-empty (:job/steps job))]
                       [{:tag :statement
                         :attrs {:name "steps"} 
                         :content [(step->xml (first steps) steps)]}]))}))

(defn save-job-control-bus [edn owner job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (case (.getStatus xhrio)
                       201 (om/set-state! owner :message {:class "success"
                                                          :header "Save successful"
                                                          :body "If you back to list"})
                       (om/set-state! owner :message {:class "error"
                                                      :header "Save failed."
                                                      :body "Somethig is wrong."}))))
    (let [job (read-string edn)]
      (.send xhrio (str "http://localhost:45102"
                        (if job-id (str "/job/" job-id) "/jobs"))
             (if job-id "put" "post") 
             edn 
             (clj->js {:content-type "application/edn"})))))

(defn save-job [xml owner job-id]
  (let [xhrio (net/xhr-connection)]
    (events/listen xhrio EventType.SUCCESS
                   (fn [e]
                     (let [edn (.getResponseText xhrio)]
                       (save-job-control-bus edn owner job-id))))
    (.send xhrio (str "/job/from-xml") "post" xml
           (clj->js {:content-type "application/xml"}))))

(defcomponent blocks-view [job-id owner]
  (render-state [_ {:keys [comm message]}]
    (html [:div
           [:div.ui.breadcrumb
            [:a.section
             {:on-click (fn [e]
                          (put! comm [:list nil]))} "Jobs"]
            [:i.right.chevron.icon.divider]
            [:div.active.section
             (if job-id
               (str "Edit job: " job-id)
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
                                        owner job-id)))} [:i.save.icon]]]]]
           [:div#job-blocks-inner]]))
  (did-mount
   [_]
   (when job-id
     (let [xhrio (net/xhr-connection)]
       (events/listen xhrio EventType.SUCCESS
                      (fn [e]
                        (let [job (read-string (.getResponseText xhrio))
                              xml (job->xml (read-string (:job/edn-notation job)))]
                          (.. js/Blockly -Xml (domToWorkspace
                                               (.-mainWorkspace js/Blockly)
                                               (.. js/Blockly -Xml (textToDom (str "<xml>" xml "</xml>") )))))))
       (.send xhrio (str "http://localhost:45102" "/job/" job-id) "get")))
   
   (.inject js/Blockly
            (.getElementById js/document "job-blocks-inner")
            (clj->js {:toolbox (.getElementById js/document "job-toolbox")}))))

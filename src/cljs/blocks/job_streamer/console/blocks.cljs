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
  (:import [goog.net.EventType]
           [goog.events EventType]))

(enable-console-print!)

(def app-state {})

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

(defcomponent blocks-view [app owner]
  (render [_]
    (html [:div
           [:div.icon.ui.buttons
            [:button.ui.button
             {:on-click (fn [e]
                          (let [xml (.. js/Blockly -Xml (workspaceToDom (.-mainWorkspace js/Blockly)))]
                            (println (.. js/Blockly -Xml (domToText xml)))))} [:i.save.icon]]]
           [:div#job-blocks-inner]]))
  (did-mount
   [_]
   (.inject js/Blockly
            (.getElementById js/document "job-blocks-inner")
            (clj->js {:toolbox (.getElementById js/document "job-toolbox")}))))

(om/root blocks-view app-state
         {:target (.getElementById js/document "job-blocks")})

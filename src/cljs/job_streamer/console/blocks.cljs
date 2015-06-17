(ns job-streamer.console.blocks
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [job-streamer.console.utils :refer [defblock]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.string :as string]
            [goog.events :as events]
            [goog.ui.Component]
            [Blockly :as Blockly]
            [Blockly.Block :as Block]
            [job-streamer.console.format :as fmt]
            [job-streamer.console.api :as api])
  (:use [cljs.reader :only [read-string]])
  (:import [Blockly Blocks Mutator FieldTextInput FieldCheckbox]))

(enable-console-print!)

(aset Blocks "property-container"
 (clj->js {:init (fn []
                   (this-as
                    this
                    (.. this appendDummyInput (appendField "Properties"))
                    (doto this
                      (.setColour 180)
                      (.appendStatementInput "STACK"))
                    (set! (.-contextMenu this) false)))}))

(aset Blocks  "property-item"
 (clj->js {:init (fn []
                   (this-as
                    this
                    (doto this
                      (.setColour 180)
                      (.setPreviousStatement true)
                      (.setNextStatement true))
                    (set! (.-contextMenu this) false)
                    (.. this appendDummyInput (appendField "Property"))))}))

(def mutate-behavior
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
                 (let [container-block (Block/obtain workspace (str "property-container"))]
                   (.initSvg container-block)
                   (loop [connection (.. container-block (getInput "STACK") -connection)
                          i 0]
                     (when (< i (.-itemCount this))
                       (let [item-block (Block/obtain workspace (str "property-item"))]
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

(defblock job
  :colour 160
  :previous-statement? false
  :next-statement? false
  :mutator "property-item"
  :fields [{:type :text
            :name "name"
            :label "Job"}
           {:type :statement
            :name "steps"}])

(let [block (aget Blocks "job")]
  (doseq [[k f] mutate-behavior]
    (aset block (name k) f)))
  
(defblock step
  :colour 150
  :previous-statement? true
  :next-statement? true
  :mutator "property-item"
  :fields [{:type :text
            :name "name"
            :label "Step"}
           {:type :value-input
            :name "step-component"
            :label ""
            :acceptable ["Batchlet" "Chunk"]}])

(let [block (aget Blocks "step")]
  (doseq [[k f] mutate-behavior]
    (aset block (name k) f)))

(api/request (str "/default/batch-components") :GET
             {:handler (fn [response]
                         (let [batchlets (get response :batch-component/batchlet ["No batchlet"])
                               item-readers (get response :batch-component/item-reader ["No item reader"])
                               item-writers (get response :batch-component/item-writer ["No item writer"])
                               item-processors (get response :batch-component/item-processor ["No item processor"])]
                           (defblock batchlet
                             :colour 316
                             :output "Batchlet"
                             :fields [{:type :dropdown
                                          :name "ref"
                                          :label "Batchlet"
                                          :value (map #(->> (repeat %)
                                                            (take 2)) batchlets)}])
                           (defblock reader
                             :colour 45
                             :output "Reader"
                             :fields [{:type :dropdown
                                       :name "ref"
                                       :label "Reader"
                                       :value (map #(->> (repeat %)
                                                         (take 2)) item-readers)}])
                           (defblock writer
                             :colour 45
                             :output "Writer"
                             :fields [{:type :dropdown
                                       :name "ref"
                                       :label "Writer"
                                       :value (map #(->> (repeat %)
                                                         (take 2)) item-writers)}])
                           (defblock processor
                             :colour 45
                             :output "Processor"
                             :fields [{:type :dropdown
                                       :name "ref"
                                       :label "Processor"
                                       :value (map #(->> (repeat %)
                                                         (take 2)) item-processors)}])))})


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

(defblock flow
  :color 40
  :previous-statement? true
  :next-statement? true
  :output "Flow"
  :fields [{:type :text
            :name "name"
            :label "Flow"}])

(defblock split
  :color 50
  :previous-statement? true
  :next-statement? true
  :output "Split"
  :fields [{:type :text
            :name "name"
            :label "Split"}])

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
   :content (when-let [el (get chunk (keyword "chunk" type))]
              [{:tag :block
                :attrs {:type type}
                :content [{:tag :field
                           :attrs {:name "ref"}
                           :content [(get el (keyword type "ref"))]}]}])})

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
             (when-let [step-name (:step/name step)]
               [{:tag :field, :attrs {:name "name"}, :content [step-name]}])
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
                            :content (chunk->xml chunk)}]}])
             (when-let [next-step (:step/next step)]
               [{:tag :next
                 :content [(step->xml (first (filter #(= (:step/name %) next-step) steps)) steps)]}])
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
              (when-let [job-name (:job/name job)]
                [{:tag :field :attrs {:name "name"} :content [job-name]}])
              (when-let [restartable? (:job/restartable? job)]
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


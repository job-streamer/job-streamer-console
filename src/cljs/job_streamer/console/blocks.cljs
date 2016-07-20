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
            [goog.ui.Menu]
            [job-streamer.console.format :as fmt]
            [job-streamer.console.api :as api]
            [Blockly])
  (:use [cljs.reader :only [read-string]]))

(enable-console-print!)

(aset Blockly.Blocks "property-container"
      (clj->js {:init (fn []
                        (this-as
                          this
                          (.. this appendDummyInput (appendField "Properties"))
                          (doto this
                            (.setColour 180)
                            (.appendStatementInput "STACK"))
                          (set! (.-contextMenu this) false)))}))

(aset Blockly.Blocks "property-item"
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
                        (.setAttribute container "items" (aget this "itemCount"))
                        container)))
   :domToMutation (fn [xml]
                    (this-as
                      this
                      (aset this "itemCount"
                            (js/parseInt (.getAttribute xml "items") 10))
                      (.call (aget this "updateShape") this)))
   :decompose (fn [workspace]
                (this-as
                  this
                  (let [container-block (.obtain Blockly.Block workspace (str "property-container"))]
                    (.initSvg container-block)
                    (loop [connection (.. container-block (getInput "STACK") -connection)
                           i 0]
                      (when (< i (.-itemCount this))
                        (let [item-block (.obtain Blockly.Block workspace (str "property-item"))]
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
                      (aset this "itemCount" i)))
                  (.call (aget this "updateShape") this)

                  (loop [i 0]
                    (when (< i (aget this "itemCount"))
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
                    (println this)
                    (if (.getInput this "EMPTY")
                      (.removeInput this "EMPTY")
                      (loop [i 0]
                        (when-let [add-input (.getInput this (str "ADD" i))]
                          (.removeInput this (str "ADD" i))
                          (recur (inc i)))))
                    (if (= (aget this "itemCount") 0)
                      (.. this
                          (appendDummyInput "EMPTY")
                          (appendField "Property"))
                      (loop [i 0]
                        (when (< i (aget this "itemCount"))
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
            :name "components"}])

(let [block (aget Blockly.Blocks "job")]
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
            :acceptable ["Batchlet" "Chunk"]}
           {:type :statement
            :name "transitions"}])

(let [block (aget Blockly.Blocks "step")]
  (doseq [[k f] mutate-behavior]
    (aset block (name k) f)))

(defn- to-dropdown-vals [vs]
  (if-let [values (not-empty
                   (map #(->> (repeat %)
                              (take 2)) vs))]
    values
    [["" ""]]))

(defn get-classes [ch]
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
                                         :value (to-dropdown-vals batchlets)}])
                             (defblock reader
                               :colour 45
                               :output "Reader"
                               :fields [{:type :dropdown
                                         :name "ref"
                                         :label "Reader"
                                         :value (to-dropdown-vals item-readers)}])
                             (defblock writer
                               :colour 45
                               :output "Writer"
                               :fields [{:type :dropdown
                                         :name "ref"
                                         :label "Writer"
                                         :value (to-dropdown-vals item-writers)}])
                             (defblock processor
                               :colour 45
                               :output "Processor"
                               :fields [{:type :dropdown
                                         :name "ref"
                                         :label "Processor"
                                         :value (to-dropdown-vals item-processors)}])
                             (when ch
                               (put! ch true))))}))
(get-classes nil)

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
  :colour 40
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "name"
            :label "Flow"}
           {:type :statement
            :name "components"
            :label ""
            :acceptable ["Step"]}])

(defblock split
  :colour 50
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "name"
            :label "Split"}
           {:type :statement
            :name "components"
            :label ""
            :acceptable ["Flow"]}])

(defblock decision
  :colour 55
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "name"
            :label "Decision"}])

(defblock next
  :colour 50
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "on"
            :label "Next on"}
           {:type :statement
            :name "components"}])

(defblock end
  :colour 50
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "on"
            :label "End on"}
           {:type :text
            :name "exit-status"
            :label "exit status"}])

(defblock fail
  :colour 50
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "on"
            :label "Fail on"}
           {:type :text
            :name "exit-status"
            :label "exit status"}])

(defblock stop
  :colour 50
  :previous-statement? true
  :next-statement? true
  :fields [{:type :text
            :name "on"
            :label "Stop on"}
           {:type :text
            :name "exit-status"
            :label "exit status"}
           {:type :text
            :name "restart"
            :label "restart"}])

(defn emit-element [e]
  (if (= (type e) js/String)
    e
    (if (empty? e)
    ""
      (str "<" (name (:tag e))
         (when-let [attrs (:attrs e)]
           (->> attrs
                (map #(str " " (name (first %)) "='" (second %) "'"))
                (reduce str)))
         (if-let [content (:content e)]
           (apply str ">" (reduce str (map emit-element content)) "</" (name (:tag e)) ">")
           "/>")))))

(declare component->xml)

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

(defn component-name [component]
  (or (:step/name component)
      (:flow/name component)
      (:split/name component)
      (:decision/name component)))

(defn transitions->xml [transitions components]
  (let [transition (first transitions)]
    (cond
      (:next/on transition) {:tag :block
                             :attrs {:type "next"}
                             :content (concat
                                        (when-let [on (:next/on transition)]
                                          [{:tag :field, :attrs {:name "on"}, :content [on]}])
                                        (when-let [to (:next/to transition)]
                                          [{:tag :statement
                                            :attrs {:name "components"}
                                            :content [(component->xml (first to) to)]}])
                                        (when-let [rest-transitions (not-empty (rest transitions))]
                                          [{:tag :next
                                            :content [(transitions->xml rest-transitions components)]}]))}
      (:fail/on transition) {:tag :block
                             :attrs {:type "fail"}
                             :content (concat
                                        (when-let [on (:fail/on transition)]
                                          [{:tag :field :attrs {:name "on"} :content [on]}])
                                        (when-let [exit-status (:fail/exit-status transition)]
                                          [{:tag :field :attrs {:name "exit-status"} :content [exit-status]}])
                                        (when-let [rest-transitions (not-empty (rest transitions))]
                                          [{:tag :next
                                            :content [(transitions->xml rest-transitions components)]}]))}
      (:end/on transition) {:tag :block
                            :attrs {:type "end"}
                            :content (concat
                                       (when-let [on (:end/on transition)]
                                         [{:tag :field :attrs {:name "on"} :content [on]}])
                                       (when-let [exit-status (:fail/end-status transition)]
                                         [{:tag :field :attrs {:name "exit-status"} :contnt [exit-status]}])
                                       (when-let [rest-transitions (not-empty (rest transitions))]
                                         [{:tag :next
                                           :content [(transitions->xml rest-transitions components)]}]))}
      (:stop/on transition) {:tag :block
                             :attrs {:type "stop"}
                             :content (concat
                                        (when-let [on (:stop/on transition)]
                                          [{:tag :field :attrs {:name "on"} :content [on]}])
                                        (when-let [exit-status (:stop/exit-status transition)]
                                          [{:tag :field :attrs {:name "exit-status"} :contnt [exit-status]}])
                                        (when-let [restart (:stop/restart transition)]
                                          [{:tag :field :attrs {:name "restart"} :contnt [restart]}])
                                        (when-let [rest-transitions (not-empty (rest transitions))]
                                          [{:tag :next
                                            :content [(transitions->xml rest-transitions components)]}]))}
      )))

(defn flow->xml [flow components]
  {:tag :block
   :attrs {:type "flow"}
   :content (concat
              (when-let [flow-name (:flow/name flow)]
                [{:tag :field
                  :attrs {:name "name"}
                  :content [flow-name]}])
              (when-let [next (not-empty (:flow/next flow))]
                [{:tag :next
                  :content [(component->xml (first (filter #(= (component-name %) next) components)) components)]}])
              (when-let [components (not-empty (:flow/components flow))]
                (let [component (first components)]
                  [{:tag :statement
                    :attrs {:name "components"}
                    :content [(component->xml (first (filter #(= (component-name %) (component-name component)) components)) components)]}])))})

(defn split->xml [split components]
  {:tag :block
   :attrs {:type "split"}
   :content (concat
              (when-let [split-name (:split/name split)]
                [{:tag :field
                  :attrs {:name "name"}
                  :content [split-name]}])
              (when-let [next (:split/next split)]
                [{:tag :next
                  :content [(component->xml (first (filter #(= (component-name %) next) components)) components)]}])
              (when-let [components (not-empty (:split/components split))]
                (let [component (first components)]
                  [{:tag :statement
                    :attrs {:name "components"}
                    :content [(component->xml (first (filter #(= (component-name %) (component-name component)) components)) components)]}])))})

(defn step->xml [step components]
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
                  :content [(component->xml (first (filter #(= (:step/name %) next-step) components)) components)]}])
              (when-let [transitions (not-empty (:step/transitions step))]
                [{:tag :statement
                  :attrs {:name "transitions"}
                  :content [(transitions->xml transitions components)]}])
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

(defn component->xml [component components]
  (cond
    (:step/name  component) (step->xml  component components)
    (:flow/name  component) (flow->xml  component components)
    (:split/name component) (split->xml component components)))

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
                (when-let [components (not-empty (:job/components job))]
                  [{:tag :statement
                    :attrs {:name "components"}
                    :content [(component->xml (first components) components)]}])
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

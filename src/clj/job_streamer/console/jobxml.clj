(ns job-streamer.console.jobxml
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml])
  (:import [org.jsoup Jsoup]))

(defn field-value [el field-name]
  (some-> el
          (.children)
          (.select (str "field[name=" field-name "]"))
          first
          (.text)))

(declare xml->components)
(declare xml->component)

(defn item-component [el type]
  (when-let [block (some-> el
                           (.select (str "> value[name=" type "] >block[type=" type "]"))
                           first)]
    {(keyword type "ref") (field-value block "ref")}))

(defn xml->chunk [chunk]
  {:chunk/reader    (item-component chunk "reader")
   :chunk/processor (item-component chunk "processor")
   :chunk/writer    (item-component chunk "writer")})

(defn xml->batchlet [batchlet]
  {:batchlet/ref (field-value batchlet "ref")})

(defn xml->property [prop]
  (when-let [prop-block (some-> prop
                                (.select "> block[type=property]")
                                first)]
    {(keyword (field-value prop-block "name")) (field-value prop-block "value")}))

(defn xml->next [next]
  {:next/on (field-value next "on")
   :next/to (xml->components next)})

(defn xml->fail [fail]
  {:fail/on (field-value fail "on")
   :fail/exit-status (field-value fail "exit-status")})

(defn xml->end [end]
  {:end/on (field-value end "on")
   :end/exit-status (field-value end "exit-status")})

(defn xml->stop [stop]
  {:stop/on (field-value stop "on")
   :stop/exit-status (field-value stop "exit-status")
   :stop/restart (field-value stop "restart")})

(defn xml->transition [el]
  (case (.attr el "type")
    "next" (xml->next el)
    "end"  (xml->end  el)
    "fail" (xml->fail el)
    "stop" (xml->stop el)))

(defn xml->transitions [first-block]
  (loop [block first-block transitions []]
    (let [transition (xml->transition block)]
      (if-let [next-block (first (.select block "> next > block[type~=(next|end|fail|stop)]"))]
        (recur next-block (conj transitions transition))
        (conj transitions transition)))))

(defn xml->flow [flow]
  (merge
   {:flow/name (field-value flow "name")
    :flow/components (xml->components flow)}
   (when-let [next-step (some-> flow
                                (.select "> next > block[type~=(step|flow|split|decision)]")
                                first
                                (field-value "name"))]
     {:flow/next next-step})))

(defn xml->split [split]
  (merge
   {:split/name (field-value split "name")
    :split/components (xml->components split)}
   (when-let [next-step (some-> split
                                (.select "> next > block[type~=(step|flow|split|decision)]")
                                first
                                (field-value "name"))]
     {:split/next next-step})))

(defn xml->step [step]
  (merge
   {:step/name (field-value step "name")
    :step/properties (some->> (.select step "> value[name^=ADD]")
                                  (map (fn [prop] (xml->property prop)))
                                  (reduce merge))}
   (when-let [batchlet (some-> step
                               (.select "> value[name=step-component] > block[type=batchlet]")
                               first
                               xml->batchlet)]
     {:step/batchlet batchlet})
   (when-let [chunk (some-> step
                            (.select "> value[name=step-component] > block[type=chunk]")
                            first
                            xml->chunk)]
     {:step/chunk chunk})
   (when-let [next-step (some-> step
                                (.select "> next > block[type~=(step|flow|split|decision)]")
                                first
                                (field-value "name"))]
     {:step/next next-step})
   (when-let [transition (first (.select step "> statement[name=transitions] > block[type~=(next|end|fail|stop)]"))]
     {:step/transitions (xml->transitions transition)})))

(defn xml->component [el]
  (case (.attr el "type")
    "step"     (xml->step el)
    "flow"     (xml->flow el)
    "split"    (xml->split el)
    ;; "decision" (xml->decision el) TODO will support
    ))

(defn xml->components [el]
  (loop [block (first (.select el "> statement[name=components] > block[type~=(step|flow|split|decision)]"))
         components []]
    (when (nil? block)
      (throw (Exception. (str (.attr el "type")  " must have one component at least."))))
    (let [component (xml->component block)]
      (if-let [next-block (first (.select block "> next > block[type~=(step|flow|split|decision)]"))]
        (recur next-block (conj components component))
        (conj components component)))))

(defn xml->job [xml]
  (let [doc (Jsoup/parse xml)
        job-els (. doc select "xml > block[type=job]")]
    (if (= (count job-els) 1)
      (let [job (first job-els)]
        {:job/name (field-value job "name")
         :job/components (xml->components job)
         :job/properties (some->> (.select job "> value[name^=ADD]")
                                  (map (fn [prop] (xml->property prop)))
                                  (reduce merge))})
      (throw (IllegalStateException. "Block must be one.")))))



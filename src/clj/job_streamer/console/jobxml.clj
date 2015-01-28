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

(defn item-component [el type]
  (when-let [block (some-> chunk
                           (.select (str "block[type=" type "]"))
                           first)]
    {:ref (field-value block "ref")}))

(defn xml->chunk [chunk]
  {:chunk/reader    (item-component "reader")
   :chunk/processor (item-component "processor")
   :chunk/writer    (item-component "writer")})

(defn xml->batchlet [batchlet]
  {:batchlet/ref (field-value batchlet "ref")})

(defn xml->step [step]
  (merge
   {:step/id (field-value step "id")}
   (when-let [batchlet (some-> step
                               (.select "block[type=batchlet]")
                               first
                               xml->batchlet)]
     {:step/batchlet batchlet})
   (when-let [chunk (some-> step
                            (.select "block[type=chunk]")
                            first
                            xml->chunk)])
   (when-let [next-step (some-> step
                                (.select "next > block[type=step]")
                                first
                                (field-value "id"))]
     {:step/next next-step})))

(defn xml->job [xml]
  (let [doc (Jsoup/parse xml)
        job-els (. doc select "xml > block[type=job]")]
    (if (= (count job-els) 1)
      (let [job (first job-els)]
        {:job/id (field-value job "id")
         :job/steps (some->> (.select job "> statement[name=steps] block[type=step]")
                         (map (fn [step] (xml->step step)))
                         vec)})
      (throw (IllegalStateException. "Block must be one.")))))



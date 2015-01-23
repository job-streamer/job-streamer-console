(ns job-streamer.console.jobxml
  (:require [clojure.java.io :as io])
  (:import [org.jsoup Jsoup] ))

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

(defn deserialize-chunk [chunk]
  {:reader    (item-component "reader")
   :processor (item-component "processor")
   :writer    (item-component "writer")})

(defn deserialize-batchlet [batchlet]
  {:ref (field-value batchlet "ref")})

(defn deserialize-step [step]
  (merge
   {:id (field-value step "id")}
   (when-let [batchlet (some-> step
                               (.select "block[type=batchlet]")
                               first
                               deserialize-batchlet)]
     {:batchlet batchlet})
   (when-let [chunk (some-> step
                            (.select "block[type=chunk]")
                            first
                            deserialize-chunk)])))

(defn deserialize-job [job]
  {:id (field-value job "id")
   :steps (some->> (.select job "> statement[name=steps] block[type=step]")
                   (map (fn [step] (deserialize-step step)))
                   vec)})

(defn deserialize [xml]
  (let [doc (Jsoup/parse xml)
        job-els (. doc select "xml > block[type=job]")]
    (if (= (count job-els) 1)
      (deserialize-job (first job-els))
      (throw (IllegalStateException. "Block must be one.")))))


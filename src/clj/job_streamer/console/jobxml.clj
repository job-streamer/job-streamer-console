(ns job-streamer.console.jobxml
  (:require [clojure.xml :as xml])
  (:import [java.io ByteArrayInputStream]))

(defn xml->job [xml]
  (let [doc (xml/parse (ByteArrayInputStream. (.getBytes xml)))
        job-els (->> doc
                     :content
                     (filter #(= (:tag %) :jsr352:job)))]
    (if (= (count job-els) 1)
      (let [job-name (-> job-els
                         first
                         (get-in [:attrs :bpmn:name]))]
        {:job/name job-name})
      (throw (IllegalStateException. "Block must be one.")))))

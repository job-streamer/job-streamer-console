(ns job-streamer.console.api
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
            [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.string :as gstring]
            [goog.ui.Component])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.net EventType]
           [goog.events KeyCodes]))

(def control-bus-url (.. js/document
                         (querySelector "meta[name=control-bus-url]")
                         (getAttribute "content")))

(defn- build-uri [path]
  (if (gstring/startsWith path "/")
    (str control-bus-url path)
    path))

(defn request
  ([path]
   (request path :GET nil {}))
  ([path options]
   (request path :GET nil options))
  ([path method options]
   (request path method nil options))
  ([path method body {:keys [handler error-handler format]}]
   (let [xhrio (net/xhr-connection)]
     (when handler
       (events/listen xhrio EventType.SUCCESS
                      (fn [e]
                        (let [res (read-string (.getResponseText xhrio))]
                          (handler res)))))
     (when error-handler
       (events/listen xhrio EventType.ERROR
                      (fn [e]
                        (let [res (read-string (.getResponseText xhrio))]
                          (error-handler res)))))
     (.send xhrio (build-uri path) (.toLowerCase (name method))
            body
            (case format
              :xml (clj->js {:content-type "application/xml"})
              (clj->js {:content-type "application/edn"}))))))


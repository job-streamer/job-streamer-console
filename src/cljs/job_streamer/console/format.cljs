(ns job-streamer.console.format
  (:require [goog.date.duration :as dr]
            [goog.string :as gstring])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-s (DateTimeFormat. goog.i18n.DateTimeFormat.Format.SHORT_DATETIME
                                    (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(def date-format-m (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                    (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn date-short [d]
  (when d
    (.format date-format-s d)))

(defn date-medium [d]
  (when d
    (.format date-format-m d)))

(defn duration [msec]
  (if (< msec 60000)
      (gstring/format "%.3f secs" (/ msec 1000))
      (dr/format msec)))

(defn duration-between [start end]
  (if start
    (let [msec (- (if end (.getTime end) (.getTime (js/Date.)))
                  (.getTime start))]
      (duration msec))
    0))



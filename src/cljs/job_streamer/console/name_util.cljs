(ns job-streamer.console.name-util)

(defn split-name [string cnt]
  (if (> (count string) cnt)
    (str (subs string 0 cnt) "...")
    string))

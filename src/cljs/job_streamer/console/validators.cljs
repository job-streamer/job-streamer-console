(ns job-streamer.console.validators
  (:require-macros [bouncer.validators :refer [defvalidator]]))

(defvalidator more-than-one
  {:default-message-format "%s must be more than one."}
  [coll]
  (not-empty coll))

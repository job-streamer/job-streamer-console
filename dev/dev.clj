(ns dev
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [duct.generate :as gen]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init start stop go reset]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [duct.component.figwheel :as figwheel]
            [dev.tasks :refer :all]
            [job-streamer.console.config :as config]
            [job-streamer.console.system :as system]))

(def dev-config
  {:app {:middleware [wrap-stacktrace]}
   :figwheel
   {:css-dirs ["resources/job-streamer-console/public/css"]
    :builds   [{:source-paths ["src/cljs" "dev"]
                :build-options
                {:optimizations :none
                 :main "cljs.user"
                 :asset-path "/js"
                 :libs ["resources/closure-js/libs"]
                 :output-to  "target/figwheel/job-streamer-console/public/js/job-streamer.js"
                 :output-dir "target/figwheel/job-streamer-console/public/js"
                 :source-map true
                 :source-map-path "/js"}}
               {:source-paths ["src/cljs-flowchart"]
                :build-options
                {:optimizations :none
                 :main "job-streamer.console.flowchart"
                 :asset-path "/js-flowchart"
                 :output-to  "target/figwheel/job-streamer-console/public/js/flowchart.js"
                 :output-dir "target/figwheel/job-streamer-console/public/js-flowchart"
                 :source-map true
                 :source-map-path "/js-flowchart"}}]}})

(def config
  (meta-merge config/defaults
              config/environ
              dev-config))

(defn new-system []
  (into (system/new-system config)
        {:figwheel (figwheel/server (:figwheel config))}))

(when (io/resource "dev/local.clj")
  (load "dev/local"))

(gen/set-ns-prefix 'job-streamer.console)

(reloaded.repl/set-init! new-system)



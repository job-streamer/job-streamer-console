(defproject net.unit8.job-streamer/job-streamer-console "0.1.0-SNAPSHOT"
  :source-paths ["src/clj"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [hiccup "1.0.5"]
                 [garden "1.2.5"]
                 [compojure "1.3.1"]
                 [org.jsoup/jsoup "1.8.1"]

                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.2.22"]
                 [prismatic/om-tools "0.3.6"]
                 [om "0.7.3"]]
  :plugins [[lein-ring "0.8.13"]
            [lein-cljsbuild "1.0.3"]]

  :ring {:handler job-streamer.console.core/app}
  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src/cljs/timeline"]
                :compiler {:output-to "resources/public/js/timeline.js"
                           :optimizations :simple}}
               {:id "job-blocks"
                :source-paths ["src/cljs/blocks"]
                :compiler {:output-to "resources/public/js/blocks.js"
                           :optimizations :simple}}
               {:id "jobs"
                :source-paths ["src/cljs/jobs"]
                :compiler {:output-to "resources/public/js/jobs.js"
                           :optimizations :simple}}]})

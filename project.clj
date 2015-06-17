(defproject net.unit8.job-streamer/job-streamer-console "0.2.0-SNAPSHOT"
  :source-paths ["src/clj"]
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [hiccup "1.0.5"]
                 [garden "1.2.5" :exclusions [org.clojure/clojure]]
                 [compojure "1.3.4"]
                 [environ "1.0.0"]
                 [org.jsoup/jsoup "1.8.2"]

                 [org.clojure/clojurescript "0.0-3308"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.3.4"]
                 [prismatic/om-tools "0.3.11"]
                 [bouncer "0.3.3"]
                 [secretary "1.2.3"]
                 [org.omcljs/om "0.8.8"]]
  :plugins [[lein-ring "0.9.1"]
            [lein-cljsbuild "1.0.5"]
            [lein-environ "1.0.0"]]

  :ring {:handler job-streamer.console.core/app}

  :profiles {:dev {:env {:dev true}}}
  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src/cljs"]
                :compiler {:output-to "resources/public/js/extern/job-streamer.js"
                           :pretty-print true
                           :optimizations :simple}}
               {:id "production"
                :source-paths ["src/cljs"]
                :compiler {:output-to "resources/public/js/extern/job-streamer.min.js"
                           :output-dir "resources/public/js/extern"
                           :pretty-print false
                           :optimizations :advanced
                           :source-map "resources/public/js/extern/job-streamer.min.js.map"}}]})

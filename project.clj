(defproject net.unit8.job-streamer/job-streamer-console "0.1.0-SNAPSHOT"
  :source-paths ["src/clj"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [hiccup "1.0.5"]
                 [garden "1.2.5"]
                 [compojure "1.3.1"]
                 [environ "1.0.0"]
                 [org.jsoup/jsoup "1.8.1"]

                 [org.clojure/clojurescript "0.0-2913"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.3.1"]
                 [prismatic/om-tools "0.3.11"]
                 [bouncer "0.3.2"]
                 [secretary "1.2.1"]
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
                           :pretty-print true
                           :optimizations :advanced}}]})

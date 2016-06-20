(defproject net.unit8.job-streamer/job-streamer-console (clojure.string/trim-newline (slurp "VERSION"))
  :source-paths ["src/clj"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [hiccup "1.0.5"]
                 [garden "1.3.2" :exclusions [org.clojure/clojure]]
                 [compojure "1.5.1"]
                 [environ "1.0.3"]
                 [org.jsoup/jsoup "1.9.2"]

                 [com.stuartsierra/component "0.3.1"]
                 [duct "0.7.0"]
                 [meta-merge "1.0.0"]

                 [org.clojure/clojurescript "1.9.76"]
                 [org.clojure/core.async "0.2.374"]
                 [sablono "0.7.2"]
                 [prismatic/om-tools "0.4.0"]
                 [bouncer "1.0.0"]
                 [secretary "1.2.3"]
                 [org.omcljs/om "1.0.0-alpha36"]

                 [ring "1.4.0"]
                 [ring/ring-defaults "0.2.0"]
                 [ring-jetty-component "0.3.1"]]
  :plugins [[lein-cljsbuild "1.1.2"]
            [lein-environ "1.0.3"]]

  :ring {:handler job-streamer.console.core/app}

  :main ^:skip-aot job-streamer.console.main
  :target-path "target/%s/"
  :resource-paths ["resources" "target/cljsbuild"]
  :prep-tasks [["javac"] ["cljsbuild" "once"] ["compile"]]
  
  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs"]
     :compiler {:output-to "target/cljsbuild/job-streamer-console/public/js/job-streamer.js"
                :pretty-print true
                :optimizations :simple}}
    {:id "production"
     :source-paths ["src/cljs"]
     :compiler {:output-to "resources/job-streamer-console/public/js/job-streamer.min.js"
                :pretty-print false
                :optimizations :advanced}}]}

  :aliases {"run-task" ["with-profile" "+repl" "run" "-m"]
            "setup"    ["run-task" "dev.tasks/setup"]}
  
  :pom-plugins [[org.apache.maven.plugins/maven-assembly-plugin "2.5.5"
                 {:configuration [:descriptors [:descriptor "src/assembly/dist.xml"]]}]]

  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :repl {:resource-paths ^:replace ["resources" "target/figwheel"]
          :prep-task      ^:replace [["javac"] ["compile"]]}
   :uberjar {:aot :all}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:dependencies [[duct/generate "0.7.0"]
                                  [reloaded.repl "0.2.2"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [org.clojure/tools.nrepl "0.2.12"]
                                  [eftest "0.1.1"]
                                  [com.gearswithingears/shrubbery "0.3.1"]
                                  [kerodon "0.7.0"]
                                  [binaryage/devtools "0.6.1"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [duct/figwheel-component "0.3.2"]
                                  [figwheel "0.5.0-6"]]
                   :source-paths ["dev"]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :env {:dev true
                         :port "3000"}}
   :project/test  {}})

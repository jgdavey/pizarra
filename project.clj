(defproject pizarra "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :source-paths ["src/clj"]

  :test-paths ["spec/clj"]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2511" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha" :scope "provided"]
                 [ring "1.3.2"]
                 [ring/ring-defaults "0.1.2"]
                 [compojure "1.3.1"]
                 [enlive "1.1.5"]
                 [om "0.8.0-rc1"]
                 [com.cognitect/transit-cljs "0.8.202"]
                 [environ "1.0.0"]]

  :plugins [[lein-cljsbuild "1.0.3"]
            [lein-environ "1.0.0"]]

  :min-lein-version "2.5.0"

  :uberjar-name "pizarra.jar"

  :cljsbuild {:builds {:app {:source-paths ["src/cljs"]
                             :compiler {:output-to     "resources/public/js/app.js"
                                        :output-dir    "resources/public/js/out"
                                        :source-map    "resources/public/js/out.js.map"
                                        :preamble      ["libs/mousetrap.min.js" "react/react.min.js"]
                                        :externs       ["libs/mousetrap.externs.js"]
                                        :optimizations :none
                                        :pretty-print  true}}}}

  :profiles {:dev {:source-paths ["env/dev/clj"]

                   :dependencies [[figwheel "0.1.6-SNAPSHOT"]
                                  [com.cemerick/piggieback "0.1.3"]
                                  [weasel "0.4.2"]
                                  [leiningen "2.5.0"]]

                   :repl-options {:init-ns pizarra.server
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

                   :plugins [[lein-figwheel "0.1.6-SNAPSHOT"]]

                   :figwheel {:http-server-root "public"
                              :server-port 3449
                              :css-dirs ["resources/public/css"]}

                   :env {:is-dev true}

                   :cljsbuild {:builds
                               {:app
                                {:source-paths ["env/dev/cljs"]}}}}

             :uberjar {:source-paths ["env/prod/clj"]
                       :hooks [leiningen.cljsbuild]
                       :env {:production true}
                       :omit-source true
                       :aot :all
                       :cljsbuild {:builds {:app
                                            {:source-paths ["env/prod/cljs"]
                                             :compiler
                                             {:optimizations :advanced
                                              :output-to     "build/js/app.js"
                                              :output-dir    "build/js/out"
                                              :source-map    "build/js/out.js.map"
                                              :pretty-print  false}}}}}})

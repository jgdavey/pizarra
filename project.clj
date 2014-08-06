(defproject pizarra "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2277"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]

                 ;; CLJ
                 [ring/ring-core "1.3.0"]
                 [compojure "1.1.8"]

                 ;; CLJS
                 [om "0.7.1"]]

  :plugins [[lein-cljsbuild "1.0.3"]
            [lein-ring "0.8.11"]
            [lein-pdo "0.1.1"]]

  :aliases {"dev" ["pdo" "cljsbuild" "auto" "dev," "ring" "server-headless"]}

  :source-paths ["src/clj"]

  :ring {:handler pizarra.server/app
         :init    pizarra.server/init}

  :profiles {:dev {:plugins [[com.cemerick/austin "0.1.4"]]}}

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/cljs"]
                        :compiler {:output-to "resources/public/js/pizarra.js"
                                   :output-dir "resources/public/js/out"
                                   :optimizations :none
                                   :source-map true
                                   :externs ["react/externs/react.js"]}}]}
)

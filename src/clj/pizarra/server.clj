(ns pizarra.server
    (:require [compojure.handler :as handler]
              [compojure.route :as route]
              [compojure.core :refer [GET defroutes]]
              [ring.util.response :as resp]
              [clojure.java.io :as io]))

(defroutes app-routes
  (GET "/" [] (resp/redirect "/index.html"))
  (route/resources "/")
  (route/not-found "Page not found"))

(def app
  (-> #'app-routes
      (handler/api)))

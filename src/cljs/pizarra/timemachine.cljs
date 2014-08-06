(ns pizarra.timemachine)

(def app-history (atom []))
(def app-future (atom []))

(defn forget-everything! []
  (reset! app-future [])
  (reset! app-history []))

(defn init-history [app-state]
  (forget-everything!)
  (reset! app-history [@app-state]))

(defn undo-is-possible []
  (> (count @app-history) 1))

(defn redo-is-possible []
  (> (count @app-future) 0))

(defn push-onto-undo-stack [new-state]
  (let [old-watchable-app-state (peek @app-history)]
    (when-not (= old-watchable-app-state new-state)
      (swap! app-history conj new-state))))

(defn do-undo [app-state]
  (when (undo-is-possible)
    (swap! app-future conj (peek @app-history))
    (swap! app-history pop)
    (reset! app-state (peek @app-history))))

(defn do-redo [app-state]
  (when (redo-is-possible)
    (reset! app-state (peek @app-future))
    (push-onto-undo-stack (peek @app-future))
    (swap! app-future pop)))

(defn handle-transaction [tx-data root-cursor]
  (when (= (:tag tx-data) :add-to-undo)
    (reset! app-future [])
    (push-onto-undo-stack (:new-state tx-data))))

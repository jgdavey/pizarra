(ns pizarra.timemachine)

(defrecord History [states current-idx])

(def ^:private blank-history (->History [] -1))

(def app-history (atom blank-history))

(defn forget-everything! []
  (reset! app-history blank-history))

(defn push-state! [app-state]
  (swap! app-history
         (fn [h]
           (->History (conj (:states h) app-state)
                      (inc (:current-idx h))))))

(defn undo-is-possible []
  (> (:current-idx @app-history) 0))

(defn redo-is-possible []
  (< (:current-idx @app-history)
     (count (:states @app-history))))

(defn push-onto-undo-stack [new-state]
  (let [old-watchable-app-state (peek (:states @app-history))]
    (when-not (= old-watchable-app-state new-state)
      (push-state! new-state))))

(defn jump-to-history [app-state idx]
  (when-let [new-state (nth (:states @app-history) idx)]
    (swap! app-history assoc :current-idx idx)
    (reset! app-state new-state)))

(defn handle-transaction [tx-data root-cursor]
  (when (= (:tag tx-data) :add-to-undo)
    (.log js/console (clj->js @app-history))
    (swap! app-history (fn [h]
                         (let [i (:current-idx h)]
                           (->History (vec (take (inc i) (:states h))) i))))
    (push-onto-undo-stack (:new-state tx-data))))

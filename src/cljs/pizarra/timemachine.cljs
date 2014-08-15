(ns pizarra.timemachine)

(defrecord History [states current-idx])

(def ^:private blank-history (->History [] -1))

(def app-history (atom blank-history))

(defn forget-everything! []
  (reset! app-history blank-history))

(defn- current-state []
  (let [h @app-history
        i (:current-idx h)]
    (when (pos? i)
      (nth (:states h) i))))

(defn- push-state* [app-state]
  (swap! app-history
         (fn [h]
           (let [i (:current-idx h)
                 states (vec (take (inc i) (:states h)))]
             (->History (conj states app-state) (inc i))))))

(defn push-state! [new-state]
  "Always throws away app 'future'"
  (when-not (= (current-state) new-state)
    (push-state* new-state)))

(defn undo-is-possible []
  (> (:current-idx @app-history) 0))

(defn redo-is-possible []
  (< (:current-idx @app-history)
     (dec (count (:states @app-history)))))

(defn jump-to-history!
  "Moves history cursor to specified index.
  Returns state at index, or nil."
  [idx]
  (when-let [new-state (nth (:states @app-history) idx)]
    (swap! app-history assoc :current-idx idx)
    new-state))

(defn undo []
  (when (undo-is-possible)
    (jump-to-history! (dec (:current-idx @app-history)))))

(defn redo []
  (when (redo-is-possible)
    (jump-to-history! (inc (:current-idx @app-history)))))

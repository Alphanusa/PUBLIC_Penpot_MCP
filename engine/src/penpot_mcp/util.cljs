;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.util
  "Small helpers shared by the edit namespaces."
  (:require
   [cuerdas.core :as str]))

(defn error!
  "Throws an input error. `:invalid-op` errors are the caller's fault: they
  are reported as is and never reset the render-wasm module."
  [msg data]
  (throw (ex-info msg (assoc data :type :invalid-op))))

(defn levenshtein
  "Edit distance between strings `a` and `b`."
  [a b]
  (let [a (str a)
        b (str b)
        n (count b)]
    (loop [i 0
           prev (vec (range (inc n)))]
      (if (= i (count a))
        (peek prev)
        (let [ca  (nth a i)
              row (reduce (fn [row j]
                            (let [cost (if (= ca (nth b j)) 0 1)]
                              (conj row (min (inc (peek row))
                                             (inc (nth prev (inc j)))
                                             (+ (nth prev j) cost)))))
                          [(inc i)]
                          (range n))]
          (recur (inc i) row))))))

(defn normalize
  "Lower case without spaces, dashes and underscores: \"IBM Plex Sans\",
  \"ibm-plex-sans\" and \"IBMPlexSans\" compare equal."
  [s]
  (-> (str s) str/lower (str/replace #"[\s_\-]+" "")))

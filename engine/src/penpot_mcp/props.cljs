;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.props
  "Which properties each edit operation accepts, with readable errors for
  the others.

  Operations come from an AI client as JSON. A key the engine does not read
  must never be dropped silently: the client would believe the change was
  applied and move on. Every op — and its nested `layout`, `stroke`, `sizing`
  and `fill` objects — is checked here before it is applied, dry runs
  included."
  (:require
   [cuerdas.core :as str]
   [penpot-mcp.util :as u]))

;; --- property sets (the keys `penpot-mcp.edit` reads)

(def layout-props
  [:type :direction :gap :rowGap :columnGap :padding :alignItems :justifyContent
   :alignContent :wrap])

(def stroke-props [:color :width :align :opacity])
(def sizing-props [:h :v])
(def fill-props [:color :image :fit :opacity])

(def ^:private geometry-props [:x :y :width :height])
(def ^:private common-props [:name :opacity :visible :stroke :sizing :tokens])
(def ^:private box-fill-props [:fill :fillOpacity])
(def ^:private text-props
  [:text :typography :fontFamily :fontSize :fontWeight :fontStyle :textColor :fill :textAlign
   :lineHeight :letterSpacing :textTransform :growType])

(def ^:private shape-props
  "Props per shape type. `:image` is the `type: \"image\"` sugar of create
  (a rectangle with an image fill)."
  {:frame  (vec (concat geometry-props common-props box-fill-props [:radius :clip :layout]))
   :rect   (vec (concat geometry-props common-props box-fill-props [:radius]))
   :circle (vec (concat geometry-props common-props box-fill-props))
   :text   (vec (concat geometry-props common-props text-props))
   :image  (vec (concat geometry-props common-props [:image :fit :fillOpacity :radius]))
   ;; `type: "component"` of create: a copy of a library component
   :component [:component :variant :texts :x :y :width :height :name :opacity :visible :sizing]
   ;; groups, paths, booleans, … (update only)
   :other  (vec (concat geometry-props common-props box-fill-props))})

(defn- props-for [type] (or (get shape-props type) (:other shape-props)))

(def ^:private op-props
  {:create [:op :type :ref :parent]
   :update [:op :id]
   :delete [:op :id]})

(def ^:private type-names
  {:frame "board (frame)" :rect "rectangle" :circle "ellipse" :text "text" :image "image"
   :component "component instance"})

(def ^:private any-shape-prop
  (into #{} (mapcat val) shape-props))

(defn- type-name [type] (or (get type-names type) (some-> type name)))

(def ^:private layout-prop? (set layout-props))

;; --- suggestions

(defn- lower [k] (str/lower (name k)))

(defn suggestion
  "The allowed key a misspelled `k` most likely meant, or nil: same key in
  another case (`textcolor`), a small typo (`fontSzie`), or a prefix
  (`justify` -> `justifyContent`)."
  [k allowed]
  (let [lk (lower k)]
    (or (some #(when (= lk (lower %)) %) allowed)
        (->> allowed
             (map (fn [c] [c (u/levenshtein lk (lower c))]))
             (filter (fn [[c d]] (<= d (max 1 (quot (count (name c)) 5)))))
             (sort-by second)
             (ffirst))
        (when (>= (count lk) 4)
          (some #(when (str/starts-with? (lower %) lk) %) allowed)))))

(defn- quoted [k] (str "\"" (name k) "\""))

(defn- allowed-list [allowed] (str/join ", " (map name allowed)))

(defn check-keys!
  "Throws an `invalid-op` error for the first key of map `m` not in
  `allowed`. `where` completes the message (\"for text\", \"in layout\"),
  `hint-fn` (optional) returns an extra hint for a key."
  ([m allowed where] (check-keys! m allowed where nil))
  ([m allowed where hint-fn]
   (let [allowed? (set allowed)]
     (doseq [k (keys m)
             :when (not (allowed? k))]
       (let [hint (or (when hint-fn (hint-fn k))
                      (when-let [s (suggestion k allowed)]
                        (str "Did you mean " (quoted s) "?")))]
         (u/error! (str "Unknown property " (quoted k) " " where "."
                        (when hint (str " " hint))
                        " Allowed: " (allowed-list allowed) ".")
                   {:field (name k)}))))))

;; --- shape ops

(defn- shape-hint
  "Why `k` is not accepted on this op, when we can tell."
  [kind type k]
  (let [tname (type-name type)]
    (cond
      (and (= kind :update) (= k :type))
      "The type of an existing shape cannot be changed: delete it and create a new one."

      (and (= kind :update) (= k :ref))
      "Refs are given on create; update and delete take the shape \"id\" (or the ref, as \"id\")."

      (and (= kind :update) (= k :parent))
      (str "To move a shape to another parent or reorder it use the move op: "
           "{\"op\": \"move\", \"id\": …, \"parent\": …, \"index\": …}.")

      (layout-prop? k)
      (if (= type :frame)
        (str (quoted k) " belongs inside \"layout\": {" (quoted k) ": …}.")
        (str (quoted k) " is a flex layout property: set it inside \"layout\" on a board (frame)."))

      (and (#{:image :fit} k) (#{:frame :rect :circle} type))
      (str "To fill a " tname " with an image use \"fill\": {\"image\": <media id>, \"fit\": \"cover\"}.")

      (and (= k :fill) (= type :image))
      "Images take \"image\" (a media id from upload_image) and \"fit\"."

      (= type :component)
      (str "A component instance takes the component's look; change " (quoted k)
           " with an update on the instance or one of its layers after creating it.")

      (any-shape-prop k)
      (str (quoted k) " is not supported on " tname ".")

      :else
      (when-let [s (suggestion k layout-props)]
        (when (= type :frame)
          (str "Did you mean \"layout\": {" (quoted s) ": …}?"))))))

(defn check-shape-op!
  "Validates the keys of a shape op. `kind` is :create, :update or :delete;
  `type` the shape type (:frame :rect :circle :text, or :image for the image
  sugar of create); ignored for delete."
  [kind type op]
  (if (= kind :delete)
    (check-keys! op (:delete op-props) "for delete")
    (let [allowed (into (get op-props kind) (props-for type))]
      (check-keys! op allowed (str "for " (type-name type))
                   (fn [k]
                     (or (shape-hint kind type k)
                         (when-let [s (suggestion k allowed)]
                           (str "Did you mean " (quoted s) "?")))))
      (when (map? (:layout op))
        (check-keys! (:layout op) layout-props "in \"layout\""))
      (when (map? (:stroke op))
        (check-keys! (:stroke op) stroke-props "in \"stroke\""))
      (when (map? (:sizing op))
        (check-keys! (:sizing op) sizing-props "in \"sizing\""))
      (when (map? (:fill op))
        (check-keys! (:fill op) fill-props "in \"fill\"")))))

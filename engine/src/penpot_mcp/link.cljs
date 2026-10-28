;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.link
  "linkAssets: links the raw colors and text styles already used on a page to
  the library colors and typographies with the same values, as if they had
  been applied from the assets panel. Penpot has no such action, and a shape
  whose fill is a plain #rrggbb does not follow later edits of a library
  color with that value.

  A value is linked only when exactly one library asset matches it — colors
  on hex + opacity, typographies on font, weight, style, size, line height,
  letter spacing and text transform. Ambiguous and unmatched values are
  reported with the number of shapes using them, never guessed. Values that
  are already linked, and shapes inside component copies (they follow their
  main component), are left as they are. Only the refs are set: the values
  themselves never change."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.text :as txt]
   [cuerdas.core :as str]
   [penpot-mcp.library :as library]
   [penpot-mcp.util :as u]))

;; --- values

(defn- round3 [v] (/ (js/Math.round (* v 1000)) 1000))

(defn- num
  "A number from a number or a numeric string (text styles are strings),
  rounded to 3 decimals; `default` when missing."
  [v default]
  (let [n (cond (number? v) v
                (string? v) (js/parseFloat v)
                :else js/NaN)]
    (if (js/isFinite n) (round3 n) default)))

(defn- color-key [color opacity] [(str/lower color) (num opacity 1)])

(defn- color-label
  [[hex opacity]]
  (if (= 1 opacity) hex (str hex " " (round3 (* opacity 100)) "%")))

(defn- family-name
  [family]
  (if (= "sourcesanspro" family) "Source Sans Pro" (str family)))

(defn- typo-key
  "What makes two text styles the same typography."
  [{:keys [font-id font-family font-weight font-style font-size line-height letter-spacing text-transform]}]
  [(if (some? font-id) (str font-id) (u/normalize font-family))
   (str (or font-weight "400"))
   (or (some-> font-style name) "normal")
   (num font-size nil)
   (num line-height 1.2)
   (num letter-spacing 0)
   (let [t (some-> text-transform name)] (if (str/blank? t) "none" t))])

(defn- typo-label
  [node]
  (let [[_ weight style size line-height spacing transform] (typo-key node)]
    (str (family-name (:font-family node)) " " weight (when (= "italic" style) " italic")
         " " size "/" line-height
         (when-not (zero? spacing) (str ", letter spacing " spacing))
         (when (not= "none" transform) (str ", " transform)))))

;; --- candidates

(defn- distinct-assets
  [assets]
  (vec (vals (into {} (map (juxt :id identity)) assets))))

(defn- color-candidates
  "Solid library colors by [hex opacity]."
  [assets]
  (->> (:colors assets)
       (filter #(string? (:color %)))
       (group-by #(color-key (:color %) (:opacity %)))
       (into {} (map (fn [[k v]] [k (distinct-assets v)])))))

(defn- typo-candidates
  [assets]
  (->> (:typographies assets)
       (group-by typo-key)
       (into {} (map (fn [[k v]] [k (distinct-assets v)])))))

(defn- hex
  [v field]
  (let [v (str v)]
    (cond
      (re-matches #"^#[0-9a-fA-F]{6}$" v) (str/lower v)
      (re-matches #"^#[0-9a-fA-F]{3}$" v) (let [[_ r g b] v] (str/lower (str "#" r r g g b b)))
      :else (u/error! (str "Invalid " field " key " (pr-str v) ": use a hex color like \"#ffffff\"")
                      {:field field :value v}))))

(defn parse-prefer
  "`prefer` ({\"#ffffff\": \"Surface\"}) -> {hex library color}. The color
  must exist and have that hex value."
  [assets prefer]
  (when (some? prefer)
    (when-not (map? prefer)
      (u/error! "prefer must map hex colors to library color names, e.g. {\"#ffffff\": \"Surface\"}"
                {:field "prefer" :value prefer}))
    (into {}
          (map (fn [[k v]]
                 (let [h     (hex (name k) "prefer")
                       asset (library/find-asset assets :colors v (str "prefer." h))]
                   (when-not (= h (some-> (:color asset) str/lower))
                     (u/error! (str "prefer: " (pr-str v) " is "
                                    (if (:color asset) (str/lower (:color asset)) "not a solid color")
                                    ", not " h)
                               {:field "prefer" :value v}))
                   [h asset])))
          prefer)))

;; --- linking

(defn- pick
  "[status asset-or-candidates]: the only candidate, the preferred one among
  several, or :ambiguous / :unmatched."
  [candidates prefer k]
  (let [cs (get candidates k)]
    (cond
      (= 1 (count cs)) [:linked (first cs)]
      (empty? cs) [:unmatched nil]
      :else (let [p (get prefer (first k))
                  hit (when p (some #(when (= (:id p) (:id %)) %) cs))]
              (if hit [:linked hit] [:ambiguous cs])))))

(defn- record
  [acc kind k label [status x] shape-id]
  (let [entry (fn [e init] (update (or e init) :shapes (fnil conj #{}) shape-id))]
    (case status
      :linked    (-> acc
                     (update-in [:linked [kind (:id x)]] entry {:asset x :label label})
                     (update :links (fnil inc 0)))
      :ambiguous (update-in acc [:ambiguous [kind k]] entry {:label label :candidates x})
      :unmatched (update-in acc [:unmatched [kind k]] entry {:label label}))))

(def ^:private fill-keys [:fill-color :fill-opacity :fill-color-ref-id :fill-color-ref-file])
(def ^:private stroke-keys [:stroke-color :stroke-opacity :stroke-color-ref-id :stroke-color-ref-file])

(defn- link-paint
  [ctx acc shape-id paint [kc ko kid kfile]]
  (let [color (get paint kc)]
    (if (or (not (string? color)) (some? (get paint kid)))
      [acc paint]
      (let [k      (color-key color (get paint ko))
            [status asset :as result] (pick (:colors ctx) (:prefer ctx) k)
            acc    (record acc :color k (color-label k) result shape-id)]
        (if (= :linked status)
          [acc (assoc paint kid (:id asset) kfile (::library/file-id asset))]
          [acc paint])))))

(defn- link-node
  [ctx acc shape-id node]
  (let [[acc node] (if (and (:colors? ctx) (seq (:fills node)))
                     (let [[acc fills] (u/map-acc #(link-paint ctx %1 shape-id %2 fill-keys) acc (:fills node))]
                       [acc (assoc node :fills fills)])
                     [acc node])]
    (if (and (:typographies? ctx) (txt/is-text-node? node) (nil? (:typography-ref-id node)))
      (let [k (typo-key node)
            [status asset :as result] (pick (:typographies ctx) nil k)
            acc (record acc :typography k (typo-label node) result shape-id)]
        (if (= :linked status)
          [acc (assoc node :typography-ref-id (:id asset) :typography-ref-file (::library/file-id asset))]
          [acc node]))
      [acc node])))

(defn- link-attr
  "Runs `f` ([acc] -> [acc value]) and replaces `attr` of `shape` only when
  something was linked, so unchanged attrs are not rewritten."
  [[acc shape] attr f]
  (let [before    (:links acc 0)
        [acc val] (f acc)]
    [acc (cond-> shape (> (:links acc 0) before) (assoc attr val))]))

(defn- link-shape
  [ctx acc shape]
  (let [id (:id shape)]
    (cond-> [acc shape]
      (and (:colors? ctx) (seq (:fills shape)))
      (link-attr :fills #(u/map-acc (fn [acc paint] (link-paint ctx acc id paint fill-keys)) % (:fills shape)))

      (and (:colors? ctx) (seq (:strokes shape)))
      (link-attr :strokes #(u/map-acc (fn [acc paint] (link-paint ctx acc id paint stroke-keys)) % (:strokes shape)))

      (and (cfh/text-shape? shape) (some? (:content shape)))
      (link-attr :content #(u/walk-content (fn [acc node] (link-node ctx acc id node)) % (:content shape))))))

;; --- report

(defn- asset-label
  [own-file-id asset]
  (let [n (library/full-name asset)]
    (if (= own-file-id (::library/file-id asset))
      n
      (str n " (" (::library/file-name asset) ")"))))

(defn- entries
  [m kind f]
  (->> m
       (filter (fn [[[k] _]] (= k kind)))
       (map (fn [[_ e]] (assoc (f e) :shapes (count (:shapes e)))))
       (sort-by (juxt (comp - :shapes) :value))
       (vec)))

(defn- report
  [acc own-file-id kind]
  {:linked (entries (:linked acc) kind
                    (fn [{:keys [asset label]}]
                      (cond-> {:name (library/full-name asset) :value label}
                        (not= own-file-id (::library/file-id asset))
                        (assoc :library (::library/file-name asset)))))
   :ambiguous (entries (:ambiguous acc) kind
                       (fn [{:keys [label candidates]}]
                         {:value label
                          :candidates (vec (sort (map #(asset-label own-file-id %) candidates)))}))
   :unmatched (entries (:unmatched acc) kind (fn [{:keys [label]}] {:value label}))})

(defn link-assets
  "Links the shapes `ids` of `objects` to library assets.

  opts: `:assets` the library index (`penpot-mcp.library/index`), `:colors?`,
  `:typographies?`, `:prefer` (from `parse-prefer`), `:own-file-id`.
  Returns {:shapes {id shape} (only the shapes that changed), :report}; the
  report counts shapes per library asset, ambiguous and unmatched value."
  [objects ids {:keys [assets colors? typographies? prefer own-file-id]}]
  (let [ctx {:colors (color-candidates assets)
             :typographies (typo-candidates assets)
             :prefer prefer
             :colors? colors?
             :typographies? typographies?}
        [acc shapes skipped]
        (reduce (fn [[acc out skipped] id]
                  (let [shape (get objects id)]
                    (cond
                      (nil? shape) [acc out skipped]
                      (ctk/in-component-copy? shape) [acc out (inc skipped)]
                      :else (let [before (:links acc 0)
                                  [acc shape'] (link-shape ctx acc shape)]
                              [acc (cond-> out (> (:links acc 0) before) (assoc id shape')) skipped]))))
                [{} {} 0]
                ids)]
    {:shapes shapes
     :report (cond-> {}
               colors? (assoc :colors (report acc own-file-id :color))
               typographies? (assoc :typographies (report acc own-file-id :typography))
               (pos? skipped) (assoc :skippedInCopies skipped))}))

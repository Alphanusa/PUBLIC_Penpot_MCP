;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.audit
  "Design system checks over the pages of a file: values not linked to
  library assets or tokens, values off the spacing/radius scale, text
  contrast (WCAG 2.1), components, layer names and structure.

  Every check is a plain function of the page objects and the file's
  assets and tokens, so the AI can run it after a batch of edits and fix
  what it reports. Issues name the shapes they concern (a few ids each) and,
  when there is one, the fix (the library color to link, the token to use,
  the op to run)."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.library :as library]
   [penpot-mcp.tokens :as tokens]))

(def checks #{"colors" "typography" "spacing" "radius" "contrast" "components" "naming" "structure"})

;; --- colors

(defn- hex->rgb
  [hex]
  (let [h (str/lower (str hex))]
    (when (re-matches #"^#[0-9a-f]{6}$" h)
      [(js/parseInt (subs h 1 3) 16) (js/parseInt (subs h 3 5) 16) (js/parseInt (subs h 5 7) 16)])))

(defn- rgb->hex
  [[r g b]]
  (str "#" (str/join (map #(let [s (.toString (js/Math.round (max 0 (min 255 %))) 16)]
                             (if (= 1 (count s)) (str "0" s) s))
                          [r g b]))))

(defn- channel
  [c]
  (let [c (/ c 255)]
    (if (<= c 0.03928) (/ c 12.92) (js/Math.pow (/ (+ c 0.055) 1.055) 2.4))))

(defn- luminance
  "WCAG relative luminance."
  [[r g b]]
  (+ (* 0.2126 (channel r)) (* 0.7152 (channel g)) (* 0.0722 (channel b))))

(defn contrast
  "WCAG contrast ratio of two rgb colors."
  [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn- blend
  "`fg` with `opacity` over `bg`."
  [fg opacity bg]
  (mapv (fn [f b] (+ (* f opacity) (* b (- 1 opacity)))) fg bg))

(defn- rgb->lab
  [rgb]
  (let [[r g b] (map channel rgb)
        x (/ (+ (* r 0.4124) (* g 0.3576) (* b 0.1805)) 0.95047)
        y (+ (* r 0.2126) (* g 0.7152) (* b 0.0722))
        z (/ (+ (* r 0.0193) (* g 0.1192) (* b 0.9505)) 1.08883)
        f #(if (> % 0.008856) (js/Math.cbrt %) (+ (* 7.787 %) (/ 16 116)))
        [fx fy fz] (map f [x y z])]
    [(- (* 116 fy) 16) (* 500 (- fx fy)) (* 200 (- fy fz))]))

(defn delta-e
  "CIE76 color difference of two hex colors (about 2.3 is just noticeable)."
  [a b]
  (let [[l1 a1 b1] (rgb->lab (hex->rgb a))
        [l2 a2 b2] (rgb->lab (hex->rgb b))]
    (js/Math.sqrt (+ (* (- l1 l2) (- l1 l2)) (* (- a1 a2) (- a1 a2)) (* (- b1 b2) (- b1 b2))))))

(defn- round [v] (when (number? v) (/ (js/Math.round (* v 100)) 100)))

;; --- shared

(defn- in-copy?
  "Layers of component copies follow their main: their values are checked
  on the main."
  [shape]
  (some? (:shape-ref shape)))

(defn- sample
  [shapes]
  (mapv (fn [s] (d/without-nils {:id (str (:id s)) :name (:name s)})) (take 5 shapes)))

(defn- issue
  [category severity message shapes & {:as more}]
  (d/without-nils
   (merge {:category category
           :severity severity
           :message message
           :count (when (seq shapes) (count shapes))
           :shapes (when (seq shapes) (sample shapes))}
          more)))

(defn- shapes-of
  [objects]
  (remove #(= uuid/zero (:id %)) (vals objects)))

;; --- colors

(defn- color-uses
  "[{:shape :where :color :opacity :linked?}] of the solid colors of
  `shape`: fills, strokes, text colors and shadows."
  [shape]
  (let [applied (:applied-tokens shape)
        fills   (if (cfh/text-shape? shape)
                  (->> (txt/node-seq txt/is-text-node? (:content shape))
                       (mapcat :fills)
                       (map (fn [f] [f :text])))
                  (map (fn [f] [f :fill]) (:fills shape)))]
    (concat
     (keep (fn [[f where]]
             (when-let [c (:fill-color f)]
               {:shape shape :where where :color (str/lower c) :opacity (or (:fill-opacity f) 1)
                :linked? (or (some? (:fill-color-ref-id f)) (contains? applied :fill))}))
           fills)
     (keep (fn [s]
             (when-let [c (:stroke-color s)]
               {:shape shape :where :stroke :color (str/lower c) :opacity (or (:stroke-opacity s) 1)
                :linked? (or (some? (:stroke-color-ref-id s)) (contains? applied :stroke-color))}))
           (:strokes shape))
     (keep (fn [sh]
             (when-let [c (get-in sh [:color :color])]
               {:shape shape :where :shadow :color (str/lower c) :opacity (or (get-in sh [:color :opacity]) 1)
                :linked? (or (some? (get-in sh [:color :ref-id])) (contains? applied :shadow))}))
           (remove :hidden (:shadow shape))))))

(defn- nearest-color
  [assets color]
  (->> (:colors assets)
       (filter :color)
       (map (fn [c] [c (delta-e color (str/lower (:color c)))]))
       (sort-by second)
       (first)))

(defn- check-colors
  [objects assets]
  (let [uses     (->> (shapes-of objects) (remove in-copy?) (mapcat color-uses))
        raw      (remove :linked? uses)
        groups   (group-by (juxt :color #(round (:opacity %))) raw)
        issues   (for [[[color opacity] us] (sort-by (comp - count val) groups)
                       :let [shapes (distinct (map :shape us))
                             [asset de] (nearest-color assets color)
                             exact?  (and asset (< de 0.5) (= opacity (round (or (:opacity asset) 1))))]]
                   (issue "colors" "warning"
                          (str (str/upper color) (when (< opacity 1) (str " at " (js/Math.round (* 100 opacity)) "%"))
                               " is used raw (" (str/join ", " (distinct (map (comp name :where) us))) ") in "
                               (count shapes) " shape" (when (> (count shapes) 1) "s"))
                          shapes
                          :value color
                          :fix (cond
                                 exact? (str "linkAssets links them to " (pr-str (library/full-name asset)))
                                 (and asset (< de 6)) (str "close to library color " (pr-str (library/full-name asset))
                                                           " (" (:color asset) "): use it, or add this one to the library")
                                 :else "add it to the library (edit_library) or a color token, and use that")))
        ;; raw colors that are almost the same: an off-by-a-bit palette
        distinct-colors (vec (distinct (map :color raw)))
        near (for [i (range (count distinct-colors))
                   j (range (inc i) (count distinct-colors))
                   :let [a (nth distinct-colors i) b (nth distinct-colors j)
                         de (delta-e a b)]
                   :when (and (pos? de) (< de 3))]
               [a b])]
    {:issues (concat issues
                     (when (seq near)
                       [(issue "colors" "warning"
                               (str (count near) " pairs of raw colors are almost the same ("
                                    (str/join "; " (map (fn [[a b]] (str a " / " b)) (take 5 near)))
                                    "): pick one")
                               nil)]))
     :coverage {:linked (count (filter :linked? uses)) :total (count uses)}
     :palette (->> (group-by :color uses)
                   (map (fn [[c us]] {:color c :uses (count us) :linked (count (filter :linked? us))}))
                   (sort-by (comp - :uses))
                   (take 30)
                   (vec))}))

;; --- typography

(defn- text-style-key
  [node]
  (select-keys node [:font-family :font-weight :font-style :font-size :line-height :letter-spacing :text-transform]))

(defn- check-typography
  [objects assets]
  (let [texts   (->> (shapes-of objects) (remove in-copy?) (filter cfh/text-shape?))
        nodes   (for [t texts
                      node (txt/node-seq txt/is-text-node? (:content t))
                      :when (not (str/blank? (:text node)))]
                  {:shape t :node node
                   :linked? (or (some? (:typography-ref-id node))
                                (some #(contains? (:applied-tokens t) %)
                                      [:typography :font-size :font-family :font-weight]))})
        raw     (remove :linked? nodes)
        typos   (:typographies assets)
        matches (fn [k] (d/seek (fn [t] (= (text-style-key t) k)) typos))
        groups  (group-by (comp text-style-key :node) raw)
        sizes   (->> nodes (map #(js/parseFloat (get-in % [:node :font-size]))) (filter js/isFinite) distinct sort vec)]
    {:issues (concat
              (for [[k ns] (sort-by (comp - count val) groups)
                    :let [shapes (distinct (map :shape ns))
                          t (matches k)]]
                (issue "typography" "warning"
                       (str "Text style " (:font-family k) " " (:font-weight k) " " (:font-size k) "/" (:line-height k)
                            " is not a library typography or token (" (count shapes) " text" (when (> (count shapes) 1) "s") ")")
                       shapes
                       :fix (if t
                              (str "linkAssets with \"typographies\": true links them to " (pr-str (library/full-name t)))
                              "add it as a typography (edit_library) or typography token, and apply it")))
              (when (> (count sizes) 8)
                [(issue "typography" "info"
                        (str "The texts use " (count sizes) " font sizes (" (str/join ", " (map round sizes))
                             "): a type scale of 6 to 8 sizes is easier to keep consistent")
                        nil)]))
     :coverage {:linked (count (filter :linked? nodes)) :total (count nodes)}}))

;; --- spacing and radius

(defn- scale-values
  "Resolved numeric values of the active tokens of `types`."
  [toks types]
  (into #{}
        (keep (fn [[_ t]]
                (when (contains? types (:type t))
                  (let [v (try (tokens/resolve-value toks (:value t)) (catch :default _ nil))
                        [_ n unit] (re-matches #"^\s*(-?\d*\.?\d+)\s*(px|rem)?\s*$" (str v))]
                    (when n (* (js/parseFloat n) (if (= "rem" unit) 16 1)))))))
        toks))

(defn- spacing-uses
  [shape]
  (let [applied (:applied-tokens shape)
        gap     (:layout-gap shape)
        pad     (:layout-padding shape)
        margin  (:layout-item-margin shape)]
    (concat
     (when (ctl/any-layout? shape)
       (concat
        (for [[k a] [[:row-gap :row-gap] [:column-gap :column-gap]]
              :let [v (get gap k)]
              :when (and (number? v) (pos? v))]
          {:shape shape :what "gap" :value v :tokenized? (contains? applied a)})
        (for [[k a] [[:p1 :p1] [:p2 :p2] [:p3 :p3] [:p4 :p4]]
              :let [v (get pad k)]
              :when (and (number? v) (pos? v))]
          {:shape shape :what "padding" :value v :tokenized? (contains? applied a)})))
     (for [[k a] [[:m1 :m1] [:m2 :m2] [:m3 :m3] [:m4 :m4]]
           :let [v (get margin k)]
           :when (and (number? v) (pos? v))]
       {:shape shape :what "margin" :value v :tokenized? (contains? applied a)}))))

(defn- off-scale-issues
  [category uses scale unit-hint]
  (let [off    (remove (fn [u] (or (:tokenized? u)
                                   (if (seq scale)
                                     (contains? scale (:value u))
                                     (zero? (mod (:value u) unit-hint)))))
                       uses)
        groups (group-by :value off)]
    (for [[v us] (sort-by (comp - count val) groups)
          :let [shapes (distinct (map :shape us))
                near   (when (seq scale) (apply min-key #(js/Math.abs (- % v)) scale))]]
      (issue category "warning"
             (str (round v) "px " (str/join "/" (distinct (map :what us))) " is "
                  (if (seq scale)
                    (str "not on the scale of your " category " tokens")
                    (str "not a multiple of " unit-hint))
                  " (" (count shapes) " shape" (when (> (count shapes) 1) "s") ")")
             shapes
             :value (round v)
             :fix (if near
                    (str "use the token for " (round near) "px (tokens: {\"" (if (= category "spacing") "gap" "radius") "\": …})")
                    (str "use " (* unit-hint (js/Math.round (/ v unit-hint))) "px, or a spacing token"))))))

(defn- check-spacing
  [objects toks]
  (let [uses  (->> (shapes-of objects) (remove in-copy?) (mapcat spacing-uses))
        scale (scale-values toks #{:spacing :dimensions})]
    {:issues (off-scale-issues "spacing" uses scale 4)
     :coverage {:tokenized (count (filter :tokenized? uses)) :total (count uses)}
     :scale (vec (sort scale))}))

(defn- check-radius
  [objects toks]
  (let [uses  (->> (shapes-of objects)
                   (remove in-copy?)
                   (filter #(#{:rect :frame} (:type %)))
                   (mapcat (fn [s]
                             (let [rs (distinct (filter #(and (number? %) (pos? %)) [(:r1 s) (:r2 s) (:r3 s) (:r4 s)]))]
                               (map (fn [r] {:shape s :what "radius" :value r
                                             :tokenized? (contains? (:applied-tokens s) :r1)})
                                    rs)))))
        scale (scale-values toks #{:border-radius})
        distinct-values (sort (distinct (map :value uses)))]
    {:issues (concat
              (when (seq scale) (off-scale-issues "radius" uses scale 2))
              (when (and (empty? scale) (> (count distinct-values) 5))
                [(issue "radius" "info"
                        (str "The shapes use " (count distinct-values) " corner radii (" (str/join ", " (map round distinct-values))
                             "): 3 to 5 radius tokens keep corners consistent")
                        nil)]))
     :coverage {:tokenized (count (filter :tokenized? uses)) :total (count uses)}}))

;; --- contrast

(defn- solid-fill
  "rgb of the top solid, opaque fill of `shape`, or nil."
  [shape]
  (let [f (first (:fills shape))]
    (when (and (:fill-color f) (>= (or (:fill-opacity f) 1) 0.99) (not (:hidden shape))
               (>= (or (:opacity shape) 1) 0.99))
      (hex->rgb (:fill-color f)))))

(defn- contains-rect?
  [outer inner]
  (and (<= (:x outer) (+ (:x inner) 0.5))
       (<= (:y outer) (+ (:y inner) 0.5))
       (>= (+ (:x outer) (:width outer)) (- (+ (:x inner) (:width inner)) 0.5))
       (>= (+ (:y outer) (:height outer)) (- (+ (:y inner) (:height inner)) 0.5))))

(defn- background
  "rgb behind `shape`: the nearest shape below it that covers it with a
  solid fill (siblings below it, then its parents and what lies below
  them), or white."
  [objects shape]
  (let [box (:selrect shape)]
    (loop [child shape]
      (let [parent (get objects (:parent-id child))]
        (if (or (nil? parent) (= (:id child) uuid/zero))
          [255 255 255]
          (let [shapes  (vec (:shapes parent))
                idx     (d/index-of shapes (:id child))
                below   (when idx (map #(get objects %) (rseq (subvec shapes 0 idx))))
                covering (d/seek (fn [s] (and s (solid-fill s) (contains-rect? (:selrect s) box)
                                              (not (cfh/text-shape? s))))
                                 below)]
            (cond
              covering (solid-fill covering)
              (and (not= uuid/zero (:id parent)) (solid-fill parent)) (solid-fill parent)
              (= uuid/zero (:id parent)) [255 255 255]
              :else (recur parent))))))))

(defn- check-contrast
  [objects]
  (let [texts (->> (shapes-of objects) (remove :hidden) (filter cfh/text-shape?))]
    {:issues
     (for [t texts
           :let [node   (d/seek #(not (str/blank? (:text %))) (txt/node-seq txt/is-text-node? (:content t)))
                 fill   (first (:fills node))
                 fg     (some-> (:fill-color fill) hex->rgb)]
           :when fg
           :let [bg     (background objects t)
                 alpha  (* (or (:fill-opacity fill) 1) (or (:opacity t) 1))
                 fg     (blend fg alpha bg)
                 ratio  (contrast fg bg)
                 size   (js/parseFloat (:font-size node))
                 bold?  (>= (js/parseFloat (or (:font-weight node) "400")) 700)
                 large? (or (>= size 24) (and bold? (>= size 18.66)))
                 need   (if large? 3 4.5)]
           :when (< ratio need)]
       (issue "contrast" "error"
              (str "Text " (pr-str (str/prune (txt/content->text (:content t)) 40)) " has a contrast of "
                   (round ratio) ":1 (" (rgb->hex fg) " on " (rgb->hex bg) "); WCAG AA needs " need ":1"
                   (if large? " for large text" " for text under 24px (18.66px bold)"))
              [t]
              :ratio (round ratio)
              :fix "darken or lighten the text or its background"))}))

;; --- components

(defn- signature
  "A structural fingerprint of a subtree: types and child counts, to find
  repeated layer structures."
  [objects id depth]
  (let [s (get objects id)]
    (str (name (:type s))
         (when (and (< depth 4) (seq (:shapes s)))
           (str "[" (str/join "," (map #(signature objects % (inc depth)) (:shapes s))) "]")))))

(defn- ctn-copy?
  [objects shape]
  (some ctk/in-component-copy? (cons shape (map #(get objects %) (cfh/get-parent-ids objects (:id shape))))))

(defn- check-components
  [pages data]
  (let [file-id   (:id data)
        comps     (vals (:components data))
        live      (remove :deleted comps)
        all-objs  (mapcat (comp shapes-of :objects) pages)
        copies    (filter #(and (ctk/instance-head? %) (ctk/in-component-copy? %)) all-objs)
        used      (into #{} (keep #(when (= file-id (:component-file %)) (:component-id %))) copies)
        unused    (remove #(contains? used (:id %)) live)
        repeated  (for [page pages
                        :let [objects (:objects page)
                              candidates (->> (shapes-of objects)
                                              (filter #(#{:frame :group} (:type %)))
                                              (remove ctk/instance-head?)
                                              (remove #(ctn-copy? objects %))
                                              (filter #(>= (count (cfh/get-children-ids objects (:id %))) 2)))]
                        [sig shapes] (group-by #(signature objects (:id %) 0) candidates)
                        :when (>= (count shapes) 3)]
                    shapes)
        empty-props (for [c live
                          :when (and (:variant-id c) (some #(str/blank? (:value %)) (:variant-properties c)))]
                      c)]
    {:issues (concat
              (for [shapes (take 10 (sort-by (comp - count) repeated))]
                (issue "components" "info"
                       (str (count shapes) " layers share the structure of " (pr-str (:name (first shapes)))
                            " but aren't copies of a component")
                       shapes
                       :fix "make one a component (makeComponent) and swap the others for copies of it"))
              (when (seq unused)
                [(issue "components" "info"
                        (str (count unused) " components of this file have no copies in it: "
                             (str/join ", " (map library/full-name (take 10 unused)))
                             (when (> (count unused) 10) ", …")
                             " (fine for a shared library)")
                        nil)])
              (for [c empty-props]
                (issue "components" "warning"
                       (str "Variant " (pr-str (library/full-name c)) " has empty property values ("
                            (str/join ", " (map #(str (:name %) "=" (:value %)) (:variant-properties c))) ")")
                       nil
                       :fix "give every variant a value for each property (setVariant)"))
              (when (and (> (count live) 10) (every? #(str/blank? (:path %)) live))
                [(issue "components" "info"
                        (str "The " (count live) " components have no path: group them like \"Buttons / Primary\"")
                        nil)]))
     :stats {:components (count live) :copies (count copies) :unused (count unused)}}))

;; --- naming

(def ^:private default-name-re
  #"^(Rectangle|Ellipse|Board|Group|Path|Bool|Union|Difference|Intersection|Exclude|Image|Vector|Frame|Line|Arrow|Curve|svg-[a-z]+)( ?\d+)?( copy( \d+)?)?$")

(defn- check-naming
  [objects]
  (let [shapes   (->> (shapes-of objects) (remove in-copy?))
        defaults (filter #(re-matches default-name-re (str (:name %))) shapes)
        top      (filter #(and (= uuid/zero (:parent-id %)) (cfh/frame-shape? %)) defaults)
        dup      (for [[_ ss] (group-by (juxt :parent-id :name)
                                        (filter #(and (= uuid/zero (:parent-id %)) (cfh/frame-shape? %)) shapes))
                       :when (> (count ss) 1)]
                   ss)]
    {:issues (concat
              (when (seq top)
                [(issue "naming" "warning"
                        (str (count top) " top-level boards have default names (" (str/join ", " (distinct (map :name (take 5 top))))
                             "): name screens and sections after what they are")
                        top)])
              (when (seq defaults)
                [(issue "naming" "info"
                        (str (count defaults) " layers have default names like " (pr-str (:name (first defaults)))
                             ": names like \"Avatar\", \"Divider\" keep the layers panel readable and survive handoff")
                        defaults)])
              (for [ss dup]
                (issue "naming" "warning" (str (count ss) " top-level boards are named " (pr-str (:name (first ss)))) ss)))}))

;; --- structure

(defn- row-or-column?
  "True when the children of a board without layout sit in one row or one
  column with the same gaps, which a flex layout would keep in place."
  [objects board]
  (let [kids (keep #(get objects %) (:shapes board))]
    (when (>= (count kids) 3)
      (let [rects (map :selrect kids)
            same  (fn [vs] (< (- (apply max vs) (apply min vs)) 1))
            gaps  (fn [sorted pos size] (map (fn [a b] (- (pos b) (+ (pos a) (size a)))) sorted (rest sorted)))]
        (or (and (same (map :y rects))
                 (let [g (gaps (sort-by :x rects) :x :width)] (and (every? #(>= % 0) g) (same g))))
            (and (same (map :x rects))
                 (let [g (gaps (sort-by :y rects) :y :height)] (and (every? #(>= % 0) g) (same g)))))))))

(defn- depth [objects id] (count (cfh/get-parent-ids objects id)))

(defn- check-structure
  [objects]
  (let [shapes   (shapes-of objects)
        floating (filter #(and (= uuid/zero (:parent-id %)) (not (cfh/frame-shape? %))) shapes)
        hidden   (filter :hidden shapes)
        empty-c  (filter #(and (#{:frame :group} (:type %)) (empty? (:shapes %)) (empty? (:fills %))
                               (not (ctk/instance-head? %)))
                         shapes)
        deep     (filter #(> (depth objects (:id %)) 12) shapes)
        flexable (filter #(and (cfh/frame-shape? %) (not (ctl/any-layout? %)) (not (in-copy? %))
                               (row-or-column? objects %))
                         shapes)]
    {:issues (concat
              (when (seq floating)
                [(issue "structure" "warning"
                        (str (count floating) " layers sit on the page outside any board")
                        floating
                        :fix "move them into a board (move op) or delete them")])
              (when (seq empty-c)
                [(issue "structure" "warning" (str (count empty-c) " empty boards or groups") empty-c :fix "delete them")])
              (when (seq hidden)
                [(issue "structure" "info" (str (count hidden) " hidden layers") hidden
                        :fix "delete the ones that aren't states or variants")])
              (when (seq deep)
                [(issue "structure" "info" (str (count deep) " layers are nested more than 12 levels deep") deep)])
              (for [b flexable]
                (issue "structure" "info"
                       (str (pr-str (:name b)) " places its children in a row or column by hand")
                       [b]
                       :fix "give it a flex layout (layout: {direction, gap}) so it adapts when content changes")))}))

;; --- running

(defn- score
  [issues]
  (let [w {"error" 5 "warning" 2 "info" 0.5}]
    (max 0 (- 100 (js/Math.round (reduce + 0 (map #(get w (:severity %) 0) issues)))))))

(defn run
  "Audits `pages` ([{:id :name :objects}]) of file `data` with `assets`
  (library index of the file and its libraries) and `toks` (active tokens).
  `only`: a set of check names (nil for all). Returns the report."
  [data pages assets toks {:keys [only limit]}]
  (let [on?     #(or (empty? only) (contains? only %))
        objects (into {} (mapcat :objects) pages)
        res     (d/without-nils
                 {:colors (when (on? "colors") (check-colors objects assets))
                  :typography (when (on? "typography") (check-typography objects assets))
                  :spacing (when (on? "spacing") (check-spacing objects toks))
                  :radius (when (on? "radius") (check-radius objects toks))
                  :contrast (when (on? "contrast") (apply merge-with concat (map #(check-contrast (:objects %)) pages)))
                  :components (when (on? "components") (check-components pages data))
                  :naming (when (on? "naming") (apply merge-with concat (map #(check-naming (:objects %)) pages)))
                  :structure (when (on? "structure") (apply merge-with concat (map #(check-structure (:objects %)) pages)))})
        issues  (vec (mapcat (comp :issues val) res))
        order   {"error" 0 "warning" 1 "info" 2}
        sorted  (sort-by (fn [i] [(get order (:severity i)) (- (or (:count i) 0))]) issues)]
    (d/without-nils
     {:pages (mapv (fn [p] {:id (str (:id p)) :name (:name p)}) pages)
      :score (score issues)
      :summary (into {} (map (fn [[k v]] [k (count (:issues v))])) res)
      :bySeverity (frequencies (map :severity issues))
      :coverage (d/without-nils
                 {:colors (get-in res [:colors :coverage])
                  :typography (get-in res [:typography :coverage])
                  :spacing (get-in res [:spacing :coverage])
                  :radius (get-in res [:radius :coverage])})
      :palette (get-in res [:colors :palette])
      :spacingScale (not-empty (get-in res [:spacing :scale]))
      :components (get-in res [:components :stats])
      :issues (vec (take (or limit 60) sorted))
      :totalIssues (count issues)})))

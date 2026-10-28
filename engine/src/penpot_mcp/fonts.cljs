;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Team font families are shaped like Penpot's frontend does it
;; (app.main.data.fonts/fonts-loaded, MPL-2.0, Copyright (c) KALEIDOS
;; SUBSIDIARY SL): font id `custom-<font-id>`, variant id `<style>-<weight>`.

(ns penpot-mcp.fonts
  "Font families for new and edited texts, resolved the way Penpot's font
  selector stores them in text content (`:font-id`, `:font-family`,
  `:font-variant-id`, `:font-weight`, `:font-style`).

  Sources, in order: the team's uploaded fonts, the builtin Source Sans Pro,
  then the Google Fonts catalog baked into `app.common.fonts` (the same one
  the editor and render-wasm use). The render path loads the font files from
  these ids (see `penpot-mcp.engine/make-resolve-font`), so texts are measured
  and rendered with the real font."
  (:require
   [app.common.data :as d]
   [app.common.fonts :as cfnt]
   [cuerdas.core :as str]
   [penpot-mcp.util :as u]))

(def builtin
  (let [{:keys [id name family variants]} (first cfnt/local-fonts)]
    {:id id
     :name name
     ;; text content stores the builtin family as "sourcesanspro"
     :family family
     :source :builtin
     :variants (mapv #(select-keys % [:id :weight :style]) variants)}))

(defn- google [font] (assoc font :source :google))

(def ^:private google-by-id
  (delay (reduce (fn [m font] (assoc m (:id font) font)) {} cfnt/catalog)))

(def ^:private google-by-name
  (delay (reduce (fn [m font] (assoc m (u/normalize (:family font)) font)) {} cfnt/catalog)))

(defn team-fonts
  "Team font families from `get-font-variants` rows."
  [variants]
  (->> variants
       (group-by :font-id)
       (mapv (fn [[font-id items]]
               (let [family (:font-family (first items))]
                 {:id (str "custom-" font-id)
                  :name family
                  :family family
                  :source :team
                  :variants (->> items
                                 (map (fn [{:keys [font-weight font-style]}]
                                        {:id (str font-style "-" font-weight)
                                         :weight (str font-weight)
                                         :style (str font-style)}))
                                 (sort-by (juxt #(d/parse-integer (:weight %))
                                                #(if (= "normal" (:style %)) 0 1)))
                                 (vec))})))))

(defn- name-match?
  [q font]
  (or (= q (u/normalize (:family font)))
      (= q (u/normalize (:name font)))))

(defn find-font
  "Family for `query`: a family name (case and spacing insensitive) or a
  Penpot font id (`gfont-…`, `custom-…`, `sourcesanspro`)."
  [team query]
  (let [query (str/trim (str query))
        q     (u/normalize query)]
    (or (d/seek #(= query (:id %)) team)
        (d/seek #(name-match? q %) team)
        (when (or (= query (:id builtin)) (name-match? q builtin)) builtin)
        (some-> (get @google-by-id query) google)
        (some-> (get @google-by-name q) google))))

(defn font-by-id
  "Family of a text content `font-id`, or nil when it is not available."
  [team font-id]
  (cond
    (or (nil? font-id) (= font-id (:id builtin))) builtin
    (str/starts-with? font-id "gfont-") (some-> (get @google-by-id font-id) google)
    :else (d/seek #(= font-id (:id %)) team)))

(defn- closest-names
  "Up to `n` family names closest to `query`, for not-found errors."
  [team query n]
  (let [q     (u/normalize query)
        score (fn [font]
                (let [f (u/normalize (:family font))]
                  (cond
                    (str/includes? f q) [0 (count f)]
                    (str/includes? q f) [1 (- (count f))]
                    :else [2 (u/levenshtein q f)])))]
    (->> (concat team [builtin] cfnt/catalog)
         (sort-by score)
         (take n)
         (map :name))))

(defn variants-desc
  "\"300, 400, 400 italic, 700\""
  [font]
  (->> (:variants font)
       (map (fn [{:keys [weight style]}] (if (= "italic" style) (str weight " italic") weight)))
       (distinct)
       (str/join ", ")))

(def ^:private weight-names
  {"thin" "100" "hairline" "100" "extralight" "200" "ultralight" "200" "light" "300"
   "normal" "400" "regular" "400" "book" "400" "medium" "500" "semibold" "600"
   "demibold" "600" "bold" "700" "extrabold" "800" "ultrabold" "800" "black" "900"
   "heavy" "900"})

(defn- parse-weight
  [v]
  (when (some? v)
    (let [s (u/normalize v)
          s (get weight-names s s)]
      (if (re-matches #"[1-9]\d\d" s)
        s
        (u/error! (str "Invalid fontWeight " (pr-str v)
                       ": use a number from 100 to 900 (e.g. 400, 600) or a name like \"medium\" or \"bold\"")
                  {:field "fontWeight" :value v})))))

(defn- parse-style
  [v]
  (when (some? v)
    (case (u/normalize v)
      ("normal" "regular") "normal"
      "italic" "italic"
      (u/error! (str "Invalid fontStyle " (pr-str v) ". Use normal or italic")
                {:field "fontStyle" :value v}))))

(defn- closest-variant
  [variants weight style]
  (cfnt/closest-variant variants (or (d/parse-integer (str weight)) 400) style))

(defn text-font-attrs
  "Text content font attrs for the `fontFamily` / `fontWeight` / `fontStyle`
  of `op`, over the current text node `base` (nil for a new text). Returns
  nil when the op does not touch the font.

  An explicit weight or style that the family does not have is an error; when
  only the family changes, the current weight and style are kept when the
  family has them, else the closest variant is used (the editor resets to the
  family's default variant instead, which loses the weight)."
  [team {:keys [fontFamily fontWeight fontStyle]} base]
  (when (or (some? fontFamily) (some? fontWeight) (some? fontStyle))
    (let [font     (cond
                     (some? fontFamily)
                     (or (find-font team fontFamily)
                         (u/error! (str "Font family " (pr-str fontFamily)
                                        " not found in the team fonts, the builtin font or Google Fonts. Closest: "
                                        (str/join ", " (closest-names team fontFamily 5))
                                        ". Use list_fonts to search.")
                                   {:field "fontFamily" :value fontFamily}))

                     :else
                     (or (font-by-id team (:font-id base))
                         (u/error! (str "The text's font " (pr-str (or (:font-family base) (:font-id base)))
                                        " is not available to this account: set fontFamily as well.")
                                   {:field "fontFamily"})))
          weight   (parse-weight fontWeight)
          style    (or (parse-style fontStyle) (some-> (:font-style base) str) "normal")
          variants (:variants font)
          styled   (filterv #(= style (:style %)) variants)
          variant  (cond
                     (some? weight)
                     (or (d/seek #(= weight (:weight %)) styled)
                         (u/error! (str "fontWeight " weight (when (= "italic" style) " italic")
                                        " is not available for " (:name font)
                                        ". Available: " (variants-desc font) ".")
                                   {:field "fontWeight" :value fontWeight}))

                     (seq styled)
                     (closest-variant styled (:font-weight base) style)

                     (some? fontStyle)
                     (u/error! (str "fontStyle " style " is not available for " (:name font)
                                    ". Available: " (variants-desc font) ".")
                               {:field "fontStyle" :value fontStyle})

                     :else
                     (or (closest-variant variants (:font-weight base) nil)
                         (first variants)))]
      (when-not variant
        (u/error! (str (:name font) " has no usable variants") {:field "fontFamily" :value fontFamily}))
      {:font-id (:id font)
       :font-family (:family font)
       :font-variant-id (:id variant)
       :font-weight (:weight variant)
       :font-style (:style variant)})))

(defn- describe
  [font]
  {:family (:name font)
   :fontId (:id font)
   :source (name (:source font))
   :variants (variants-desc font)})

(defn search
  "Families matching `query` (substring, case and spacing insensitive; all
  when blank), grouped by source. The Google list is capped at `limit`."
  [team query limit]
  (let [q      (some-> query u/normalize not-empty)
        match? (fn [font] (or (nil? q) (str/includes? (u/normalize (:family font)) q)
                              (str/includes? (u/normalize (:name font)) q)))
        gfonts (filter match? cfnt/catalog)]
    {:team (mapv describe (filter match? team))
     :builtin (mapv describe (filter match? [builtin]))
     :google (mapv (comp describe google) (take limit gfonts))
     :googleMatches (count gfonts)}))

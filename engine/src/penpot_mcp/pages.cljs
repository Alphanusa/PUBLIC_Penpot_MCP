;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns penpot-mcp.pages
  "Page operations (add, rename, delete, move) as Penpot changes — the same
  `:add-page` / `:mod-page` / `:del-page` / `:mov-page` changes the workspace
  sends (app.main.data.workspace.pages), validated by the backend on save."
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.props :as props]
   [penpot-mcp.util :as u]))

(def ^:private op-props
  {"add"    [:op :name :ref :index]
   "rename" [:op :id :name]
   "delete" [:op :id]
   "move"   [:op :id :index]})

(defn- page-name!
  [v]
  (if (and (string? v) (not (str/blank? v)) (<= (count v) 250))
    (str/trim v)
    (u/error! (str "Invalid name " (pr-str v) " (expected a non-empty text, at most 250 characters)")
              {:field "name" :value v})))

(defn- position!
  "0-based position after the move, clamped to the last page."
  [v n]
  (if (and (integer? v) (>= v 0))
    (min v (dec n))
    (u/error! (str "Invalid index " (pr-str v) " (expected 0 for the first page, 1 for the second, …)")
              {:field "index" :value v})))

(defn- resolve-page
  [{:keys [refs pages]} v]
  (let [id (or (get refs v) (when (string? v) (uuid/parse* v)))]
    (if (and id (some #(= id %) pages))
      id
      (u/error! (str "Page " (pr-str v) " not found in this file (use a page id, or the ref of a page added earlier in this batch)")
                {:field "id" :value v}))))

(defn- move
  "Moves page `id` to position `index` (counted after the move).
  `:mov-page` takes the position *before* removing the page from the list
  (`d/insert-at-index`), so moving forward needs index + 1."
  [state id index]
  (let [pages   (:pages state)
        target  (position! index (count pages))
        current (d/index-of pages id)
        param   (if (> target current) (inc target) target)]
    (if (= target current)
      state
      (-> state
          (update :changes conj {:type :mov-page :id id :index param})
          (assoc :pages (d/insert-at-index pages param [id]))))))

(defn initial
  "Page state of `file-data` before any op."
  [file-data]
  (let [index (:pages-index file-data)
        pages (vec (:pages file-data))]
    {:changes []
     :pages pages
     :names (into {} (map (fn [id] [id (get-in index [id :name])])) pages)
     :refs {}
     :added []
     :deleted []}))

(defn- main-components?
  [page]
  (some :main-instance (vals (:objects page))))

(defn- apply-op
  [file-data state op]
  (when-not (map? op)
    (u/error! "Each op must be an object like {\"op\": \"add\", \"name\": \"Screens\"}" {:field "op"}))
  (let [kind    (some-> (:op op) str str/lower)
        allowed (or (get op-props kind)
                    (u/error! (str "Unknown op " (pr-str (:op op)) ". Use add, rename, delete or move")
                              {:field "op"}))]
    (props/check-keys! op allowed (str "for " kind))
    (case kind
      "add"
      (let [id    (uuid/next)
            name  (page-name! (:name op))
            state (cond-> (-> state
                              (update :changes conj {:type :add-page :id id :name name})
                              (update :pages conj id)
                              (assoc-in [:names id] name)
                              (update :added conj id))
                    (some? (:ref op)) (update :refs assoc (str (:ref op)) id))]
        (if (contains? op :index) (move state id (:index op)) state))

      "rename"
      (let [id   (resolve-page state (:id op))
            name (page-name! (:name op))]
        (-> state
            (update :changes conj {:type :mod-page :id id :name name})
            (assoc-in [:names id] name)))

      "delete"
      (let [id (resolve-page state (:id op))]
        (when (= 1 (count (:pages state)))
          (u/error! "A file needs at least one page: add another page before deleting this one" {:field "id"}))
        (when (main-components? (get-in file-data [:pages-index id]))
          (u/error! (str "Page " id " holds main components; delete it in Penpot, which also handles the components")
                    {:field "id"}))
        (-> state
            (update :changes conj {:type :del-page :id id})
            (update :pages (fn [pages] (filterv #(not= % id) pages)))
            (update :names dissoc id)
            (update :deleted conj id)))

      "move"
      (move state (resolve-page state (:id op)) (:index op)))))

(defn build
  "Applies page `ops` to `file-data` (:pages and :pages-index). Returns the
  state; `(:changes state)` are the redo changes for `update-file`."
  [file-data ops]
  (when-not (and (sequential? ops) (seq ops))
    (u/error! "ops must be a non-empty array of operations" {:field "ops"}))
  (reduce (fn [state [idx op]]
            (try
              (apply-op file-data state op)
              (catch :default cause
                (throw (ex-info (str "ops[" idx "]: " (ex-message cause))
                                (assoc (ex-data cause) :index idx)
                                cause)))))
          (initial file-data)
          (map-indexed vector ops)))

(defn result
  "JSON-friendly summary: pages in order, refs of added pages."
  [{:keys [pages names refs added deleted]}]
  {:pages (mapv (fn [id] {:id (str id) :name (get names id)}) pages)
   :created (into {} (map (fn [[ref id]] [ref (str id)])) refs)
   :addedIds (mapv str added)
   :deleted (mapv str deleted)})

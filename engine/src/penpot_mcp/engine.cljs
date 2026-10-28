;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Font and image provisioning follow Penpot's exporter (app.wasm.render,
;; MPL-2.0, Copyright (c) KALEIDOS SUBSIDIARY SL).

(ns penpot-mcp.engine
  "Browser-free Penpot engine for the MCP server.

  Runs Penpot's own code under Node — `common` for the data model, changes and
  layout, and the render-wasm (Skia) module for text layout and rendering —
  reusing the headless pieces of Penpot's exporter (`app.wasm`,
  `app.wasm.serialize`) unchanged. What differs from the exporter:

  - auth: the user's Penpot access token (`Authorization: Token ...`);
  - transport: base URI passed per call instead of process config;
  - output: bytes/values returned instead of written to temp files;
  - editing: see `penpot-mcp.edit`.

  The JS API (exports in `shadow-cljs.edn`) is promise based and takes and
  returns plain JS values, so the TypeScript MCP server can call it directly."
  (:require
   ["node:fs" :as fs]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.data :as d]
   [app.common.fonts :as cfnt]
   ;; Required for side effects: transit read handlers and deftype impls the
   ;; RPC responses are decoded into.
   [app.common.geom.matrix]
   [app.common.geom.point]
   [app.common.geom.rect]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.fills.impl]
   [app.common.types.objects-map]
   [app.common.types.page :as ctp]
   [app.common.types.path.impl]
   [app.common.types.shape :as cts]
   [app.common.types.shape.images :as images]
   [app.common.uuid :as uuid]
   [app.wasm :as wasm]
   [app.wasm.serialize :as serialize]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [penpot-mcp.edit :as edit]
   [penpot-mcp.fonts :as fonts]
   [penpot-mcp.media :as media]
   [penpot-mcp.pages :as pages]
   [promesa.core :as p]))

(l/set-level! :warn)

;; --- module lifecycle

(defonce ^:private module* (atom nil))
(defonce ^:private fonts-dir* (atom nil))

(defn- ensure-module!
  []
  (or @module*
      (reset! module* (wasm/init!))))

(defn init
  "Loads the render-wasm module from `artifact-dir` (render-wasm.js/.wasm).
  `fonts-dir`, when given, holds Penpot's builtin font TTFs (Source Sans Pro),
  read locally instead of downloaded. Safe to call more than once."
  ([artifact-dir] (init artifact-dir nil))
  ([artifact-dir fonts-dir]
   (when artifact-dir
     (set! wasm/artifact-dir artifact-dir))
   (when fonts-dir
     (reset! fonts-dir* fonts-dir))
   (->> (ensure-module!)
        (p/fmap (constantly true)))))

;; --- backend access with the user's access token

(defn- join-uri
  [base path]
  (str (str/rtrim base "/") "/" path))

(defn- auth-headers
  [token]
  (cond-> #js {"Content-Type" "application/transit+json"
               "Accept"       "application/transit+json"}
    (some? token) (doto (unchecked-set "Authorization" (str "Token " token)))))

(defn- fetch-bytes
  "GETs `uri` and resolves to an ArrayBuffer, or nil on any failure (callers
  degrade: a missing font or image must not fail the whole render)."
  [uri token]
  (if (nil? uri)
    (p/resolved nil)
    (->> (p/do (http/fetch uri #js {:method "GET"
                                    :headers (cond-> #js {}
                                               (some? token)
                                               (doto (unchecked-set "Authorization" (str "Token " token))))}))
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.arrayBuffer resp)
                     (do (l/warn :hint "fetch non-200" :uri uri :status (.-status resp))
                         (p/resolved nil)))))
         (p/merr (fn [cause]
                   (l/warn :hint "fetch failed" :uri uri :cause cause)
                   (p/resolved nil))))))

(defn- rpc-error
  [command status body]
  (let [data (try (t/decode-str body) (catch :default _ nil))
        hint (or (:hint data) (:code data) (some-> body (str/prune 300)))]
    (ex-info (str "Penpot " (name command) " failed (HTTP " status "): " hint)
             {:status status :command command :code (:code data) :type (:type data)})))

(defn- handle-response
  [command ^js resp]
  (->> (.text resp)
       (p/mcat (fn [body]
                 (if (= 200 (.-status resp))
                   (p/resolved (when-not (str/blank? body) (t/decode-str body)))
                   (p/rejected (rpc-error command (.-status resp) body)))))))

(defn rpc
  "Calls a backend RPC command and resolves to the decoded response."
  [{:keys [base-uri token]} command params]
  (when (str/blank? base-uri)
    (throw (ex-info "Penpot base URI is not configured" {})))
  (let [uri (join-uri base-uri (str "api/rpc/command/" (name command)))]
    (->> (p/do (http/fetch uri #js {:method "POST"
                                   :headers (auth-headers token)
                                   :body (t/encode-str params)}))
         (p/mcat (partial handle-response command)))))

(defn- rpc-multipart
  "Calls a multipart RPC command (uploads), like the frontend does: plain
  form fields plus one file part."
  [{:keys [base-uri token]} command fields {:keys [field data mtype filename]}]
  (when (str/blank? base-uri)
    (throw (ex-info "Penpot base URI is not configured" {})))
  (let [uri  (join-uri base-uri (str "api/main/methods/" (name command)))
        form (http/FormData.)]
    (doseq [[k v] fields]
      (.append form k (str v)))
    (.append form field (js/Blob. #js [data] #js {:type mtype}) filename)
    (->> (p/do (http/fetch uri #js {:method "POST"
                                   :headers (cond-> #js {"Accept" "application/transit+json"}
                                              (some? token)
                                              (doto (unchecked-set "Authorization" (str "Token " token))))
                                   :body form}))
         (p/mcat (partial handle-response command)))))

;; --- fonts

(defn- family-uuid
  [id]
  (uuid/from-unsigned-parts (aget id 0) (aget id 1) (aget id 2) (aget id 3)))

(defn- find-variant
  [variants font-uuid weight style]
  (let [style-str (if (zero? style) "normal" "italic")]
    (or (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                             (= (:font-weight v) weight)
                             (= (name (:font-style v)) style-str)))
                variants)
        (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                             (= (:font-weight v) weight)))
                variants)
        (d/seek (fn [v] (= (:font-id v) font-uuid)) variants))))

(defonce ^:private font-cache (atom {}))

(defn- cached-bytes
  [cache-key fetch-fn]
  (if-let [hit (get @font-cache cache-key)]
    hit
    (let [pending (->> (fetch-fn)
                       (p/fmap (fn [buf]
                                 (when-not buf (swap! font-cache dissoc cache-key))
                                 buf)))]
      (swap! font-cache assoc cache-key pending)
      pending)))

(defn- builtin-font-bytes
  [{:keys [base-uri]} ttf-file]
  (let [local (some-> @fonts-dir* (path/join ttf-file))]
    (if (and local (fs/existsSync local))
      (cached-bytes local #(p/resolved (fs/readFileSync local)))
      (let [uri (some-> base-uri (join-uri (str "fonts/" ttf-file)))]
        (cached-bytes uri #(fetch-bytes uri nil))))))

(defn- gfont-bytes
  "Google font file through the Penpot instance's gfonts proxy, like the
  exporter. Offline (no backend), only when `:gfonts-direct?` is set: straight
  from fonts.gstatic.com (tests)."
  [{:keys [base-uri gfonts-direct?]} ttf-url]
  (cond
    (some? base-uri)
    (let [uri (cfnt/gstatic->proxy-url ttf-url (join-uri base-uri "internal/gfonts/font"))]
      (cached-bytes uri #(fetch-bytes uri nil)))

    gfonts-direct?
    (cached-bytes ttf-url #(fetch-bytes ttf-url nil))

    :else
    (p/resolved nil)))

(defn- make-resolve-font
  [variants {:keys [base-uri token] :as ctx}]
  (fn [{:keys [id weight style]}]
    (let [font-uuid (family-uuid id)
          variant   (find-variant variants font-uuid weight style)]
      (cond
        (and (:ttf-file-id variant) base-uri)
        (fetch-bytes (join-uri base-uri (str "assets/by-id/" (:ttf-file-id variant))) token)

        (= uuid/zero font-uuid)
        (builtin-font-bytes ctx (cfnt/resolve-ttf-file weight style))

        :else
        (if-let [gurl (cfnt/resolve-ttf-url font-uuid weight style)]
          (gfont-bytes ctx gurl)
          (p/resolved nil))))))

(defn- scene-fallback-fonts
  [scene]
  (let [texts  (for [shape (vals scene)
                     :when (= :text (:type shape))
                     node  (or (some->> (:content shape) (tree-seq :children :children)) [])
                     :let  [text (:text node)]
                     :when (string? text)]
                 text)
        emoji? (boolean (some cfnt/contains-emoji? texts))
        langs  (reduce cfnt/collect-used-languages #{} texts)]
    (distinct
     (cond-> (cfnt/add-noto-fonts [] langs)
       emoji? (cfnt/add-emoji-font)))))

(defn- provision-fallback-fonts!
  [ctx scene]
  (->> (scene-fallback-fonts scene)
       (map (fn [{:keys [font-id weight style is-emoji is-fallback]}]
              (if-let [font-uuid (cfnt/gfont-id->uuid font-id)]
                (if-let [ttf-url (cfnt/resolve-ttf-url font-uuid weight style)]
                  (->> (gfont-bytes ctx ttf-url)
                       (p/fmap (fn [buf]
                                 (when buf
                                   (wasm/store-font! {:id (uuid/get-u32 font-uuid)
                                                      :weight weight
                                                      :style style
                                                      :emoji? (boolean is-emoji)
                                                      :fallback? (boolean is-fallback)}
                                                     buf)))))
                  (p/resolved nil))
                (p/resolved nil))))
       (p/all)))

;; --- images

(defn- image-bytes
  "Encoded bytes of file media `image-id`: given by the caller (`:images`,
  offline) or fetched from the backend."
  [{:keys [base-uri token images]} image-id]
  (if-let [local (get images image-id)]
    (p/resolved local)
    (if (nil? base-uri)
      (p/resolved nil)
      (fetch-bytes (join-uri base-uri (str "assets/by-file-media-id/" image-id)) token))))

(defn- provision-images!
  [ctx scene]
  (->> (remove wasm/image-cached? (images/scene-image-ids scene))
       (map (fn [image-id]
              (->> (image-bytes ctx image-id)
                   (p/fmap (fn [buf]
                             (if buf
                               (wasm/store-image! image-id buf)
                               (l/warn :hint "image unavailable" :media-id (str image-id))))))))
       (p/all)))

;; media id -> {:width :height :mtype}; media objects never change
(defonce ^:private media-info-cache (atom {}))

(defn- media-info!
  "Natural size and type of file media `image-id`, read from the image (the
  backend has no read RPC for media object metadata), or nil when it can't
  be read. The bytes also go to the render-wasm image store, which the text
  measuring and the render that follow reuse. Needs the module loaded."
  [ctx image-id]
  (if-let [hit (get @media-info-cache image-id)]
    (p/resolved hit)
    (->> (image-bytes ctx image-id)
         (p/fmap (fn [buf]
                   (when-let [info (some-> buf media/info)]
                     (when-not (wasm/image-cached? image-id)
                       (wasm/store-image! image-id buf))
                     (swap! media-info-cache assoc image-id info)
                     info))))))

(defn- media-infos!
  "image id -> info for the image fills referenced in `ops`."
  [ctx ops]
  (->> (edit/image-refs ops)
       (map (fn [id]
              (->> (media-info! ctx id)
                   (p/fmap (fn [info] (when info [id info])))
                   (p/merr (fn [cause]
                             (l/warn :hint "media info failed" :media-id (str id) :cause cause)
                             (p/resolved nil))))))
       (p/all)
       (p/fmap #(into {} (remove nil?) %))))

;; --- scene loading

(defn- load-scene!
  "Loads `scene` (an objects map) into the WASM state with fonts and images for
  the subtrees of `ids`, and lays out every text with the real fonts."
  [ctx scene ids variants]
  (serialize/serialize-scene! scene)
  (wasm/clear-fonts!)
  (->> (p/all [(provision-images! ctx scene)
               (provision-fallback-fonts! ctx scene)])
       (p/mcat (fn [_]
                 (wasm/provision-fonts! ids (make-resolve-font (or variants []) ctx))))
       (p/fmap (fn [_]
                 (doseq [shape (vals scene)
                         :when (= :text (:type shape))]
                   (wasm/update-text-layout! (:id shape)))
                 nil))))

(defn- reset-on-error
  [promise]
  (p/merr (fn [cause]
            ;; A panic can leave the instance aborted: rebuild next time.
            (when-not (= :invalid-op (:type (ex-data cause)))
              (reset! module* nil))
            (p/rejected cause))
          promise))

(defn- render-ids
  "Renders each id of the loaded scene."
  [ids {:keys [scale format]}]
  (mapv (fn [id]
          (case format
            :svg (wasm/render-shape-svg id scale)
            :pdf (wasm/render-shape-pdf id)
            (wasm/render-shape-raster id scale format)))
        ids))

(defn- render-scene!
  [ctx scene object-ids {:keys [variants] :as opts}]
  (->> (load-scene! ctx scene object-ids variants)
       (p/fmap (fn [_] (render-ids object-ids opts)))
       (reset-on-error)))

(defn- ->uuid [v] (if (uuid? v) v (uuid/parse v)))

(defn- ->ctx [^js opts] {:base-uri (.-baseUri opts) :token (.-token opts)})

(defn- js-images
  "{uuid-string: bytes} (JS object) -> {uuid bytes}."
  [^js images]
  (when images
    (into {} (map (fn [k] [(uuid/parse k) (unchecked-get images k)])) (js-keys images))))

(defn- guard
  "Calls `f` and always returns a promise: synchronous throws become
  rejections, so JS callers only ever need `await`/`catch`."
  [f]
  (try
    (p/promise (f))
    (catch :default cause
      (p/rejected cause))))

(defn- ->js
  "clj->js that also turns uuids into strings."
  [v]
  (clj->js (walk/postwalk #(if (uuid? %) (str %) %) v)))

(defn render-shape
  "JS entry. Renders one shape of a file to image bytes.

  opts: {baseUri, token, fileId, pageId, objectId, scale=1, format='png'}
  Resolves to a Uint8Array."
  [^js opts]
  (guard
   #(let [ctx       (->ctx opts)
          file-id   (->uuid (.-fileId opts))
          page-id   (->uuid (.-pageId opts))
          object-id (->uuid (.-objectId opts))
          scale     (or (.-scale opts) 1)
          format    (keyword (or (.-format opts) "png"))]
    (->> (ensure-module!)
         (p/mcat (fn [_]
                   (p/all [(rpc ctx :get-page {:file-id file-id
                                               :page-id page-id
                                               :object-id #{object-id}})
                           (->> (rpc ctx :get-font-variants {:file-id file-id})
                                (p/merr (fn [_] (p/resolved []))))])))
         (p/mcat (fn [[page variants]]
                   (render-scene! ctx (:objects page) [object-id]
                                  {:scale scale :format format :variants variants})))
         (p/fmap first)))))

;; --- editing

(defn- measure-texts!
  "Text id -> {:width :height} laid out by render-wasm with the real fonts."
  [ctx state variants]
  (let [texts (:texts state)]
    (if (empty? texts)
      (p/resolved {})
      (->> (load-scene! ctx (edit/objects state) (vec texts) variants)
           (p/fmap (fn [_]
                     (into {} (keep (fn [id] (when-let [dims (edit/text-dimensions id)] [id dims])))
                           texts)))))))

(defn- add-position-data!
  "Recomputes the position data of texts that were created, edited or moved."
  [ctx state variants]
  (let [ids (edit/text-ids state)]
    (if (empty? ids)
      (p/resolved state)
      (let [objects (edit/objects state)]
        (->> (load-scene! ctx objects (vec ids) variants)
             (p/fmap (fn [_]
                       (edit/with-position-data
                         state
                         (into {} (map (fn [id] [id (edit/position-data (get objects id))])) ids)))))))))

(defn- edit-objects!
  "Runs the edit pipeline on `objects`: build changes, measure texts, apply
  layout/geometry, size `contain` image fills, compute text position data.
  `variants` are the team's font variants. Resolves to the edit state."
  [ctx page-id objects ops variants]
  (reset-on-error
   (p/let [_     (ensure-module!)
           media (media-infos! ctx ops)
           state (p/do (edit/build page-id objects ops {:fonts (fonts/team-fonts variants)
                                                        :media media}))
           ;; pass 1: texts measured at their own width, then layouts
           dims  (measure-texts! ctx state variants)
           state (p/do (edit/second-pass (edit/apply-geometry state dims)))
           ;; pass 2: auto-height texts whose width a layout just changed
           dims  (measure-texts! ctx state variants)
           state (p/do (edit/fit-images (edit/apply-geometry state dims)))]
     (add-position-data! ctx state variants))))

(defn- edit-result
  [state]
  (let [objects (edit/objects state)]
    {:changes (count (:redo-changes (:changes state)))
     :created (into {} (map (fn [[ref id]] [ref (str id)])) (:refs state))
     :createdIds (mapv str (:created state))
     :deleted (mapv str (:deleted state))
     :shapes (into [] (keep #(some->> (get objects %) (edit/shape-summary objects)))
                   (concat (:created state) (remove (set (:created state)) (:moved state))))}))

(defn- ops->clj [ops] (js->clj ops :keywordize-keys true))

(defn- first-page-id
  [file]
  (first (get-in file [:data :pages])))

(defn- save-changes!
  "Saves `changes` to `file` in one update-file (unless it is a dry run or
  nothing changed) and adds :revn and :saved to `result`."
  [ctx file changes dry-run? result]
  (if (or dry-run? (empty? changes))
    (p/resolved (assoc result :revn (:revn file) :saved false))
    (->> (rpc ctx :update-file {:id (:id file)
                                :session-id (uuid/next)
                                :revn (:revn file)
                                :vern (or (:vern file) 0)
                                :changes (vec changes)})
         (p/fmap (fn [_] (assoc result :revn (inc (:revn file)) :saved true))))))

(defn- font-variants!
  "The team's font variants for a file; none when they can't be read (the
  edit still works with Google and builtin fonts)."
  [ctx file-id]
  (->> (rpc ctx :get-font-variants {:file-id file-id})
       (p/merr (fn [_] (p/resolved [])))))

(defn apply-ops
  "JS entry. Applies `ops` to a page of a Penpot file and saves them.

  opts: {baseUri, token, fileId, pageId?, ops, dryRun?}
  Resolves to {changes, created: {ref: id}, createdIds, deleted, shapes, revn}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          ops     (ops->clj (.-ops opts))]
    (->> (p/all [(rpc ctx :get-file {:id file-id})
                 (font-variants! ctx file-id)])
         (p/mcat (fn [[file variants]]
                   (let [page-id (or (some-> (.-pageId opts) ->uuid) (first-page-id file))
                         page    (get-in file [:data :pages-index page-id])]
                     (when-not page
                       (throw (ex-info (str "Page " page-id " not found in file " file-id) {})))
                     (->> (edit-objects! ctx page-id (:objects page) ops variants)
                          (p/mcat (fn [state]
                                    (save-changes! ctx file (:redo-changes (:changes state)) (.-dryRun opts)
                                                   (assoc (edit-result state) :pageId (str page-id)))))))))
         (p/fmap ->js)))))

(defn apply-ops-offline
  "JS entry. Applies `ops` to an empty in-memory page (no Penpot needed) and
  renders `renderRef` (a ref or id; default: the first created shape).

  opts: {renderRef?, scale?, images?: {mediaId: bytes} for image fills,
         gfonts?: true to load Google fonts straight from fonts.gstatic.com}
  Resolves to {png, changes, created, shapes, tree}."
  [^js ops ^js opts]
  (guard
   #(let [page-id (uuid/next)
          objects (:objects (ctp/make-empty-page {:id page-id :name "Page 1"}))
          ops     (ops->clj ops)
          scale   (or (some-> opts .-scale) 1)
          ctx     {:images (js-images (some-> opts .-images))
                   :gfonts-direct? (boolean (some-> opts .-gfonts))}]
    (->> (edit-objects! ctx page-id objects ops [])
         (p/mcat (fn [state]
                   (let [objects (edit/objects state)
                         target  (let [r (some-> opts .-renderRef)]
                                   (or (get (:refs state) r) (some-> r uuid/parse*) (first (:created state))))]
                     (->> (if target
                            (render-scene! ctx objects [target] {:scale scale :format :png :variants []})
                            (p/resolved [nil]))
                          (p/fmap (fn [[png]]
                                    (-> (edit-result state)
                                        (assoc :tree (edit/tree objects uuid/zero 8))
                                        (assoc :objects (into {}
                                                              (map (fn [[id shape]]
                                                                     [(str id) (select-keys shape [:type :fills :content])]))
                                                              (select-keys objects (:created state))))
                                        (->js)
                                        (doto (unchecked-set "png" png)))))))))))))

(defn page-summary
  "JS entry. Compact tree of a page for the AI.

  opts: {baseUri, token, fileId, pageId?, rootId?, depth=4}"
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          page-id (some-> (.-pageId opts) ->uuid)]
    (->> (rpc ctx :get-page (cond-> {:file-id file-id}
                              page-id (assoc :page-id page-id)))
         (p/fmap (fn [page]
                   (let [objects (:objects page)
                         root    (or (some-> (.-rootId opts) ->uuid) uuid/zero)]
                     (->js {:pageId (str (:id page))
                            :pageName (:name page)
                            :tree (edit/tree objects root (or (.-depth opts) 4))}))))))))

;; --- projects, files and pages

(defn create-project
  "JS entry. Creates a project.

  opts: {baseUri, token, name, teamId?} — without teamId, the account's
  default team. Resolves to {projectId, name, teamId, teamName?}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          team-id (some-> (.-teamId opts) ->uuid)]
    (p/let [teams   (if team-id (p/resolved nil) (rpc ctx :get-teams {}))
            team    (when-not team-id
                      (or (d/seek :is-default teams) (first teams)
                          (throw (ex-info "This account has no team to create the project in" {}))))
            team-id (or team-id (:id team))
            project (rpc ctx :create-project {:team-id team-id :name (.-name opts)})]
      (->js (d/without-nils {:projectId (:id project)
                             :name (:name project)
                             :teamId team-id
                             :teamName (:name team)}))))))

(defn file-pages
  "JS entry. Pages of a file, in order: [{id, name}]."
  [^js opts]
  (guard
   #(->> (rpc (->ctx opts) :get-file {:id (->uuid (.-fileId opts))})
         (p/fmap (fn [file] (->js (:pages (pages/result (pages/initial (:data file))))))))))

(defn create-file
  "JS entry. Creates a file in a project.

  opts: {baseUri, token, projectId, name, pages?: [name, ...]} — `pages`
  names the first page and adds the others, in order, in the same call.
  Resolves to {fileId, name, projectId, pages: [{id, name}], revn}."
  [^js opts]
  (guard
   #(let [ctx        (->ctx opts)
          project-id (->uuid (.-projectId opts))
          names      (some-> (.-pages opts) js->clj seq vec)]
    (p/let [created (rpc ctx :create-file {:name (.-name opts) :project-id project-id})
            file    (rpc ctx :get-file {:id (:id created)})
            data    (:data file)
            ops     (when names
                      (into [{:op "rename" :id (str (first (:pages data))) :name (first names)}]
                            (map (fn [n] {:op "add" :name n}))
                            (rest names)))
            result  (if ops
                      (let [state (pages/build data ops)]
                        (save-changes! ctx file (:changes state) false (pages/result state)))
                      (p/resolved (assoc (pages/result (pages/initial data)) :revn (:revn file))))]
      (->js (-> result
                (select-keys [:pages :revn])
                (assoc :fileId (str (:id file))
                       :name (:name file)
                       :projectId (str project-id))))))))

(defn edit-pages
  "JS entry. Adds, renames, deletes and moves pages of a file, saved in one
  update-file.

  opts: {baseUri, token, fileId, ops, dryRun?}
  Resolves to {pages: [{id, name}], created: {ref: id}, addedIds, deleted, revn, saved}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          ops     (ops->clj (.-ops opts))]
    (->> (rpc ctx :get-file {:id file-id})
         (p/mcat (fn [file]
                   (let [state (pages/build (:data file) ops)]
                     (save-changes! ctx file (:changes state) (.-dryRun opts) (pages/result state)))))
         (p/fmap ->js)))))

;; --- fonts and media

(defn list-fonts
  "JS entry. Font families for texts, grouped by source.

  opts: {baseUri, token, fileId? | teamId?, search?, limit=30} — fileId or
  teamId adds the team's uploaded fonts.
  Resolves to {team, builtin, google, googleMatches}."
  [^js opts]
  (guard
   #(let [ctx    (->ctx opts)
          params (cond
                   (.-fileId opts) {:file-id (->uuid (.-fileId opts))}
                   (.-teamId opts) {:team-id (->uuid (.-teamId opts))})]
    (->> (if params (rpc ctx :get-font-variants params) (p/resolved []))
         (p/fmap (fn [variants]
                   (->js (fonts/search (fonts/team-fonts variants)
                                       (.-search opts)
                                       (or (.-limit opts) 30)))))))))

(defn upload-image
  "JS entry. Adds an image to a file's media, for image fills.

  opts: {baseUri, token, fileId, name, url} — the Penpot backend downloads it —
  or {baseUri, token, fileId, name, data (bytes), mimeType}.
  Resolves to {id, name, width, height, mtype}."
  [^js opts]
  (guard
   #(let [ctx     (->ctx opts)
          file-id (->uuid (.-fileId opts))
          name    (.-name opts)]
    (->> (if-let [url (.-url opts)]
           (rpc ctx :create-file-media-object-from-url
                {:file-id file-id :url url :name name :is-local true})
           (rpc-multipart ctx :upload-file-media-object
                          {"file-id" file-id "name" name "is-local" "true"}
                          {:field "content" :data (.-data opts) :mtype (.-mimeType opts) :filename name}))
         (p/fmap (fn [mobj]
                   (->js (select-keys mobj [:id :name :width :height :mtype]))))))))

;; --- offline helpers for tests

(defn pages-offline
  "JS entry (tests). Applies page `ops` to an in-memory file with `pages`
  ([{id, name, mainComponent?}]). Resolves to the edit-pages result plus the
  raw changes."
  [^js pages ^js ops]
  (guard
   #(let [pages (js->clj pages :keywordize-keys true)
          ids   (mapv (comp uuid/parse :id) pages)
          data  {:pages ids
                 :pages-index (into {}
                                    (map (fn [id {:keys [name mainComponent]}]
                                           [id {:id id :name name
                                                :objects (if mainComponent
                                                           {id {:id id :main-instance true}}
                                                           {})}])
                                         ids pages))}
          state (pages/build data (ops->clj ops))]
      (->js (assoc (pages/result state) :changes (:changes state))))))

(defn image-info
  "JS entry (tests). {mtype, width, height} of encoded image bytes, or null."
  [bytes]
  (->js (media/info bytes)))

;; --- offline smoke test: no backend needed

(defn- demo-scene
  []
  (let [frame-id (uuid/next)
        rect-id  (uuid/next)
        ell-id   (uuid/next)
        frame    (cts/setup-shape {:id frame-id :type :frame :name "Demo"
                                   :x 0 :y 0 :width 320 :height 200
                                   :parent-id uuid/zero :frame-id uuid/zero
                                   :fills [{:fill-color "#F4F1EA" :fill-opacity 1}]
                                   :shapes [rect-id ell-id]})
        rect     (cts/setup-shape {:id rect-id :type :rect :name "Rect"
                                   :x 24 :y 24 :width 160 :height 96 :r1 12 :r2 12 :r3 12 :r4 12
                                   :parent-id frame-id :frame-id frame-id
                                   :fills [{:fill-color "#3366FF" :fill-opacity 1}]})
        ellipse  (cts/setup-shape {:id ell-id :type :circle :name "Circle"
                                   :x 200 :y 80 :width 96 :height 96
                                   :parent-id frame-id :frame-id frame-id
                                   :fills [{:fill-color "#E8593C" :fill-opacity 1}]})]
    {:frame-id frame-id
     :objects  {frame-id frame rect-id rect ell-id ellipse}}))

(defn render-demo
  "JS entry. Renders a synthetic frame (rect + circle) to PNG bytes without
  any Penpot backend. Used by the CI smoke test."
  []
  (->> (ensure-module!)
       (p/mcat (fn [_]
                 (let [{:keys [frame-id objects]} (demo-scene)]
                   (render-scene! {} objects [frame-id] {:scale 2 :format :png}))))
       (p/fmap first)))

;; --- JS-friendly RPC (decoded result converted to plain JS)

(defn rpc-js
  "JS entry. `rpc({baseUri, token}, 'get-profile', {...})` -> plain JS value."
  [^js ctx command ^js params]
  (guard
   #(->> (rpc (->ctx ctx)
              (keyword command)
              (js->clj (or params #js {}) :keywordize-keys true))
         (p/fmap ->js))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Token edits mirror Penpot's Tokens panel (app.main.data.workspace.tokens
;; .library-edit, MPL-2.0, Copyright (c) KALEIDOS SUBSIDIARY SL): the same
;; changes, validated with the same schemas.

(ns penpot-mcp.tokens
  "Design tokens of a file — themes, sets and tokens — read and edited with
  Penpot's own token library (`app.common.types.tokens-lib`) and changes,
  like the Tokens panel.

  Tokens live in sets; themes group sets (\"Light\" uses \"core\" and
  \"light\"); the file's tokens status says which themes and sets are
  active. Activating a theme activates its sets and deactivates the other
  themes of its group; activating a set directly deactivates every theme,
  as in the panel. A file may take its tokens from a library (its tokens
  source): those can only be read here, and edited in that library.

  Token values are kept as written (\"{color.primary}\", \"{space.md} * 2\").
  `resolve` computes what they stand for — references, and arithmetic on
  numbers with one unit — for reading and for applying tokens to shapes;
  Penpot's editor does the same with Style Dictionary."
  (:require
   [app.common.data :as d]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.logic.tokens :as clt]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [penpot-mcp.props :as props]
   [penpot-mcp.util :as u]))

;; --- types

(def ^:private type-by-key
  (merge (into {} (map (fn [t] [(u/normalize (name t)) t])) cto/token-types)
         (into {} (map (fn [[dtcg t]] [(u/normalize dtcg) t])) cto/dtcg-token-type->token-type)))

(defn token-type!
  [v]
  (or (when (or (string? v) (keyword? v)) (get type-by-key (u/normalize (name v))))
      (u/error! (str "Invalid token type " (pr-str v) ". Types: "
                     (str/join ", " (sort (map name cto/token-types))))
                {:field "type" :value v})))

;; --- resolving values

(def ^:private max-depth 32)

(defn- tokenize
  "Tokens of an arithmetic expression: numbers with an optional unit,
  operators and parentheses; nil when it is not one."
  [s]
  (loop [s (str/trim s) out []]
    (if (str/blank? s)
      out
      (if-let [[m n unit] (re-find #"^(\d+(?:\.\d+)?|\.\d+)([a-z%]*)" s)]
        (recur (str/trim (subs s (count m))) (conj out [:num (js/parseFloat n) unit]))
        (if-let [[op] (re-find #"^[-+*/()]" s)]
          (recur (str/trim (subs s 1)) (conj out [:op op]))
          nil)))))

(defn- evaluate
  "{:n number :unit unit} of an arithmetic expression whose numbers share
  one unit (or none), or nil."
  [s]
  (when-let [toks (seq (tokenize s))]
    (let [units (into #{} (comp (filter #(= :num (first %))) (map #(nth % 2)) (remove str/blank?)) toks)]
      (when (<= (count units) 1)
        (let [pos  (volatile! 0)
              peek* #(nth toks @pos nil)
              next* #(let [t (peek*)] (vswap! pos inc) t)]
          (letfn [(primary []
                    (let [[kind v] (next*)]
                      (cond
                        (= kind :num) v
                        (= v "-") (when-let [p (primary)] (- p))
                        (= v "(") (let [r (expr)] (when (= [:op ")"] (next*)) r))
                        :else nil)))
                  (term []
                    (loop [acc (primary)]
                      (let [[kind op] (peek*)]
                        (if (and acc (= kind :op) (#{"*" "/"} op))
                          (do (next*)
                              (let [r (primary)]
                                (recur (when r (if (= op "*") (* acc r) (/ acc r))))))
                          acc))))
                  (expr []
                    (loop [acc (term)]
                      (let [[kind op] (peek*)]
                        (if (and acc (= kind :op) (#{"+" "-"} op))
                          (do (next*)
                              (let [r (term)]
                                (recur (when r (if (= op "+") (+ acc r) (- acc r))))))
                          acc))))]
            (let [n (expr)]
              (when (and (number? n) (js/isFinite n) (= @pos (count toks)))
                {:n (/ (js/Math.round (* n 10000)) 10000) :unit (first units)}))))))))

(defn- arithmetic?
  [s]
  (and (re-find #"[-+*/]" (str/trim s))
       (re-matches #"^[\d\s.+\-*/()a-z%]+$" (str/trim s))))

(declare resolve-value)

(defn- resolve-string
  [tokens s seen]
  (let [refs (cto/find-token-value-references s)]
    (if (empty? refs)
      (if (arithmetic? s)
        (if-let [{:keys [n unit]} (evaluate s)]
          (str n unit)
          s)
        s)
      (let [resolved (reduce (fn [s ref]
                               (let [token (get tokens ref)]
                                 (cond
                                   (nil? token) (throw (ex-info (str "{" ref "} does not exist") {:ref ref}))
                                   (or (contains? seen ref) (> (count seen) max-depth))
                                   (throw (ex-info (str "{" ref "} is a circular reference") {:ref ref}))
                                   :else
                                   (let [v (resolve-value tokens (:value token) (conj seen ref))]
                                     (if (string? v)
                                       (str/replace s (str "{" ref "}") v)
                                       (if (= s (str "{" ref "}"))
                                         (reduced v)
                                         (throw (ex-info (str "{" ref "} is a composite value") {:ref ref}))))))))
                             s
                             refs)]
        (if (string? resolved) (resolve-string tokens resolved seen) resolved)))))

(defn resolve-value
  "What token value `v` stands for, with `tokens` (name -> token): references
  replaced, arithmetic computed. Throws ex-info for broken references."
  ([tokens v] (resolve-value tokens v #{}))
  ([tokens v seen]
   (cond
     (string? v) (resolve-string tokens v seen)
     (map? v) (into {} (map (fn [[k x]] [k (resolve-value tokens x seen)])) v)
     (sequential? v) (mapv #(resolve-value tokens % seen) v)
     :else v)))

;; --- reading

(defn- lib [data] (cfo/get-tokens-lib data))

(defn- status
  [data]
  (or (cfo/get-tokens-status data) (ctos/make-tokens-status)))

(defn active-tokens
  "name -> token of the active sets (later sets win), then the tokens of the
  other sets for names no active set has."
  [data source-data]
  (if-let [l (lib source-data)]
    (let [all (reduce (fn [m s] (merge m (ctob/get-tokens l (ctob/get-id s)))) {} (ctob/get-sets l))]
      (merge all (cfo/get-tokens-in-active-sets (status data) l)))
    {}))

(defn- token-desc
  [tokens set-name token]
  (let [v (:value token)
        [resolved error] (try [(resolve-value tokens v) nil]
                              (catch :default e [nil (ex-message e)]))]
    (d/without-nils
     {:set set-name
      :name (:name token)
      :type (name (:type token))
      :value v
      :resolved (when (and (nil? error) (not= resolved v)) resolved)
      :error error
      :description (not-empty (:description token))})))

(defn describe
  "JSON-friendly description of the tokens of a file (`data`), taken from
  `source-data` (the file itself or its tokens source). opts: `:set` (name),
  `:type`, `:search` (part of the name), `:limit`."
  [data source-data {:keys [set type search limit]}]
  (let [l      (lib source-data)
        st     (status data)
        tokens (active-tokens data source-data)
        type   (some-> type token-type!)
        q      (some-> search str/lower not-empty)
        sets   (if l (vec (ctob/get-sets l)) [])
        only   (when (some? set)
                 (let [s (when l (ctob/get-set-by-name l (ctob/normalize-set-name (str set))))]
                   (or s (u/error! (str "No token set " (pr-str set)) {:field "set" :value set}))))
        rows   (for [s (if only [only] sets)
                     t (vals (ctob/get-tokens l (ctob/get-id s)))
                     :when (and (or (nil? type) (= type (:type t)))
                                (or (nil? q) (str/includes? (str/lower (:name t)) q)))]
                 [s t])
        limit  (or limit 200)]
    (d/without-nils
     {:themes (when l
                (vec (for [t (ctob/get-themes-no-hidden l)]
                       (d/without-nils
                        {:id (str (:id t))
                         :group (not-empty (:group t))
                         :name (:name t)
                         :description (not-empty (:description t))
                         :sets (vec (sort (:sets t)))
                         :active (ctos/theme-active? st (:id t))}))))
      :sets (vec (for [s sets]
                   (d/without-nils
                    {:id (str (ctob/get-id s))
                     :name (ctob/get-name s)
                     :description (not-empty (ctob/get-description s))
                     :active (ctos/set-active? st (ctob/get-id s))
                     :tokens (count (ctob/get-tokens l (ctob/get-id s)))})))
      :tokens (mapv (fn [[s t]] (token-desc tokens (ctob/get-name s) t)) (take limit rows))
      :totalTokens (count rows)})))

;; --- editing

(def ^:private op-props
  {"addset"      [:op :name :description :active]
   "updateset"   [:op :set :name :description :active]
   "deleteset"   [:op :set]
   "addtheme"    [:op :name :group :description :sets :active]
   "updatetheme" [:op :theme :name :group :description :sets :active]
   "deletetheme" [:op :theme]
   "addtoken"    [:op :set :type :name :value :description]
   "updatetoken" [:op :set :token :name :value :description]
   "deletetoken" [:op :set :token]})

(defn- text!
  [v field & {:keys [blank?]}]
  (if (and (string? v) (or blank? (not (str/blank? v))) (<= (count v) 2048))
    v
    (u/error! (str "Invalid " field ": " (pr-str v) " (expected a text)") {:field field :value v})))

(defn- bool!
  [v field]
  (if (boolean? v) v (u/error! (str "Invalid " field ": " (pr-str v) " (expected true or false)") {:field field :value v})))

(defn- sets-hint
  [l]
  (let [names (when l (map ctob/get-name (ctob/get-sets l)))]
    (if (seq names) (str ". Sets: " (str/join ", " (map pr-str names))) ". The file has no token sets yet")))

(defn- find-set
  [data v]
  (let [l  (lib data)
        id (when (string? v) (uuid/parse* v))]
    (or (when (and l (string? v))
          (or (when id (ctob/get-set l id))
              (ctob/get-set-by-name l (ctob/normalize-set-name v))))
        (u/error! (str "No token set " (pr-str v) (sets-hint l)) {:field "set" :value v}))))

(defn- find-theme
  [data v]
  (let [l      (lib data)
        themes (if l (ctob/get-themes-no-hidden l) [])
        id     (when (string? v) (uuid/parse* v))
        k      (some-> v str str/trim str/lower)
        by-path (filter #(= k (str/lower (ctob/get-theme-path %))) themes)
        by-name (filter #(= k (str/lower (:name %))) themes)]
    (or (when id (d/seek #(= id (:id %)) themes))
        (first by-path)
        (when (= 1 (count by-name)) (first by-name))
        (u/error! (str "No token theme " (pr-str v)
                       (if (> (count by-name) 1)
                         (str " (several groups have one: use \"group/name\")")
                         (if (seq themes)
                           (str ". Themes: " (str/join ", " (map #(pr-str (ctob/get-theme-path %)) themes)))
                           ". The file has no themes yet")))
                  {:field "theme" :value v}))))

(defn- find-token
  [data set v]
  (let [tokens (ctob/get-tokens (lib data) (ctob/get-id set))
        id     (when (string? v) (uuid/parse* v))]
    (or (when id (d/seek #(= id (:id %)) (vals tokens)))
        (get tokens v)
        (u/error! (str "No token " (pr-str v) " in set " (pr-str (ctob/get-name set))
                       (when-let [similar (seq (take 10 (filter #(str/includes? (str/lower %) (str/lower (str v)))
                                                                (keys tokens))))]
                         (str ". Similar: " (str/join ", " similar))))
                  {:field "token" :value v}))))

(defn- kebab-keys
  [m]
  (into {} (map (fn [[k v]] [(keyword (str/kebab (name k))) v])) m))

(defn- token-value
  "A token value from JSON: numbers as strings, typography and shadow keys
  in Penpot's spelling, a font family list from a comma separated text."
  [type v]
  (let [v (cond
            (number? v) (str v)
            (and (= type :font-family) (string? v) (not (str/starts-with? (str/trim v) "{")))
            (mapv str/trim (str/split v #","))
            (and (= type :typography) (map? v))
            (into {}
                  (map (fn [[k x]]
                         [k (cond
                              (number? x) (str x)
                              (and (= k :font-family) (string? x) (not (str/starts-with? (str/trim x) "{")))
                              (mapv str/trim (str/split x #","))
                              :else x)]))
                  (kebab-keys v))
            (and (= type :shadow) (sequential? v))
            (mapv #(if (map? %) (into {} (map (fn [[k x]] [k (if (number? x) (str x) x)])) (kebab-keys %)) %) v)
            :else v)]
    (when-not (sm/validate (cfo/make-token-value-schema type) v)
      (u/error! (str "Invalid value for a " (name type) " token: " (pr-str v))
                {:field "value" :value v}))
    v))

(defn- check-token!
  "Name and references of a token about to be saved in `set`."
  [data set {:keys [id name value]}]
  (when-not (and (string? name) (<= 1 (count name) 255) (re-matches cto/token-name-validation-regex name))
    (u/error! (str "Invalid token name " (pr-str name)
                   ": use letters, digits, - and _, with dots for groups, e.g. \"color.primary\"")
              {:field "name" :value name}))
  (let [l      (lib data)
        tokens (into {} (remove (fn [[_ t]] (= id (:id t)))) (ctob/get-tokens l (ctob/get-id set)))]
    (when (contains? tokens name)
      (u/error! (str "Set " (pr-str (ctob/get-name set)) " already has a token named " (pr-str name))
                {:field "name" :value name}))
    (when (ctob/token-name-path-exists? name (ctob/tokens-tree tokens))
      (u/error! (str "Token name " (pr-str name) " clashes with a token group of set " (pr-str (ctob/get-name set))
                     " (a token can't be named like the group of other tokens, or be inside a token)")
                {:field "name" :value name}))
    (when (cto/token-value-self-reference? name value)
      (u/error! (str "Token " (pr-str name) " refers to itself") {:field "value" :value value}))
    (let [all (assoc (ctob/get-all-tokens-map l) name {:name name :value value})]
      (doseq [ref (cond (string? value) (cto/find-token-value-references value)
                        (map? value) (mapcat cto/find-token-value-references (filter string? (vals value)))
                        :else nil)]
        (when-not (contains? all ref)
          (u/error! (str "Token " (pr-str name) " refers to {" ref "}, which doesn't exist") {:field "value" :value value})))
      (when (cfo/token-circular-reference? all name)
        (u/error! (str "Token " (pr-str name) " makes a circular reference") {:field "value" :value value})))))

(defn- step
  "Applies `f` (changes builder -> builder) on the current data."
  [state f]
  (let [changes (f (-> (pcb/empty-changes :mcp) (pcb/with-library-data (:data state))))
        redo    (:redo-changes changes)]
    (-> state
        (update :changes into redo)
        (assoc :data (cfc/process-changes (:data state) redo false)))))

(defn- sync-status
  [state]
  (step state #(clt/generate-sync-tokens-status-with-lib % (status (:data state)) (lib (:data state)))))

(defn- set-active
  [state set-id active?]
  (step state #(clt/generate-set-enabled-token-set % (status (:data state)) (lib (:data state)) set-id active?)))

(defn- theme-active
  [state theme-id active?]
  (step state #(clt/generate-set-theme-status % (status (:data state)) (lib (:data state)) theme-id active?)))

(defn- set-name!
  [data v current-id]
  (let [n (some-> (text! v "name") ctob/normalize-set-name)]
    (when (str/blank? n)
      (u/error! (str "Invalid set name " (pr-str v)) {:field "name" :value v}))
    (when-let [other (some-> (lib data) (ctob/get-set-by-name n))]
      (when (not= current-id (ctob/get-id other))
        (u/error! (str "There is already a token set named " (pr-str n)) {:field "name" :value v})))
    n))

(defn- theme-sets!
  [data v]
  (when (some? v)
    (when-not (and (sequential? v) (every? string? v))
      (u/error! "sets must be an array of token set names" {:field "sets" :value v}))
    (into #{} (map #(ctob/get-name (find-set data %))) v)))

(defn- check-theme-name!
  [data group name current-id]
  (when-let [other (some-> (lib data) (ctob/get-theme-by-name group name))]
    (when (not= current-id (:id other))
      (u/error! (str "There is already a theme " (pr-str (str (when (seq group) (str group "/")) name)))
                {:field "name" :value name}))))

(defn- apply-op
  [state op]
  (when-not (map? op)
    (u/error! "Each op must be an object like {\"op\": \"addSet\", \"name\": \"core\"}" {:field "op"}))
  (let [kind    (some-> (:op op) str str/lower)
        allowed (or (get op-props kind)
                    (u/error! (str "Unknown op " (pr-str (:op op)) ". Use addSet, updateSet, deleteSet, addTheme, "
                                   "updateTheme, deleteTheme, addToken, updateToken or deleteToken")
                              {:field "op"}))
        _       (props/check-keys! op allowed (str "for " (:op op)))
        data    (:data state)]
    (case kind
      "addset"
      (let [n   (set-name! data (:name op) nil)
            set (ctob/make-token-set :name n :description (or (some-> (:description op) (text! "description" :blank? true)) ""))
            st  (-> state
                    (step #(pcb/set-token-set % (ctob/get-id set) set))
                    (update :created assoc (str "set:" n) (str (ctob/get-id set))))]
        (if (some? (:active op)) (set-active st (ctob/get-id set) (bool! (:active op) "active")) st))

      "updateset"
      (let [set (find-set data (:set op))
            id  (ctob/get-id set)
            st  (if (contains? op :name)
                  (let [n (set-name! data (:name op) id)]
                    (step state #(pcb/rename-token-set % id n)))
                  state)
            st  (if (contains? op :description)
                  (let [set (find-set (:data st) (str id))]
                    (step st #(pcb/set-token-set % id (ctob/set-description set (text! (:description op) "description" :blank? true)))))
                  st)]
        (if (some? (:active op)) (set-active st id (bool! (:active op) "active")) st))

      "deleteset"
      (let [set (find-set data (:set op))]
        (-> state
            (step #(pcb/set-token-set % (ctob/get-id set) nil))
            (sync-status)))

      "addtheme"
      (let [group (or (some-> (:group op) (text! "group" :blank? true) str/trim) "")
            n     (str/trim (text! (:name op) "name"))
            _     (check-theme-name! data group n nil)
            theme (ctob/make-token-theme :name n :group group
                                         :description (or (some-> (:description op) (text! "description" :blank? true)) "")
                                         :sets (or (theme-sets! data (:sets op)) #{}))
            st    (-> state
                      (step #(pcb/set-token-theme % (:id theme) theme))
                      (update :created assoc (str "theme:" (ctob/get-theme-path theme)) (str (:id theme))))]
        (if (some? (:active op)) (theme-active st (:id theme) (bool! (:active op) "active")) st))

      "updatetheme"
      (let [theme (find-theme data (:theme op))
            group (if (contains? op :group) (str/trim (text! (:group op) "group" :blank? true)) (:group theme))
            n     (if (contains? op :name) (str/trim (text! (:name op) "name")) (:name theme))
            _     (check-theme-name! data group n (:id theme))
            theme' (ctob/make-token-theme
                    (cond-> (assoc (into {} theme) :group group :name n)
                      (contains? op :description) (assoc :description (text! (:description op) "description" :blank? true))
                      (contains? op :sets) (assoc :sets (theme-sets! data (:sets op)))))
            st    (if (= theme theme') state (step state #(clt/generate-update-token-theme % theme')))]
        (if (some? (:active op)) (theme-active st (:id theme) (bool! (:active op) "active")) st))

      "deletetheme"
      (let [theme (find-theme data (:theme op))]
        (-> state
            (step #(pcb/set-token-theme % (:id theme) nil))
            (sync-status)))

      "addtoken"
      (let [set   (find-set data (:set op))
            type  (token-type! (:type op))
            value (token-value type (:value op))
            token (ctob/make-token :name (:name op) :type type :value value
                                   :description (or (some-> (:description op) (text! "description" :blank? true)) ""))]
        (check-token! data set token)
        (-> state
            (step #(pcb/set-token % (ctob/get-id set) (:id token) token))
            (update :created assoc (str "token:" (ctob/get-name set) "/" (:name token)) (str (:id token)))))

      "updatetoken"
      (let [set   (find-set data (:set op))
            token (find-token data set (:token op))
            value (if (contains? op :value) (token-value (:type token) (:value op)) (:value token))
            token' (ctob/make-token (cond-> (assoc (into {} token) :value value)
                                      (contains? op :name) (assoc :name (:name op))
                                      (contains? op :description)
                                      (assoc :description (text! (:description op) "description" :blank? true))))]
        (check-token! data set token')
        (step state #(pcb/set-token % (ctob/get-id set) (:id token) token')))

      "deletetoken"
      (let [set   (find-set data (:set op))
            token (find-token data set (:token op))]
        (step state #(pcb/set-token % (ctob/get-id set) (:id token) nil))))))

(defn build-ops
  "Applies token `ops` to file `data` (its own tokens). Returns {:changes
  [redo changes] :data data' :created {\"set:core\" id, …}}."
  [data ops]
  (when-not (and (sequential? ops) (seq ops))
    (u/error! "ops must be a non-empty array of operations" {:field "ops"}))
  (when-not (cfo/editable-tokens? data)
    (u/error! (str "This file takes its tokens from a library (its tokens source " (cfo/get-tokens-source data)
                   "): edit them in that library, or read them with get_tokens")
              {:field "fileId"}))
  (reduce (fn [state [idx op]]
            (try
              (apply-op state op)
              (catch :default cause
                (throw (ex-info (str "ops[" idx "]: " (ex-message cause))
                                (assoc (ex-data cause) :index idx :type :invalid-op)
                                cause)))))
          {:data data :changes [] :created {}}
          (map-indexed vector ops)))

;; --- applying tokens to shapes (edit_page "tokens")

(def ^:private number-types #{:dimensions :number})

(def ^:private targets
  "edit_page prop -> token types that can set it, the applied-tokens keys
  it records (as the Tokens panel does) and the kind of value."
  {:fill          {:types #{:color} :attrs [:fill] :as :color}
   :textColor     {:types #{:color} :attrs [:fill] :as :color}
   :stroke        {:types #{:color} :attrs [:stroke-color] :as :color}
   :radius        {:types (conj number-types :border-radius) :attrs [:r1 :r2 :r3 :r4] :as :number}
   :opacity       {:types #{:opacity :number} :attrs [:opacity] :as :opacity}
   :width         {:types (conj number-types :sizing) :attrs [:width] :as :number}
   :height        {:types (conj number-types :sizing) :attrs [:height] :as :number}
   :gap           {:types (conj number-types :spacing) :attrs [:row-gap :column-gap] :as :number}
   :rowGap        {:types (conj number-types :spacing) :attrs [:row-gap] :as :number}
   :columnGap     {:types (conj number-types :spacing) :attrs [:column-gap] :as :number}
   :padding       {:types (conj number-types :spacing) :attrs [:p1 :p2 :p3 :p4] :as :number}
   :fontSize      {:types (conj number-types :font-size) :attrs [:font-size] :as :number}
   :fontFamily    {:types #{:font-family} :attrs [:font-family] :as :font-family}
   :fontWeight    {:types #{:font-weight} :attrs [:font-weight] :as :font-weight}
   :letterSpacing {:types (conj number-types :letter-spacing) :attrs [:letter-spacing] :as :number}})

(def target-props (vec (sort (map name (keys targets)))))

(defn unapplied-attrs
  "applied-tokens keys that the explicit props of `op` replace: like the
  editor, a value set by hand detaches the token from that property."
  [op]
  (let [layout (:layout op)]
    (cond-> []
      (or (contains? op :fill) (contains? op :textColor)) (conj :fill)
      (contains? op :stroke) (conj :stroke-color)
      (contains? op :radius) (into [:r1 :r2 :r3 :r4])
      (contains? op :opacity) (conj :opacity)
      (contains? op :width) (conj :width)
      (contains? op :height) (conj :height)
      (and (map? layout) (contains? layout :gap)) (into [:row-gap :column-gap])
      (and (map? layout) (contains? layout :rowGap)) (conj :row-gap)
      (and (map? layout) (contains? layout :columnGap)) (conj :column-gap)
      (and (map? layout) (contains? layout :padding)) (into [:p1 :p2 :p3 :p4])
      (and (some? layout) (not (map? layout))) (into [:row-gap :column-gap :p1 :p2 :p3 :p4])
      (contains? op :fontSize) (conj :font-size)
      (contains? op :fontFamily) (conj :font-family)
      (contains? op :fontWeight) (conj :font-weight)
      (contains? op :letterSpacing) (conj :letter-spacing)
      (contains? op :typography) (into [:font-size :font-family :font-weight :letter-spacing]))))

(defn- hex-color
  "{:color \"#rrggbb\" :opacity} of \"#rgb\", \"#rrggbb\" or \"#rrggbbaa\"."
  [v]
  (when (string? v)
    (let [s (str/lower (str/trim v))]
      (cond
        (re-matches #"^#[0-9a-f]{6}$" s) {:color s :opacity 1}
        (re-matches #"^#[0-9a-f]{3}$" s) (let [[_ r g b] s] {:color (str "#" r r g g b b) :opacity 1})
        (re-matches #"^#[0-9a-f]{8}$" s) {:color (subs s 0 7)
                                          :opacity (/ (js/Math.round (* (/ (js/parseInt (subs s 7) 16) 255) 100)) 100)}))))

(defn- dimension
  "A number from \"8\", \"8px\" or \"0.5rem\" (16px per rem, Penpot's default
  base font size)."
  [v]
  (let [[_ n unit] (re-matches #"^\s*(-?\d*\.?\d+)\s*(px|rem)?\s*$" (str v))]
    (when n
      (* (js/parseFloat n) (if (= "rem" unit) 16 1)))))

(defn- opacity-value
  [v]
  (let [s (str/trim (str v))
        [_ pct] (re-matches #"^(\d*\.?\d+)%$" s)
        n (if pct (/ (js/parseFloat pct) 100) (dimension s))]
    (when (and (number? n) (<= 0 n 1)) n)))

(defn shape-props
  "The edit_page props that `m` ({prop token-name}) stands for, from the
  active `tokens` (name -> token). Returns {:props {prop value} :applied
  {applied-tokens key token-name}}."
  [tokens m]
  (reduce
   (fn [acc [prop token-name]]
     (let [field  (str "tokens." (name prop))
           target (or (get targets prop)
                      (u/error! (str "Unknown property " (pr-str (name prop)) " in \"tokens\". Tokens can set: "
                                     (str/join ", " target-props))
                                {:field field}))
           token  (or (get tokens token-name)
                      (u/error! (str "No token named " (pr-str token-name)
                                     " in the active sets of the file (see get_tokens)")
                                {:field field :value token-name}))
           _      (when-not (contains? (:types target) (:type token))
                    (u/error! (str field ": " (pr-str token-name) " is a " (name (:type token)) " token; "
                                   (name prop) " takes a " (str/join " or " (sort (map name (:types target)))) " token")
                              {:field field :value token-name}))
           value  (try (resolve-value tokens (:value token))
                       (catch :default e
                         (u/error! (str field ": " (pr-str token-name) " can't be resolved: " (ex-message e))
                                   {:field field :value token-name})))
           bad    #(u/error! (str field ": " (pr-str token-name) " resolves to " (pr-str value) ", " %)
                             {:field field :value token-name})
           v      (case (:as target)
                    :color       (or (hex-color value) (bad "not a hex color"))
                    :number      (or (dimension value) (bad "not a number"))
                    :opacity     (or (opacity-value value) (bad "not an opacity from 0 to 1"))
                    :font-family (let [f (if (sequential? value) (first value) (first (str/split (str value) #",")))]
                                   (if (str/blank? (str f)) (bad "not a font family") (str/trim (str f))))
                    :font-weight (if (or (string? value) (number? value))
                                   (first (str/split (str/trim (str value)) #"\s+"))
                                   (bad "not a font weight")))]
       (-> acc
           (assoc-in [:props prop] v)
           (update :applied merge (zipmap (:attrs target) (repeat token-name))))))
   {:props {} :applied {}}
   m))

;; --- propagation: shapes follow token edits and theme switches

(defn- resolved-or-error
  [tokens token-name]
  (try (resolve-value tokens (:value (get tokens token-name)))
       (catch :default e [:error (ex-message e)])))

(defn changed-tokens
  "Names whose token (type or resolved value) differs between `before` and
  `after` (name -> token maps of the active sets)."
  [before after]
  (into #{}
        (filter (fn [n]
                  (let [a (get before n) b (get after n)]
                    (or (not= (:type a) (:type b))
                        (not= (resolved-or-error before n) (resolved-or-error after n))))))
        (into (set (keys before)) (keys after))))

(def ^:private prop-groups
  "edit_page token props by the applied-tokens keys they record, wider
  groups first."
  [[:fill [:fill]] [:stroke [:stroke-color]] [:radius [:r1 :r2 :r3 :r4]] [:opacity [:opacity]]
   [:width [:width]] [:height [:height]] [:gap [:row-gap :column-gap]] [:rowGap [:row-gap]]
   [:columnGap [:column-gap]] [:padding [:p1 :p2 :p3 :p4]] [:fontSize [:font-size]]
   [:fontFamily [:font-family]] [:fontWeight [:font-weight]] [:letterSpacing [:letter-spacing]]])

(defn- applicable?
  [tokens prop token-name]
  (try (shape-props tokens {prop token-name}) true
       (catch :default _ false)))

(defn- mixed-text?
  [shape]
  (and (= :text (:type shape))
       (> (count (into #{} (map #(dissoc % :text)) (txt/node-seq txt/is-text-node? (:content shape)))) 1)))

(defn propagation-ops
  "edit_page update ops that apply again the `changed` tokens to the shapes
  of `objects` using them, as the Tokens panel does after an edit or a theme
  switch. Tokens that no longer exist or resolve are left as they are, and
  so are shapes inside component copies (they follow their main) and texts
  with several styles."
  [objects tokens changed]
  (into []
        (keep (fn [shape]
                (let [applied (:applied-tokens shape)]
                  (when (and (seq applied)
                             (nil? (:shape-ref shape))
                             ;; an update rewrites a text with one style: keep mixed ones
                             (not (mixed-text? shape)))
                    (let [m (first
                             (reduce (fn [[m used] [prop attrs]]
                                       (let [names (map #(get applied %) attrs)
                                             n     (first names)]
                                         (if (and (some? n)
                                                  (not-any? used attrs)
                                                  (apply = names)
                                                  (contains? changed n)
                                                  (applicable? tokens prop n))
                                           [(assoc m prop n) (into used attrs)]
                                           [m used])))
                                     [{} #{}]
                                     prop-groups))]
                      (when (seq m)
                        {:op "update" :id (str (:id shape)) :tokens m}))))))
        (vals objects)))

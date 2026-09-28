(ns clojure-lsp-proxy.rename
  "Rename through the running clojure-lsp (contract C7).

  The proxy asks clojure-lsp for the rename (`textDocument/rename`) and,
  when the answer renames a file (a namespace rename), for the text edits
  that go with it (`workspace/willRenameFiles`, as an editor would ask
  before moving the file). Without `apply` the combined WorkspaceEdit is
  returned untouched. With `apply` the proxy applies it: text edits per
  file from the last position backwards, then the file operations, and
  reports every touched path so that clojure-lsp re-reads them; the reply
  waits for that analysis. A file rename that would overwrite an existing
  file fails before anything is written.

  A symbol can be named instead of a position: `ns/name` for a var, a
  bare `ns` for the namespace itself. The candidates come from
  `workspace/symbol`; each candidate file's `textDocument/documentSymbol`
  tree says which namespace it declares and where the name sits.

  Every text edit is computed and checked against the file before
  anything is written; a failure while writing leaves the files written
  so far in place, and those paths are still reported."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure-lsp-proxy.changes :as changes]
            [clojure-lsp-proxy.detect :as detect]
            [clojure-lsp-proxy.gate :as gate]
            [clojure-lsp-proxy.transport :as transport])
  (:import [java.net URI]))

(def request-timeout-ms 30000)

(defn- request!
  "The result of the proxy's own request, or an exception carrying the
  server's error."
  [proxy method params]
  (let [response (deref (transport/request-server! proxy method params) request-timeout-ms ::timeout)]
    (cond
      (= ::timeout response)
      (throw (ex-info (str method " got no answer within " request-timeout-ms " ms") {:method method}))

      (get response "error")
      (throw (ex-info (str method " failed: " (get-in response ["error" "message"]))
                      {:method method :error (get response "error")}))

      :else (get response "result"))))

(defn uri->path [uri]
  (.getPath (URI. uri)))

;;; finding the position of ns/name

(defn- declares-namespace?
  "Whether the document symbol tree has the namespace `ns-name` at its
  top level (kind 3, or no kind at all in a terse server)."
  [symbols ns-name]
  (boolean (some #(and (= ns-name (get % "name"))
                       (contains? #{3 nil} (get % "kind")))
                 symbols)))

(defn- symbol-position
  "The start of the name of the document symbol called `name` in the
  `textDocument/documentSymbol` tree `symbols`, searched depth first."
  [symbols name]
  (some (fn [symbol]
          (if (= name (get symbol "name"))
            (get-in symbol ["selectionRange" "start"])
            (symbol-position (get symbol "children") name)))
        symbols))

(defn- resolve-symbol
  "The uri and position of the definition of `symbol`: `ns/name` for a
  var, `ns` for a namespace (whose workspace symbol carries the full
  namespace as its name)."
  [proxy symbol]
  (let [[ns-name var-name] (str/split symbol #"/" 2)
        var-name (if (str/blank? var-name) ns-name var-name)]
    (when (str/blank? ns-name)
      (throw (ex-info (str "expected ns/name or ns, got " (pr-str symbol)) {:symbol symbol})))
    (let [candidates (request! proxy "workspace/symbol" {"query" var-name})
          uris (into [] (comp (filter #(= var-name (get % "name")))
                              (map #(get-in % ["location" "uri"]))
                              (distinct))
                     candidates)
          trees (into {} (map (fn [uri] [uri (request! proxy "textDocument/documentSymbol" {"textDocument" {"uri" uri}})])) uris)
          in-ns (filterv #(declares-namespace? (get trees %) ns-name) uris)]
      (case (count in-ns)
        0 (throw (ex-info (str "no definition of " symbol " found") {:symbol symbol}))
        1 (let [uri (first in-ns)
                position (symbol-position (get trees uri) var-name)]
            (when-not position
              (throw (ex-info (str "no document symbol named " var-name " in " uri) {:symbol symbol})))
            {:uri uri :position position})
        (throw (ex-info (str symbol " is defined in more than one file: " (str/join ", " in-ns))
                        {:symbol symbol :uris in-ns}))))))

;;; the workspace edit

(defn- file-operation? [change]
  (contains? change "kind"))

(defn- document-changes
  "The edit's operations as a `documentChanges` list, whatever shape the
  server used."
  [edit]
  (or (get edit "documentChanges")
      (mapv (fn [[uri edits]] {"textDocument" {"uri" uri} "edits" edits})
            (get edit "changes"))))

(defn- with-rename-text-edits
  "For each file rename in `edit`, adds the text edits clojure-lsp makes
  through `workspace/willRenameFiles` (the `ns` form and every require of
  the namespace), placed before the renames so that they apply to the old
  paths."
  [proxy edit]
  (let [operations (document-changes edit)
        renames (filter #(= "rename" (get % "kind")) operations)]
    (if (empty? renames)
      {"documentChanges" operations}
      (let [extra (request! proxy "workspace/willRenameFiles"
                            {"files" (mapv #(select-keys % ["oldUri" "newUri"]) renames)})]
        {"documentChanges" (into (vec (document-changes extra)) operations)}))))

(defn summarize [edit]
  (let [operations (document-changes edit)
        text-edits (remove file-operation? operations)]
    {:files (count (distinct (map #(get-in % ["textDocument" "uri"]) text-edits)))
     :edits (reduce + (map #(count (get % "edits")) text-edits))
     :renamed_files (count (filter #(= "rename" (get % "kind")) operations))}))

;;; applying

(defn- line-offsets
  "Start offset of every line of `text`, lines ending in \\n."
  [text]
  (reduce (fn [offsets index] (conj offsets (inc index)))
          [0]
          (keep-indexed (fn [index c] (when (= \newline c) index)) text)))

(defn- offset
  "The string offset of an LSP position in `text`, checked against the
  line count and the line's length."
  [text offsets {:strs [line character] :as position}]
  (when-not (and (int? line) (int? character) (<= 0 line) (< line (count offsets)) (<= 0 character))
    (throw (ex-info (str "position out of range: " (pr-str position)) {:position position})))
  (let [start (nth offsets line)
        line-end (if-let [next-start (get offsets (inc line))] (dec next-start) (count text))]
    (when (> (+ start character) line-end)
      (throw (ex-info (str "position past the end of its line: " (pr-str position)) {:position position})))
    (+ start character)))

(defn apply-text-edits
  "`text` with the LSP `edits` applied, from the last position backwards
  so that earlier offsets stay valid. Positions count UTF-16 units, as
  Java strings do. Throws before changing anything when a position lies
  outside the text."
  [text edits]
  (let [offsets (line-offsets text)
        spans (mapv (fn [edit]
                      [(offset text offsets (get-in edit ["range" "start"]))
                       (offset text offsets (get-in edit ["range" "end"]))
                       (get edit "newText")])
                    edits)]
    (reduce (fn [text [from to new-text]]
              (str (subs text 0 from) new-text (subs text to)))
            text
            (sort-by (comp - first) spans))))

(defn- check-file-operations!
  "Fails before anything is written when a rename or create would
  overwrite an existing file."
  [operations]
  (doseq [{:strs [kind newUri uri options]} operations
          :when (and (#{"rename" "create"} kind) (not (get options "overwrite")))
          :let [target (uri->path (or newUri uri))]
          :when (fs/exists? target)]
    (throw (ex-info (str kind " would overwrite " target) {:path target}))))

(defn- planned-text
  "For a text document edit, the path and the text it will hold; reading
  and checking happen here, before any write."
  [{:strs [textDocument edits]}]
  (let [path (uri->path (get textDocument "uri"))]
    {:path path :text (apply-text-edits (slurp path) edits)}))

(defn- apply-operation! [{:strs [kind textDocument edits uri oldUri newUri] :as operation}]
  (case kind
    nil (let [{:keys [path text]} (:planned operation)]
          (spit path text)
          [path])
    "rename" (let [from (uri->path oldUri)
                   to (uri->path newUri)]
               (fs/create-dirs (fs/parent to))
               (fs/move from to)
               [from to])
    "create" (let [path (uri->path uri)]
               (fs/create-dirs (fs/parent path))
               (spit path "")
               [path])
    "delete" (let [path (uri->path uri)]
               (fs/delete-if-exists path)
               [path])))

(defn apply-edit!
  "Applies the edit's operations in order, adding each touched path to
  the `touched` atom as it goes. Every text edit is computed and every
  file operation checked before the first write."
  [edit touched]
  (let [operations (mapv (fn [operation]
                           (cond-> operation
                             (not (file-operation? operation)) (assoc :planned (planned-text operation))))
                         (document-changes edit))]
    (check-file-operations! operations)
    (doseq [operation operations]
      (swap! touched into (apply-operation! operation)))))

;;; the operation

(defn rename!
  "The `rename` control operation: `file`, `line` and `character`
  (zero-based) or `symbol` (`ns/name`), `new_name`, and `apply`."
  [{:keys [project-root send-deadline-ms state] :as proxy} {:strs [file line character symbol new_name apply]}]
  (when (str/blank? new_name)
    (throw (ex-info "new_name is required" {})))
  ;; symbols and edits computed against an analysis behind a reported change
  ;; would be wrong; an open gate is not enough (see gate/await-settled), and
  ;; changes a dropped send left unconfirmed get one more send first
  (gate/resend-unconfirmed! proxy)
  (when-not (gate/await-settled proxy (* 2 send-deadline-ms))
    (throw (ex-info "the analysis of reported changes is not confirmed (still running, or none arrived within the deadline); retry once `status` shows settled" {})))
  (let [{:keys [uri position]} (if symbol
                                 (resolve-symbol proxy symbol)
                                 {:uri (changes/path->uri (changes/real-path file))
                                  :position {"line" line "character" character}})
        edit (with-rename-text-edits
              proxy (request! proxy "textDocument/rename"
                              {"textDocument" {"uri" uri} "position" position "newName" new_name}))
        summary (summarize edit)]
    (if apply
      (let [touched (atom [])
            report! (fn []
                      (let [paths (distinct @touched)
                            report (changes/report-for-paths project-root paths)]
                        (when (seq paths)
                          ;; the Bash bracket around the command must not report them again
                          (swap! state update :self-reported merge (zipmap paths (map detect/reported-state paths)))
                          (transport/log-event! proxy "rename-applied" :touched (count paths) :reported (count report))
                          (gate/report-changes! proxy report))
                        paths))]
        (try
          (apply-edit! edit touched)
          (catch Exception e
            (report!)
            (throw e)))
        (let [paths (report!)
              settled? (gate/await-settled proxy (* 2 send-deadline-ms))]
          {:ok true :edit edit :summary summary :applied true :touched paths :timed_out (not settled?)}))
      {:ok true :edit edit :summary summary :applied false})))

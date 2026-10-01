(ns clojure-lsp-proxy.rename
  "Rename through the running clojure-lsp (contract C7).

  The proxy asks clojure-lsp for the rename (`textDocument/rename`) and,
  when the answer renames a file (a namespace rename), for the text edits
  that go with it (`workspace/willRenameFiles`, as an editor would ask
  before moving the file). Without `apply` the combined WorkspaceEdit is
  returned untouched; with `apply` it goes through `clojure-lsp-proxy.edit`.

  A var or a namespace can be named instead of a position: `ns/name` for a
  var, a bare `ns` for the namespace itself. The candidates come from
  `workspace/symbol`; each candidate file's `textDocument/documentSymbol`
  tree says which namespace it declares and where the name sits. Keywords
  and locals have no workspace symbol and take the position form.

  Before asking for the rename, the proxy asks `clojure/cursorInfo/raw`
  what sits at the position and refuses the key of a destructuring map
  (`:keys`, `:syms`, `:strs`, qualified or not), which clj-kondo marks
  `keys-destructuring-ns-modifier`: renaming one produces a single edit
  that breaks the destructuring instead of renaming anything. The same
  spelling used as plain data carries no marker and is renamed."
  (:require [clojure.string :as str]
            [clojure-lsp-proxy.changes :as changes]
            [clojure-lsp-proxy.edit :as edit]
            [clojure-lsp-proxy.transport :as transport]))

(def ^:private position-form-hint
  "keywords and locals have no workspace symbol; use the position form: rename <file> <line> <col> <new-name>")

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
  (when (str/starts-with? symbol ":")
    (throw (ex-info (str "--symbol resolves vars and namespaces, not the keyword " symbol "; " position-form-hint)
                    {:symbol symbol})))
  (let [[ns-name var-name] (str/split symbol #"/" 2)
        var-name (if (str/blank? var-name) ns-name var-name)]
    (when (str/blank? ns-name)
      (throw (ex-info (str "expected ns/name or ns, got " (pr-str symbol)) {:symbol symbol})))
    (let [candidates (transport/request! proxy "workspace/symbol" {"query" var-name})
          uris (into [] (comp (filter #(= var-name (get % "name")))
                              (map #(get-in % ["location" "uri"]))
                              (distinct))
                     candidates)
          trees (into {} (map (fn [uri] [uri (transport/request! proxy "textDocument/documentSymbol" {"textDocument" {"uri" uri}})])) uris)
          in-ns (filterv #(declares-namespace? (get trees %) ns-name) uris)]
      (case (count in-ns)
        0 (throw (ex-info (str "no definition of " symbol " found; " position-form-hint) {:symbol symbol}))
        1 (let [uri (first in-ns)
                position (symbol-position (get trees uri) var-name)]
            (when-not position
              (throw (ex-info (str "no document symbol named " var-name " in " uri) {:symbol symbol})))
            {:uri uri :position position})
        (throw (ex-info (str symbol " is defined in more than one file: " (str/join ", " in-ns))
                        {:symbol symbol :uris in-ns}))))))

;;; what sits at the position

(defn- check-renameable!
  "Asks clojure-lsp for the elements at the position and refuses the key
  of a destructuring map, which clj-kondo marks
  `keys-destructuring-ns-modifier`."
  [proxy uri position]
  (let [info (transport/request! proxy "clojure/cursorInfo/raw"
                                 {"textDocument" {"uri" uri} "position" position})]
    (when-let [{:strs [ns name]} (some #(let [element (get % "element")]
                                          (when (get element "keys-destructuring-ns-modifier") element))
                                       (get info "elements"))]
      (throw (ex-info (str ":" (when ns (str ns "/")) name
                           " is a destructuring key, not a keyword to rename; aim at an occurrence of the keyword itself")
                      {:ns ns :name name})))))

;;; the workspace edit

(defn- with-rename-text-edits
  "For each file rename in `edit`, adds the text edits clojure-lsp makes
  through `workspace/willRenameFiles` (the `ns` form and every require of
  the namespace), placed before the renames so that they apply to the old
  paths."
  [proxy workspace-edit]
  (let [operations (edit/document-changes workspace-edit)
        renames (filter #(= "rename" (get % "kind")) operations)]
    (if (empty? renames)
      {"documentChanges" operations}
      (let [extra (transport/request! proxy "workspace/willRenameFiles"
                                      {"files" (mapv #(select-keys % ["oldUri" "newUri"]) renames)})]
        {"documentChanges" (into (vec (edit/document-changes extra)) operations)}))))

;;; the operation

(defn rename!
  "The `rename` control operation: `file`, `line` and `character`
  (zero-based) or `symbol` (`ns/name` or `ns`), `new_name`, and `apply`."
  [proxy {:strs [file line character symbol new_name apply]}]
  (when (str/blank? new_name)
    (throw (ex-info "new_name is required" {})))
  (edit/await-fresh! proxy)
  (let [{:keys [uri position]} (if symbol
                                 (resolve-symbol proxy symbol)
                                 {:uri (changes/path->uri (changes/real-path file))
                                  :position {"line" line "character" character}})
        _ (check-renameable! proxy uri position)
        workspace-edit (with-rename-text-edits
                        proxy (transport/request! proxy "textDocument/rename"
                                                  {"textDocument" {"uri" uri} "position" position "newName" new_name}))
        summary (edit/summarize workspace-edit)]
    (if apply
      (merge {:ok true :edit workspace-edit :summary summary :applied true}
             (edit/apply-and-report! proxy workspace-edit "rename-applied"))
      {:ok true :edit workspace-edit :summary summary :applied false})))

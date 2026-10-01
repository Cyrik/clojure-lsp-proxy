(ns clojure-lsp-proxy.edit
  "Applying a workspace edit that clojure-lsp proposed (contracts C7 and
  C8): text edits per file from the last position backwards, then the
  file operations. Every text edit is computed and every file operation
  checked before the first write; a failure while writing leaves the
  files written so far in place. The touched paths are reported through
  the gate so that clojure-lsp re-reads them, remembered as the proxy's
  own writes so that the Bash bracket around the command does not report
  them again, and the reply waits for the analysis.

  The operations that produce edits (rename, format) first wait for every
  reported change to be analyzed (`await-fresh!`): positions and text
  computed against an analysis behind a reported change would be wrong."
  (:require [babashka.fs :as fs]
            [clojure-lsp-proxy.changes :as changes]
            [clojure-lsp-proxy.detect :as detect]
            [clojure-lsp-proxy.gate :as gate]
            [clojure-lsp-proxy.transport :as transport])
  (:import [java.net URI]))

(defn uri->path [uri]
  (.getPath (URI. uri)))

;;; the workspace edit

(defn file-operation? [change]
  (contains? change "kind"))

(defn document-changes
  "The edit's operations as a `documentChanges` list, whatever shape the
  server used."
  [edit]
  (or (get edit "documentChanges")
      (mapv (fn [[uri edits]] {"textDocument" {"uri" uri} "edits" edits})
            (get edit "changes"))))

(defn summarize [edit]
  (let [operations (document-changes edit)
        text-edits (remove file-operation? operations)]
    {:files (count (distinct (map #(get-in % ["textDocument" "uri"]) text-edits)))
     :edits (reduce + (map #(count (get % "edits")) text-edits))
     :renamed_files (count (filter #(= "rename" (get % "kind")) operations))}))

;;; text and positions

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

(defn clamp-edits
  "`edits` with every position past the end of `text` moved to the end of
  the text, or of its line. Formatting answers replace the whole document
  with an end position far beyond it (clojure-lsp uses line 999999);
  rename edits never need this and keep the strict check."
  [text edits]
  (let [offsets (line-offsets text)
        last-line (dec (count offsets))
        line-length (fn [line]
                      (- (if-let [next-start (get offsets (inc line))] (dec next-start) (count text))
                         (nth offsets line)))
        clamp (fn [{:strs [line character] :as position}]
                (if (and (int? line) (int? character))
                  (let [line (min line last-line)]
                    {"line" line "character" (min character (line-length line))})
                  position))]
    (mapv #(-> % (update-in ["range" "start"] clamp) (update-in ["range" "end"] clamp)) edits)))

;;; applying

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

;;; freshness and reporting

(defn await-fresh!
  "Waits until every change the proxy reported has been analyzed, after
  re-sending the changes a dropped send left unconfirmed; throws when they
  are not confirmed within two deadlines. An open gate is not enough, see
  `gate/await-settled`."
  [{:keys [send-deadline-ms] :as proxy}]
  (gate/resend-unconfirmed! proxy)
  (when-not (gate/await-settled proxy (* 2 send-deadline-ms))
    (throw (ex-info "the analysis of reported changes is not confirmed (still running, or none arrived within the deadline); retry once `status` shows settled" {}))))

(defn apply-and-report!
  "Applies `edit`, reports every touched path (also after a failure while
  writing) and waits for the analysis; `event` names the log entry.
  Returns `{:touched paths :timed_out bool}`."
  [{:keys [project-root send-deadline-ms state gate-lock] :as proxy} edit event]
  (let [touched (atom [])
        report! (fn []
                  (let [paths (distinct @touched)
                        report (changes/report-for-paths project-root paths)]
                    (when (seq paths)
                      (locking gate-lock
                        ;; the Bash bracket around the command must not report them again
                        (swap! state update :self-reported merge (zipmap paths (map detect/reported-state paths)))
                        (transport/log-event! proxy event :touched (count paths) :reported (count report))
                        (gate/report-changes! proxy report)))
                    paths))]
    (try
      (apply-edit! edit touched)
      (catch Exception e
        (report!)
        (throw e)))
    (let [paths (report!)
          settled? (gate/await-settled proxy (* 2 send-deadline-ms))]
      {:touched paths :timed_out (not settled?)})))

(ns clojure-lsp-proxy.format
  "Formatting through the running clojure-lsp (contract C8): the file's
  `textDocument/formatting` edits, cljfmt with the project's settings as
  clojure-lsp reads them, applied through the same path as a rename."
  (:require [babashka.fs :as fs]
            [clojure-lsp-proxy.changes :as changes]
            [clojure-lsp-proxy.edit :as edit]
            [clojure-lsp-proxy.transport :as transport]))

(defn format!
  "The `format` control operation: `file` and `apply`. A file that is
  already formatted yields no edits and nothing is written."
  [proxy {:strs [file apply]}]
  (when-not (fs/regular-file? file)
    (throw (ex-info (str "no such file: " file) {:file file})))
  (edit/await-fresh! proxy)
  (let [path (changes/real-path file)
        uri (changes/path->uri path)
        edits (edit/clamp-edits (slurp path)
                                (vec (transport/request! proxy "textDocument/formatting"
                                                         {"textDocument" {"uri" uri}
                                                          "options" {"tabSize" 2 "insertSpaces" true}})))
        workspace-edit {"documentChanges" [{"textDocument" {"uri" uri "version" nil} "edits" edits}]}
        summary (edit/summarize workspace-edit)]
    (cond
      (not apply) {:ok true :edit workspace-edit :summary summary :applied false}
      (empty? edits) {:ok true :edit workspace-edit :summary summary :applied true :touched [] :timed_out false}
      :else (merge {:ok true :edit workspace-edit :summary summary :applied true}
                   (edit/apply-and-report! proxy workspace-edit "format-applied")))))

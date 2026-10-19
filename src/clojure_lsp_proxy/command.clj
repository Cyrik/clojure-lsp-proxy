(ns clojure-lsp-proxy.command
  "clojure-lsp refactorings run through the proxy: `clean-ns` and every
  other command clojure-lsp lists in `executeCommandProvider`.

  clojure-lsp runs a refactoring as `workspace/executeCommand` with the
  arguments `[uri line character & args]` (zero-based; clean-ns ignores
  the position). It computes a workspace edit, sends it to the client as a
  `workspace/applyEdit` request, waits up to ten seconds for the answer
  and then returns the same edit as the command's result. An empty result
  (null) means the command found nothing to do, for clean-ns a file
  without an `ns` form; a failure comes back as an error response.

  The proxy applies the edit itself, from the command's result, through
  `clojure-lsp-proxy.edit` like a rename: dry run, checks before the first
  write, reporting through the gate and waiting for the analysis. The
  `workspace/applyEdit` that clojure-lsp sends while the proxy's own
  command is in flight is therefore answered by the proxy with
  `applied: false` and never forwarded: Claude Code does not answer that
  request, so forwarding it would only stall the command for ten seconds,
  and answering `applied: true` would claim a write that has not happened.
  When no own command is in flight (clojure-lsp inserting an `ns` form
  into a new blank file, say), the request crosses to the client
  unchanged. Commands are run one at a time per proxy so that a consumed
  request belongs to the one command in flight."
  (:require [babashka.fs :as fs]
            [clojure-lsp-proxy.changes :as changes]
            [clojure-lsp-proxy.edit :as edit]
            [clojure-lsp-proxy.transport :as transport]))

(def apply-edit-failure-reason "clojure-lsp-proxy applies the command result")

(defn with-consumed-edit
  "`state` with `edit` appended to the in-flight command's consumed edits,
  or `state` unchanged when no own command is in flight. Never creates
  `:own-command`: the command may end (its request timing out) while an
  applyEdit is being handled, and a recreated marker would make every
  later applyEdit look like the proxy's own."
  [state edit]
  (if (contains? state :own-command)
    (update-in state [:own-command :consumed-edits] conj edit)
    state))

(defn consume-apply-edit!
  "Answers a server `workspace/applyEdit` request with `applied: false`
  while one of the proxy's own commands is in flight, remembering its edit
  for the comparison with the command's result. Whether a command is in
  flight is decided in the same atomic step that records the edit. Returns
  true when the message was consumed, false when the caller should forward
  it."
  [{:keys [state] :as proxy} msg]
  (if (= "workspace/applyEdit" (get msg "method"))
    (let [[before] (swap-vals! state with-consumed-edit (get-in msg ["params" "edit"]))]
      (if-let [in-flight (:own-command before)]
        (do (transport/log-message! proxy "s->p" msg)
            (transport/log-event! proxy "apply-edit-consumed" :command (:command in-flight))
            (transport/send-own-message-to-server!
             proxy {"jsonrpc" "2.0" "id" (get msg "id")
                    "result" {"applied" false "failureReason" apply-edit-failure-reason}})
            true)
        false))
    false))

(defn- execute!
  "The edit `command` proposes for `arguments`, with the server's own
  `workspace/applyEdit` consumed meanwhile (see the namespace docstring).
  An applyEdit whose edit differs from the result is logged as an anomaly:
  it was some other edit, now answered `applied: false`."
  [{:keys [state command-lock] :as proxy} command arguments]
  (locking command-lock
    (swap! state assoc :own-command {:command command :consumed-edits []})
    (try
      (let [result (transport/request! proxy "workspace/executeCommand" {"command" command "arguments" arguments})]
        (doseq [consumed (get-in @state [:own-command :consumed-edits])
                :when (not= consumed result)]
          (transport/log-event! proxy "anomaly" :kind "consumed-edit-differs-from-result" :command command))
        result)
      (finally
        (swap! state dissoc :own-command)))))

(defn refactor!
  "The `refactor` control operation: `command` (a clojure-lsp command name
  such as \"clean-ns\"), `file`, zero-based `line` and `character`
  (default 0), extra `args` appended after the position, and `apply`.
  Waits for every reported change to be analyzed first, since commands
  like clean-ns read the analysis. Without `apply` the proposed edit is
  returned; with it the edit is applied and reported, and a file whose
  planned text equals its current text is left alone. Throws when the
  file does not exist, when clojure-lsp answers with an error, or when it
  proposes no edit at all."
  [proxy {:strs [command file line character args apply]}]
  (when-not (fs/regular-file? file)
    (throw (ex-info (str "no such file: " file) {:file file})))
  (edit/await-fresh! proxy)
  (let [uri (changes/path->uri (changes/real-path file))
        workspace-edit (execute! proxy command (into [uri (or line 0) (or character 0)] args))
        _ (when-not workspace-edit
            (throw (ex-info (str command " proposed no edit for " file
                                 (when (= "clean-ns" command) " (does it have an ns form?)"))
                            {:command command :file file})))
        summary (edit/summarize workspace-edit)]
    (if apply
      (merge {:ok true :edit workspace-edit :summary summary :applied true}
             (edit/apply-and-report! proxy workspace-edit (str command "-applied")))
      {:ok true :edit workspace-edit :summary summary :applied false})))

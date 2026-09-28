#!/usr/bin/env bb
;; A stand-in LSP server for the proxy tests. Every request is answered with
;; an echo of what arrived; `exit` ends the process, `fake/crash` ends it
;; with status 3, `fake/hang` makes it ignore `shutdown` and `exit`, and
;; `fake/notify` makes it send a `fake/hello` notification. Order of arrival
;; is available through the `fake/received` request. Prints one line to
;; stderr on start so that the stderr pump can be observed.
;;
;; Work-done progress, emulating the clojure-lsp fork: `fake/configure`
;; with `{"progress": {"create_after_ms": N, "end_after_ms": N or null,
;; "title": T}}` (or a list of such maps, each run) makes every
;; `workspace/didChangeWatchedFiles` start a progress sequence: a `window/workDoneProgress/create` request after the
;; first delay, `begin` with the title once the client answered, `end`
;; after the second delay (never, when null). `fake/progress` with the same
;; fields runs one sequence on its own; `fake/client-progress` sends a
;; `$/progress` on a token the client chose, without a create.
;;
;; Rename: `fake/configure` may also carry `"replies"`, a map of method
;; name to the result the fake answers for that method (`workspace/symbol`,
;; `textDocument/documentSymbol`, `textDocument/rename`,
;; `workspace/willRenameFiles`); a reply `{"error": {...}}` is sent as an
;; error response, and a reply `{"by_uri": {uri: result}}` answers per
;; `params.textDocument.uri`.
(require '[babashka.classpath :refer [add-classpath]]
         '[babashka.fs :as fs])
(add-classpath (str (fs/path (fs/parent (fs/parent (fs/canonicalize *file*))) "src")))
(require '[clojure-lsp-proxy.framing :as framing])

(def received (atom []))
(def hanging (atom false))
(def progress-config (atom nil))
(def replies (atom {}))
(def pending-requests (atom {}))
(def request-counter (atom 0))
(def out System/out)
(def out-lock (Object.))

(defn send! [message]
  (locking out-lock
    (framing/write-message out (framing/encode message))))

(defn reply! [id result]
  (send! {"jsonrpc" "2.0" "id" id "result" result}))

(defn request!
  "Sends a request to the client; returns a promise of its response."
  [method params]
  (let [id (str "fake-" (swap! request-counter inc))
        response (promise)]
    (swap! pending-requests assoc id response)
    (send! {"jsonrpc" "2.0" "id" id "method" method "params" params})
    response))

(defn progress! [token value]
  (send! {"jsonrpc" "2.0" "method" "$/progress" "params" {"token" token "value" value}}))

(defn run-progress! [{:strs [create_after_ms end_after_ms title]}]
  (future
    (Thread/sleep (or create_after_ms 0))
    (let [token (str (random-uuid))
          response (request! "window/workDoneProgress/create" {"token" token})]
      (deref response 10000 nil)
      (progress! token {"kind" "begin" "title" title "percentage" 0})
      (when end_after_ms
        (Thread/sleep end_after_ms)
        (progress! token {"kind" "end"})))))

(binding [*out* *err*]
  (println "fake server started"))

(loop []
  (when-let [{:keys [body]} (framing/read-message System/in)]
    (let [msg (framing/parse body)
          method (get msg "method")
          id (get msg "id")]
      (if (nil? method)
        (do (swap! received conj {"response" id})
            (some-> (get @pending-requests id) (deliver msg)))
        (do
          (swap! received conj (cond-> {"method" method "id" id}
                                 (= "workspace/didChangeWatchedFiles" method)
                                 (assoc "changes" (get-in msg ["params" "changes"]))))
          (case method
            "initialize" (reply! id {"capabilities" {}
                                     "serverInfo" {"name" "fake-server"
                                                   "pid" (.pid (java.lang.ProcessHandle/current))}
                                     "clientCapabilities" (get-in msg ["params" "capabilities"])})
            "shutdown" (when-not @hanging (reply! id nil))
            "exit" (when-not @hanging (System/exit 0))
            "fake/crash" (System/exit 3)
            "fake/hang" (reset! hanging true)
            "fake/notify" (send! {"jsonrpc" "2.0" "method" "fake/hello" "params" (get msg "params")})
            "fake/received" (reply! id @received)
            "fake/configure" (do (when (contains? (get msg "params") "progress")
                                   (reset! progress-config (get-in msg ["params" "progress"])))
                                 (swap! replies merge (get-in msg ["params" "replies"])))
            "fake/progress" (run-progress! (get msg "params"))
            "fake/client-progress" (progress! (get-in msg ["params" "token"]) (get-in msg ["params" "value"]))
            "workspace/didChangeWatchedFiles" (doseq [config (let [c @progress-config] (if (map? c) [c] c))]
                                                 (run-progress! config))
            (when id
              (if-let [[_ canned] (find @replies method)]
                (cond
                  (and (map? canned) (get canned "error"))
                  (send! {"jsonrpc" "2.0" "id" id "error" (get canned "error")})

                  (and (map? canned) (contains? canned "by_uri"))
                  (reply! id (get-in canned ["by_uri" (get-in msg ["params" "textDocument" "uri"])]))

                  :else (reply! id canned))
                (reply! id {"echo" method "params" (get msg "params")}))))))
      (recur))))

;; stdin closed without exit: a hanging server stays until it is killed
(when @hanging
  (Thread/sleep 60000))
(System/exit 0)

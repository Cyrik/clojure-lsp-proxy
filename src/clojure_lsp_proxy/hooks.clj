(ns clojure-lsp-proxy.hooks
  "The Claude Code hooks that bracket every Bash command (contract C6):
  `start` (PreToolUse) sends `bash-started`, `end` (PostToolUse and
  PostToolUseFailure) sends `bash-finished` with the `bashEditDiff` Claude
  Code recorded, to every proxy of the project. The hook input JSON comes
  on stdin. A hook never fails Claude: it prints nothing on stdout, exits
  0 whatever happens and gives up on a proxy after 5 s; problems go to
  stderr."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure-lsp-proxy.client :as client]))

(def timeout-ms 5000)

(defn- project-root []
  (str (fs/canonicalize (or (System/getenv "CLAUDE_PROJECT_DIR") (fs/cwd)))))

(defn request-for
  "The socket request for hook `event` (\"start\" or \"end\") and the hook
  `input` map."
  [event input]
  (case event
    "start" {:op "bash-started"
             :tool_use_id (get input "tool_use_id")}
    "end" {:op "bash-finished"
           :tool_use_id (get input "tool_use_id")
           :bash_edit_diff (get-in input ["tool_response" "bashEditDiff"])}))

(defn- request-all!
  "Asks every proxy at once and gives up on all of them together after
  `timeout-ms`, so that several stuck proxies cannot add up."
  [root request]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        pending (mapv (fn [socket] [socket (future (client/request! socket request timeout-ms))])
                      (client/sockets root))]
    (mapv (fn [[socket reply]]
            {:socket socket
             :reply (let [left (max 0 (- deadline (System/currentTimeMillis)))
                          result (deref reply left ::timeout)]
                      (when (not= ::timeout result) result))})
          pending)))

(defn -main [& [event]]
  (try
    (let [input (json/parse-string (slurp *in*))
          results (request-all! (project-root) (request-for event input))]
      (doseq [{:keys [socket reply]} results
              :when (not (get reply "ok"))]
        (binding [*out* *err*]
          (println "clojure-lsp-proxy hook:" socket (if reply (get reply "error") "no answer")))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "clojure-lsp-proxy hook failed:" (str t)))))
  (System/exit 0))

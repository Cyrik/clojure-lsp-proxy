(ns clojure-lsp-proxy.main
  "Stdio proxy between an LSP client and clojure-lsp.

  Claude Code starts this process in place of clojure-lsp. It spawns the
  real server (`CLOJURE_LSP_PROXY_SERVER`, default `clojure-lsp` on PATH,
  with the proxy's own arguments), pumps framed messages in both directions
  and logs every message. Stdout carries protocol bytes only; the proxy's
  own diagnostics go to stderr.

  On top of forwarding, the proxy owns the server's work-done progress,
  injects `workspace/didChangeWatchedFiles` for changes reported over its
  control socket and holds the client's messages until the server has
  analyzed them (see `clojure-lsp-proxy.gate`).

  Exit rules: after the client's `exit` was forwarded, or after the client
  closed stdin, the proxy shuts the server down if needed and exits 0. When
  the server dies otherwise, held requests are answered with an error and
  the proxy exits 1 so that the client restarts both. Claude Code sends
  `shutdown` and then terminates the proxy with SIGTERM instead of sending
  `exit`; a shutdown hook covers that path, and the server ends on its own
  when its stdin closes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure-lsp-proxy.control :as control]
            [clojure-lsp-proxy.detect :as detect]
            [clojure-lsp-proxy.framing :as framing]
            [clojure-lsp-proxy.gate :as gate]
            [clojure-lsp-proxy.log :as log]
            [clojure-lsp-proxy.project :as project]
            [clojure-lsp-proxy.transport :as transport :refer [log-event! log-message! warn!]])
  (:import [java.io InputStream]
           [java.util.concurrent TimeUnit]))

(def shutdown-response-timeout-ms 3000)
(def server-exit-timeout-ms 5000)
(def default-send-deadline-ms 30000)

(defn- server-alive? [{:keys [server]}]
  (.isAlive ^Process (:proc server)))

(defn- shut-server-down!
  "Sends `shutdown` and `exit` for a client that left without them, then
  waits for the server, killing it when it outlives the timeout. The server
  reader sees the closed pipe afterwards and delivers the exit code."
  [{:keys [state server] :as proxy}]
  (when (and (not (:exit-forwarded? @state)) (server-alive? proxy))
    (let [response (transport/request-server! proxy "shutdown" nil)]
      (when-not (deref response shutdown-response-timeout-ms nil)
        (log-event! proxy "shutdown-response-timeout"))
      (swap! state assoc :exit-forwarded? true)
      (transport/send-own-message-to-server! proxy {"jsonrpc" "2.0" "method" "exit"})))
  (when-not (.waitFor ^Process (:proc server) server-exit-timeout-ms TimeUnit/MILLISECONDS)
    (log-event! proxy "server-killed")
    (.destroyForcibly ^Process (:proc server))))

(defn- client-closed! [{:keys [state] :as proxy}]
  (swap! state assoc :client-eof? true)
  (log-event! proxy "client-eof")
  (shut-server-down! proxy))

(defn- patch-initialize
  "The proxy owns work-done progress (C2), so the server must see a client
  that supports it whatever Claude Code advertised. It also applies
  workspace edits itself (C7), so the server may answer a rename with
  `documentChanges`, file renames included; Claude Code never asks the
  server for edits, so it never sees that shape."
  [msg]
  (-> msg
      (assoc-in ["params" "capabilities" "window" "workDoneProgress"] true)
      (update-in ["params" "capabilities" "workspace" "workspaceEdit"]
                 merge {"documentChanges" true
                        "resourceOperations" ["create" "rename" "delete"]})))

(defn- client-loop [{:keys [^InputStream client-in state] :as proxy}]
  (loop []
    (if-let [{:keys [body]} (framing/read-message client-in)]
      (let [msg (framing/parse body)]
        (case (get msg "method")
          "initialize" (let [patched (patch-initialize msg)]
                         (log-event! proxy "initialize-patched"
                                     :client-capabilities (get-in msg ["params" "capabilities"]))
                         (gate/handle-client-message! proxy (framing/encode patched) patched))
          "exit" (do (swap! state assoc :exit-forwarded? true)
                     (gate/handle-client-message! proxy body msg))
          (gate/handle-client-message! proxy body msg))
        (recur))
      (client-closed! proxy))))

(defn- server-closed! [{:keys [state exit server] :as proxy}]
  (let [expected? (boolean (or (:exit-forwarded? @state) (:client-eof? @state)))
        exited? (.waitFor ^Process (:proc server) server-exit-timeout-ms TimeUnit/MILLISECONDS)]
    (when-not exited?
      (log-event! proxy "server-killed")
      (.destroyForcibly ^Process (:proc server)))
    (log-event! proxy "server-exit" :code (when exited? (.exitValue ^Process (:proc server))) :expected expected?)
    (deliver exit (if expected? 0 1))))

(defn- server-loop [{:keys [^InputStream server-out state] :as proxy}]
  (loop []
    (if-let [{:keys [body]} (framing/read-message server-out)]
      (let [msg (framing/parse body)]
        (cond
          (transport/own-response? msg)
          (do (log-message! proxy "s->p" msg)
              (transport/deliver-own-response! proxy msg))

          (gate/handle-server-message! proxy msg)
          nil

          :else
          (do (log-message! proxy "s->c" msg)
              (transport/send-to-client! proxy body)))
        (recur))
      (server-closed! proxy))))

(defn- stderr-loop [{:keys [server-err log]}]
  (with-open [reader (io/reader server-err)]
    (doseq [line (line-seq reader)]
      (log/write! log {:dir "server-stderr" :line line})
      (warn! "server:" line))))

(defn- run-thread!
  "Runs `f` on a named thread. An escaped exception ends the proxy with
  exit code 1; the exit is delivered first so that nothing that follows
  can keep the proxy alive."
  [{:keys [exit] :as proxy} thread-name f]
  (doto (Thread. (fn []
                   (try
                     (f)
                     (catch Throwable t
                       (deliver exit 1)
                       (log-event! proxy "thread-error" :thread thread-name :error (str t))
                       (warn! thread-name "failed:" (str t)))))
                 thread-name)
    (.start)))

(defn- on-termination!
  "Runs on JVM shutdown. Only a signal reaches here before the exit code
  was delivered; the normal paths have already written their exit entry.
  The socket goes in both cases."
  [{:keys [exit log] :as proxy} control]
  (control/stop! control)
  (when-not (realized? exit)
    (log-event! proxy "terminated" :server-alive (server-alive? proxy))
    (log/close! log)))

(defn- log-path [project-root pid]
  (or (System/getenv "CLAUDE_PLUGIN_LSP_LOG_FILE")
      (str (fs/path (project/state-dir project-root) (str pid ".log")))))

(defn- startup-snapshot
  "The snapshot the first Bash check falls back on; nil when git is not
  usable here, which must not stop the proxy."
  [project-root]
  (try
    (detect/snapshot project-root)
    (catch Exception e
      (warn! "no startup snapshot:" (str e))
      nil)))

(defn- send-deadline-ms
  "`CLOJURE_LSP_PROXY_SEND_DEADLINE_MS` when it is a positive integer, else
  the default (with a warning for a value that is set but unusable)."
  []
  (let [setting (System/getenv "CLOJURE_LSP_PROXY_SEND_DEADLINE_MS")
        parsed (some-> setting parse-long)]
    (cond
      (nil? setting) default-send-deadline-ms
      (and parsed (pos? parsed)) parsed
      :else (do (warn! "ignoring CLOJURE_LSP_PROXY_SEND_DEADLINE_MS" (pr-str setting) "; using" default-send-deadline-ms)
                default-send-deadline-ms))))

(defn -main [& server-args]
  (let [project-root (project/root)
        pid (.pid (java.lang.ProcessHandle/current))
        log (log/open (log-path project-root pid))
        command (or (System/getenv "CLOJURE_LSP_PROXY_SERVER") "clojure-lsp")
        server (p/process (into [command] server-args))
        proxy {:log log
               :pid pid
               :project-root project-root
               :server server
               :server-in (:in server)
               :server-out (:out server)
               :server-err (:err server)
               :server-lock (Object.)
               :client-in System/in
               :client-out System/out
               :client-lock (Object.)
               :gate-lock (Object.)
               :own-request-counter (atom 0)
               :send-deadline-ms (send-deadline-ms)
               :state (atom (merge {:exit-forwarded? false
                                    :client-eof? false
                                    ;; Bash brackets, see clojure-lsp-proxy.control
                                    :snapshots {}
                                    :last-snapshot (startup-snapshot project-root)
                                    :self-reported {}
                                    ;; the proxy's own requests, see transport/request-server!
                                    :own-requests {}}
                                   (gate/initial-state)))
               :exit (promise)}]
    (log-event! proxy "start"
                :pid pid
                :server-pid (.pid ^Process (:proc server))
                :command command
                :args (vec server-args)
                :project-root project-root
                :project-dir-env (System/getenv "CLAUDE_PROJECT_DIR")
                :plugin-root (System/getenv "CLAUDE_PLUGIN_ROOT")
                :send-deadline-ms (:send-deadline-ms proxy)
                :log (:path log))
    (let [control (control/start! proxy (control/socket-path project-root pid))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(on-termination! proxy control) "termination"))
      (run-thread! proxy "client->server" #(client-loop proxy))
      (run-thread! proxy "server->client" #(server-loop proxy))
      (run-thread! proxy "server-stderr" #(stderr-loop proxy))
      (let [code @(:exit proxy)]
        (when (server-alive? proxy)
          (log-event! proxy "server-killed")
          (.destroyForcibly ^Process (:proc server)))
        ;; C1: whatever ended the proxy, a held request must not hang the client
        (when (pos? code)
          (let [failed (gate/fail-held! proxy)]
            (when (pos? failed)
              (log-event! proxy "held-requests-failed" :count failed))))
        (control/stop! control)
        (log-event! proxy "exit" :code code)
        (log/close! log)
        (System/exit code)))))

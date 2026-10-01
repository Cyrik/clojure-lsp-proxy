(ns clojure-lsp-proxy.control
  "The control socket (contract C5) and its operations. One JSON object
  per line each way, one request per connection; every connection is
  served on its own thread so that a waiting `changed` does not block the
  hooks."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure-lsp-proxy.changes :as changes]
            [clojure-lsp-proxy.detect :as detect]
            [clojure-lsp-proxy.edit :as edit]
            [clojure-lsp-proxy.format :as fmt]
            [clojure-lsp-proxy.gate :as gate]
            [clojure-lsp-proxy.project :as project]
            [clojure-lsp-proxy.rename :as rename]
            [clojure-lsp-proxy.transport :as transport])
  (:import [java.io BufferedReader IOException InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]))

(defn socket-path [project-root pid]
  (str (fs/path (project/state-dir project-root) (str pid ".sock"))))

(defn- status [{:keys [pid server project-root] :as proxy}]
  (merge {:ok true
          :pid pid
          :server_pid (.pid ^Process (:proc server))
          :project_root project-root}
         (gate/snapshot proxy)))

(defn- changed!
  "Reports `files`; with `wait`, replies once the gate has opened again."
  [{:keys [project-root send-deadline-ms state gate-lock] :as proxy} {:strs [files wait]}]
  (let [report (changes/report-for-paths project-root files)
        {:keys [reported queued]} (locking gate-lock
                                   (when (seq report)
                                     (swap! state assoc :self-reported {}))
                                   (gate/report-changes! proxy report))]
    (if wait
      (let [started (System/currentTimeMillis)
            opened? (gate/await-open proxy (* 2 send-deadline-ms))]
        {:ok true
         :reported reported
         :waited_ms (- (System/currentTimeMillis) started)
         :timed_out (not opened?)})
      {:ok true :reported reported :queued queued})))

;;; Snapshots are keyed by tool_use_id because Bash brackets can overlap.
;;; The shared checkpoint also covers writes between brackets. Its scan,
;;; report and update run under snapshot-lock, before taking gate-lock;
;;; analysis waits never hold snapshot-lock.

(def max-snapshots
  "Brackets whose finish never arrives (an interrupted command) would
  otherwise accumulate."
  32)

(defn- prune-snapshots [snapshots]
  (if (<= (count snapshots) max-snapshots)
    snapshots
    (into {} (take-last max-snapshots (sort-by #(or (:marker-ms (val %)) 0) snapshots)))))

(defn- bracket-key [tool-use-id]
  (or tool-use-id ::anonymous))

(def self-reported-retention-ms
  "How long a path the proxy reported itself stays
  excluded from the checks of brackets that were open at the time."
  600000)

(defn- without-self-reported
  "`paths` minus those the proxy itself reported at or after `marker-ms`
  whose content is still what it reported. Overlapping checks and the
  bracket around an applied rename must not analyze the same write again;
  a later write to the path stays in."
  [self-reported marker-ms paths]
  (if marker-ms
    (remove (fn [path]
              (when-let [reported (get self-reported path)]
                (and (>= (:at reported) marker-ms)
                     (detect/as-reported? reported path))))
            paths)
    paths))

(defn- check-changes!
  "Queues changes since `before` and advances the shared checkpoint only
  after reporting them. Caller holds snapshot-lock; a missing baseline
  starts tracking git when it becomes available."
  [{:keys [state project-root gate-lock] :as proxy} before reported-paths]
  (let [{:keys [paths snapshot]} (if before
                                 (detect/changed-paths before reported-paths)
                                 {:paths (set reported-paths) :snapshot (detect/snapshot project-root)})]
    ;; Native document updates invalidate remembered reports under gate-lock.
    ;; Comparing and installing fingerprints must not straddle an invalidation.
    (locking gate-lock
      (let [paths (without-self-reported (:self-reported @state) (:marker-ms before) paths)
            report (changes/report-for-paths project-root paths)
            reported-states (into {} (map (fn [uri]
                                            (let [path (edit/uri->path uri)]
                                              [path (detect/reported-state path)])))
                                  (keys report))
            result (gate/report-changes! proxy report)
            fresh-enough (- (System/currentTimeMillis) self-reported-retention-ms)]
        (swap! state #(-> %
                          (assoc :last-snapshot snapshot)
                          (update :self-reported merge reported-states)
                          (update :self-reported (fn [m] (into {} (filter (fn [[_ {:keys [at]}]] (> at fresh-enough)) m))))))
        {:snapshot snapshot :candidates (count paths) :result result}))))

(defn- reconcile! [{:keys [state] :as proxy} source]
  (let [{:keys [result] :as check} (check-changes! proxy (:last-snapshot @state) [])]
    (transport/log-event! proxy "disk-check" :source source :reported (:reported result))
    check))

(defn- bash-started! [{:keys [state snapshot-lock] :as proxy} {:strs [tool_use_id]}]
  (locking snapshot-lock
    (let [{:keys [snapshot]} (reconcile! proxy "bash-started")]
      (swap! state update :snapshots #(prune-snapshots (assoc % (bracket-key tool_use_id) snapshot)))
      {:ok true})))

(defn- bash-finished! [{:keys [state snapshot-lock] :as proxy} {:strs [tool_use_id bash_edit_diff]}]
  (locking snapshot-lock
    (let [key (bracket-key tool_use_id)
          own (get-in @state [:snapshots key])
          diff-files (get bash_edit_diff "changedFiles")
          {:keys [result candidates]} (check-changes! proxy (or own (:last-snapshot @state)) diff-files)]
      (swap! state update :snapshots dissoc key)
      (transport/log-event! proxy "bash-check"
                            :tool-use-id tool_use_id
                            :fallback (nil? own)
                            :diff-files (count diff-files)
                            :candidates candidates
                            :reported (:reported result))
      (merge {:ok true} result))))

(defn handle-request [proxy {:strs [op] :as request}]
  (case op
    "status" (status proxy)
    "changed" (changed! proxy request)
    "bash-started" (bash-started! proxy request)
    "bash-finished" (bash-finished! proxy request)
    "rename" (do (locking (:snapshot-lock proxy)
                   (reconcile! proxy "rename"))
                 (rename/rename! proxy request))
    "format" (do (locking (:snapshot-lock proxy)
                   (reconcile! proxy "format"))
                 (fmt/format! proxy request))
    {:ok false :error (str "unknown op: " (pr-str op))}))

(declare serve-request!)

(defn- serve-connection!
  "Serves one request. A peer that leaves before the reply is logged; a
  peer that never sends a line keeps its thread until it disconnects."
  [proxy ^SocketChannel channel]
  (try
    (serve-request! proxy channel)
    (catch Exception e
      (transport/log-event! proxy "control-connection-failed" :error (str e)))))

(defn- serve-request! [proxy ^SocketChannel channel]
  (with-open [channel channel]
    (let [reader (BufferedReader. (InputStreamReader. (Channels/newInputStream channel) "UTF-8"))
          writer (OutputStreamWriter. (Channels/newOutputStream channel) "UTF-8")
          line (.readLine reader)
          request (try (some-> line json/parse-string)
                       (catch Exception _ ::unparsable))
          reply (cond
                  (nil? request) {:ok false :error "empty request"}
                  (= ::unparsable request) {:ok false :error "request is not a JSON object"}
                  :else (try
                          (handle-request proxy request)
                          (catch Exception e
                            {:ok false :error (str e)})))]
      (transport/log-event! proxy "control" :op (when (map? request) (get request "op")) :reply reply)
      (.write writer (json/generate-string reply))
      (.write writer "\n")
      (.flush writer))))

(defn start!
  "Binds the socket at `path` and serves connections until `stop!`."
  [proxy path]
  (fs/create-dirs (fs/parent path))
  (fs/delete-if-exists path)
  (let [server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of path)))]
    (doto (Thread. (fn []
                     (try
                       (loop []
                         (let [channel (.accept server)]
                           (.start (Thread. #(serve-connection! proxy channel) "control-connection"))
                           (recur)))
                       ;; accept fails with a closed-channel exception after stop!;
                       ;; bb lacks that class, so the open state tells the cases apart
                       (catch IOException e
                         (when (.isOpen server)
                           (throw e)))))
                   "control-accept")
      (.start))
    (transport/log-event! proxy "control-listening" :socket path)
    {:path path :server server}))

(defn stop! [{:keys [^ServerSocketChannel server path]}]
  (.close server)
  (fs/delete-if-exists path))

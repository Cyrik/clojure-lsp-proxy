#!/usr/bin/env bb

(ns gate-test
  "End-to-end tests of progress ownership, change injection and gating:
  the proxy runs as a subprocess in front of test/fake_server.bb, the test
  plays the client on its stdio and reports changes over the control
  socket."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-lsp-proxy.client :as client]
            [clojure-lsp-proxy.control :as control]
            [clojure-lsp-proxy.framing :as framing]
            [clojure-lsp-proxy.project :as project])
  (:import [java.util.concurrent TimeUnit]))

(def root (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(def launcher (str (fs/path root "bin" "clojure-lsp-proxy-server")))
(def fake-server (str (fs/path root "test" "fake_server.bb")))
(def analysis-title "Analyzing external file changes")

(defn now [] (System/currentTimeMillis))

(defn git! [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(defn start-proxy
  "A proxy over the fake server for a fresh temporary project, a git
  repository holding committed src/a.clj and src/b.clj. Waits until the
  control socket exists."
  ([] (start-proxy {}))
  ([{:keys [deadline-ms] :or {deadline-ms 30000}}]
   (let [project (str (fs/canonicalize (fs/create-temp-dir {:prefix "clojure-lsp-proxy-gate"})))
         log (str (fs/path project "proxy.log"))]
     (fs/create-dirs (fs/path project "src"))
     (spit (str (fs/path project "src" "a.clj")) "(ns a)\n")
     (spit (str (fs/path project "src" "b.clj")) "(ns b)\n")
     (git! project "init" "-q" "--initial-branch=main")
     (git! project "add" "-A")
     (git! project "commit" "-q" "-m" "base")
     (let [proc (p/process [launcher]
                           {:extra-env {"CLOJURE_LSP_PROXY_SERVER" fake-server
                                        "CLAUDE_PLUGIN_LSP_LOG_FILE" log
                                        "CLAUDE_PROJECT_DIR" project
                                        "CLOJURE_LSP_PROXY_SEND_DEADLINE_MS" (str deadline-ms)}
                            :err :string})
           socket (control/socket-path project (.pid ^Process (:proc proc)))]
       (loop [waited 0]
         (when (and (not (fs/exists? socket)) (< waited 5000))
           (Thread/sleep 20)
           (recur (+ waited 20))))
       {:proc proc :log log :project project :socket socket}))))

(defn send! [{:keys [proc]} message]
  (framing/write-message (:in proc) (framing/encode message)))

(defn recv!
  "The next server-to-client message, or ::timeout after `timeout-ms`."
  ([proxy] (recv! proxy 10000))
  ([{:keys [proc]} timeout-ms]
   (deref (future (some-> (framing/read-message (:out proc)) :body framing/parse)) timeout-ms ::timeout)))

(defn recv-response!
  "Skips messages until the response with `id` arrives."
  [proxy id]
  (loop []
    (let [m (recv! proxy)]
      (if (or (= ::timeout m) (= id (get m "id")))
        m
        (recur)))))

(defn initialize!
  "Runs the client's initialize handshake and returns the response."
  [proxy]
  (send! proxy {"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" {"capabilities" {"workspace" {}}}})
  (let [response (recv! proxy)]
    (send! proxy {"jsonrpc" "2.0" "method" "initialized" "params" {}})
    response))

(defn configure!
  "Configures the fake server's progress and waits until it has taken the
  configuration: the notification travels over stdin while change reports
  travel over the socket, so a report could otherwise overtake it and hold
  it behind the closed gate."
  [proxy progress]
  (send! proxy {"jsonrpc" "2.0" "method" "fake/configure" "params" {"progress" progress}})
  (let [id (str "configure-" (System/nanoTime))]
    (send! proxy {"jsonrpc" "2.0" "id" id "method" "fake/received"})
    (recv-response! proxy id)))

(defn analysis-config [create-after end-after]
  {"create_after_ms" create-after "end_after_ms" end-after "title" analysis-title})

(defn control! [{:keys [socket]} request timeout-ms]
  (client/request! socket request timeout-ms))

(defn changed! [{:keys [project] :as proxy} paths wait?]
  (control! proxy {:op "changed" :files (mapv #(str (fs/path project %)) paths) :wait wait?} 70000))

(defn received!
  "What the fake server received so far, as method names; responses to its
  own requests appear as response:<id>, and the round trips `configure!`
  makes are left out. Only usable while the gate is open."
  [proxy id]
  (send! proxy {"jsonrpc" "2.0" "id" id "method" "fake/received"})
  (into []
        (comp (remove #(and (= "fake/received" (get % "method"))
                            (str/starts-with? (str (get % "id")) "configure-")))
              (map #(or (get % "method") (str "response:" (get % "response")))))
        (get (recv-response! proxy id) "result")))

(defn wait-exit
  "The proxy's exit code, or ::timeout. Removes the temporary project's
  state directory afterwards."
  [{:keys [proc project]} ms]
  (let [code (if (.waitFor ^Process (:proc proc) ms TimeUnit/MILLISECONDS)
               (.exitValue ^Process (:proc proc))
               (do (.destroyForcibly ^Process (:proc proc))
                   ::timeout))]
    (fs/delete-tree (project/state-dir project))
    code))

(defn stop! [proxy]
  (send! proxy {"jsonrpc" "2.0" "id" 999 "method" "shutdown"})
  (recv-response! proxy 999)
  (send! proxy {"jsonrpc" "2.0" "method" "exit"})
  (wait-exit proxy 10000))

(defn log-entries [{:keys [log]}]
  (mapv json/parse-string (str/split-lines (slurp log))))

(def gate-events
  #{"gate-close" "gate-open" "send-start" "send-queued" "send-end" "send-analysis-begin"
    "send-deadline" "send-dropped" "report-dropped"
    "hold" "anomaly" "held-request-cancelled" "sends-abandoned" "held-requests-failed"})

(defn gate-log [entries]
  (into [] (comp (filter #(contains? gate-events (get % "event")))
                 (map #(get % "event")))
        entries))

(defn event [entries name]
  (first (filter #(= name (get % "event")) entries)))

(deftest progress-ownership-test
  (let [proxy (start-proxy)
        init (initialize! proxy)]
    (is (true? (get-in init ["result" "clientCapabilities" "window" "workDoneProgress"]))
        "the server sees a client that supports work-done progress")
    (send! proxy {"jsonrpc" "2.0" "method" "fake/client-progress"
                  "params" {"token" "client-token" "value" {"kind" "report" "message" "startup"}}})
    (send! proxy {"jsonrpc" "2.0" "method" "fake/progress"
                  "params" {"title" "Fetching libs for completion" "create_after_ms" 0 "end_after_ms" 50}})
    (let [m (recv! proxy)]
      (is (= ["$/progress" "client-token"] [(get m "method") (get-in m ["params" "token"])])
          "progress on the client's own token passes through"))
    (Thread/sleep 300)
    (is (= ["initialize" "initialized" "fake/client-progress" "fake/progress" "response:fake-1" "fake/received"]
           (received! proxy 2))
        "the proxy answered the server's create request itself")
    (let [entries (log-entries proxy)]
      (is (= ["window/workDoneProgress/create" "$/progress" "$/progress"]
             (mapv #(get % "method") (filter #(= "s->p" (get % "dir")) entries)))
          "create, begin and end of the owned token were consumed")
      (is (= ["client-token"]
             (mapv #(get-in % ["body" "params" "token"])
                   (filter #(and (= "s->c" (get % "dir")) (= "$/progress" (get % "method"))) entries)))
          "only the client-token progress was forwarded")
      (is (= "Fetching libs for completion"
             (get-in (first (filter #(= "s->p" (get % "dir")) (filter #(= "$/progress" (get % "method")) entries)))
                     ["body" "params" "value" "title"]))))
    (is (= 0 (stop! proxy)))))

(deftest hold-until-analysis-end-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 300))
    (is (= {"ok" true "reported" 1 "queued" false} (changed! proxy ["src/a.clj"] false)))
    (let [t0 (now)]
      (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {"x" 1}})
      (send! proxy {"jsonrpc" "2.0" "method" "textDocument/didChange" "params" {"y" 2}})
      (let [response (recv-response! proxy 10)
            elapsed (- (now) t0)]
        (is (= {"echo" "textDocument/definition" "params" {"x" 1}} (get response "result")))
        (is (<= 1300 elapsed) (str "held across create, begin and end; took " elapsed " ms"))))
    (is (= ["initialize" "initialized" "fake/configure" "workspace/didChangeWatchedFiles" "response:fake-1"
            "textDocument/definition" "textDocument/didChange" "fake/received"]
           (received! proxy 11))
        "the request and the notification reached the server after the analysis, in order")
    (let [entries (log-entries proxy)]
      (is (= ["gate-close" "send-start" "hold" "hold" "send-analysis-begin" "send-end" "gate-open"]
             (gate-log entries)))
      (is (= "analysis-end" (get (event entries "gate-open") "reason")))
      (is (= 2 (get (event entries "gate-open") "released")))
      (is (every? #(<= 1000 (get % "held-ms")) (filter #(contains? % "held-ms") entries))
          "released messages are logged with their hold time")
      (is (= (str "file://" (:project proxy) "/src/a.clj")
             (get-in (first (filter #(= "workspace/didChangeWatchedFiles" (get % "method")) entries))
                     ["body" "params" "changes" 0 "uri"]))))
    (is (= 0 (stop! proxy)))))

(deftest coalescing-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 500))
    (let [t0 (now)]
      (is (= {"ok" true "reported" 1 "queued" false} (changed! proxy ["src/a.clj"] false)))
      (Thread/sleep 200)
      (is (= {"ok" true "reported" 1 "queued" true} (changed! proxy ["src/b.clj"] false))
          "a report during an outstanding send is queued")
      (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
      (recv-response! proxy 10)
      (is (<= 3000 (- (now) t0)) "the gate stays closed until the second send ended"))
    (send! proxy {"jsonrpc" "2.0" "id" 11 "method" "fake/received"})
    (let [received (remove #(and (= "fake/received" (get % "method"))
                                 (str/starts-with? (str (get % "id")) "configure-"))
                           (get (recv-response! proxy 11) "result"))
          sends (filter #(= "workspace/didChangeWatchedFiles" (get % "method")) received)]
      (is (= ["initialize" "initialized" "fake/configure" "workspace/didChangeWatchedFiles" "response:fake-1"
              "workspace/didChangeWatchedFiles" "response:fake-2" "textDocument/definition" "fake/received"]
             (mapv #(or (get % "method") (str "response:" (get % "response"))) received))
          "exactly two notifications, the second after the first analysis ended")
      (is (= [[(str "file://" (:project proxy) "/src/a.clj")] [(str "file://" (:project proxy) "/src/b.clj")]]
             (mapv #(mapv (fn [c] (get c "uri")) (get % "changes")) sends))))
    (is (= ["gate-close" "send-start" "send-queued" "hold" "send-analysis-begin" "send-end"
            "send-start" "send-analysis-begin" "send-end" "gate-open"]
           (gate-log (log-entries proxy))))
    (is (= 0 (stop! proxy)))))

(deftest early-create-is-an-anomaly-test
  (let [proxy (start-proxy {:deadline-ms 2000})]
    (initialize! proxy)
    (configure! proxy (analysis-config 100 100))
    (let [t0 (now)]
      (changed! proxy ["src/a.clj"] false)
      (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
      (recv-response! proxy 10)
      (is (<= 1900 (- (now) t0)) "an end that came too early does not open the gate; the deadline does"))
    (let [entries (log-entries proxy)]
      (is (= "create-too-early" (get (event entries "anomaly") "kind")))
      (is (= "no-analysis" (get (event entries "send-dropped") "reason"))
          "no analysis began for the send, so it is dropped at the deadline")
      (is (= "deadline" (get (event entries "gate-open") "reason"))))
    (is (= 0 (stop! proxy)))))

(deftest missing-end-opens-at-deadline-test
  (let [proxy (start-proxy {:deadline-ms 2000})]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 nil))
    (let [reply (changed! proxy ["src/a.clj"] true)]
      (is (= {"ok" true "reported" 1 "timed_out" false} (dissoc reply "waited_ms")))
      (is (<= 1900 (get reply "waited_ms")) "changed --wait returns when the deadline opens the gate"))
    (let [entries (log-entries proxy)]
      (is (true? (get (event entries "send-deadline") "analysis-running")))
      (is (= ["gate-close" "send-start" "send-analysis-begin" "send-deadline" "gate-open"] (gate-log entries))
          "the send stays outstanding, waiting for its end, without holding the gate")
      (is (true? (get (control! proxy {:op "status"} 5000) "in_flight"))))
    (is (= 0 (stop! proxy)))))

(deftest status-and-wait-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 300))
    (changed! proxy ["src/a.clj"] false)
    (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/hover" "params" {}})
    (Thread/sleep 100)
    (is (= {"gate_open" false "in_flight" true "pending_paths" 0 "held_messages" 1}
           (select-keys (control! proxy {:op "status"} 5000) ["gate_open" "in_flight" "pending_paths" "held_messages"])))
    (let [reply (changed! proxy ["src/b.clj"] true)]
      (is (false? (get reply "timed_out")))
      (is (<= 2500 (get reply "waited_ms")) "a queued report waits for its own send to end"))
    (recv-response! proxy 10)
    (let [status (control! proxy {:op "status"} 5000)]
      (is (= {"gate_open" true "in_flight" false "pending_paths" 0 "held_messages" 0}
             (select-keys status ["gate_open" "in_flight" "pending_paths" "held_messages"])))
      (is (= (:project proxy) (get status "project_root")))
      (is (= (.pid ^Process (:proc (:proc proxy))) (get status "pid"))))
    (is (= {"ok" false} (select-keys (control! proxy {:op "nope"} 5000) ["ok"])))
    (is (= 0 (stop! proxy)))))

(deftest shutdown-opens-the-gate-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 nil))
    (changed! proxy ["src/a.clj"] false)
    (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
    (send! proxy {"jsonrpc" "2.0" "id" 11 "method" "shutdown"})
    (is (= 10 (get (recv! proxy) "id")) "the held request is answered before shutdown")
    (is (= 11 (get (recv! proxy) "id")))
    (is (= ["initialize" "initialized" "fake/configure" "workspace/didChangeWatchedFiles"
            "textDocument/definition" "shutdown" "fake/received"]
           (received! proxy 12))
        "released before the batch's create was even due")
    (let [entries (log-entries proxy)]
      (is (= "shutdown" (get (event entries "sends-abandoned") "reason")))
      (is (= "shutdown" (get (event entries "gate-open") "reason"))))
    (send! proxy {"jsonrpc" "2.0" "method" "exit"})
    (is (= 0 (wait-exit proxy 10000)))))

(deftest cancel-held-request-test
  (let [proxy (start-proxy {:deadline-ms 5000})]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 nil))
    (changed! proxy ["src/a.clj"] false)
    (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
    (let [t0 (now)]
      (send! proxy {"jsonrpc" "2.0" "method" "$/cancelRequest" "params" {"id" 10}})
      (let [response (recv! proxy)]
        (is (= {"jsonrpc" "2.0" "id" 10 "error" {"code" -32800 "message" "Request cancelled while held by clojure-lsp-proxy"}}
               response))
        (is (> 500 (- (now) t0)) "answered at once, not at the deadline")))
    (send! proxy {"jsonrpc" "2.0" "id" 11 "method" "shutdown"})
    (recv-response! proxy 11)
    (is (= ["initialize" "initialized" "fake/configure" "workspace/didChangeWatchedFiles" "shutdown" "fake/received"]
           (received! proxy 12))
        "neither the cancelled request nor the cancel reached the server")
    (send! proxy {"jsonrpc" "2.0" "method" "exit"})
    (is (= 0 (wait-exit proxy 10000)))))

(deftest server-death-fails-held-requests-test
  (let [proxy (start-proxy)
        init (initialize! proxy)
        server-pid (get-in init ["result" "serverInfo" "pid"])]
    (configure! proxy (analysis-config 1100 nil))
    (changed! proxy ["src/a.clj"] false)
    (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
    (send! proxy {"jsonrpc" "2.0" "method" "textDocument/didChange" "params" {}})
    (Thread/sleep 100)
    (p/shell "kill" "-9" (str server-pid))
    (let [response (recv! proxy)]
      (is (= [10 -32603] [(get response "id") (get-in response ["error" "code"])])
          "the held request gets an InternalError"))
    (is (= 1 (wait-exit proxy 10000)))
    (let [entries (log-entries proxy)]
      (is (= 1 (get (event entries "held-requests-failed") "count")))
      (is (false? (get (event entries "server-exit") "expected"))))
    (is (not (fs/exists? (:socket proxy))) "the socket is removed on exit")))

(defn wait-for-gate! [proxy]
  (control! proxy {:op "changed" :files [] :wait true} 70000))

(defn sent-uris
  "The URIs of every didChangeWatchedFiles the proxy sent, per send."
  [proxy]
  (mapv #(mapv (fn [c] [(get c "uri") (get c "type")]) (get-in % ["body" "params" "changes"]))
        (filter #(= "workspace/didChangeWatchedFiles" (get % "method")) (log-entries proxy))))

(defn uri [proxy path] (str "file://" (:project proxy) "/" path))

(deftest changes-between-brackets-test
  (let [proxy (start-proxy)]
    (try
      (initialize! proxy)
      (configure! proxy (analysis-config 1100 100))
      (control! proxy {:op "bash-started" :tool_use_id "before"} 5000)
      (control! proxy {:op "bash-finished" :tool_use_id "before"} 5000)
      (doseq [path ["src/a.clj" "src/b.clj"]]
        (Thread/sleep 1100)
        (spit (str (fs/path (:project proxy) path)) "(ns externally-edited)\n")
        ;; The write predates the next bracket's marker by a whole second.
        (Thread/sleep 1100)
        (is (= {"ok" true} (control! proxy {:op "bash-started" :tool_use_id path} 5000)))
        (is (false? (get (control! proxy {:op "status"} 5000) "gate_open")))
        (wait-for-gate! proxy)
        (is (= [[(uri proxy path) 2]] (last (sent-uris proxy))))
        (is (= 0 (get (control! proxy {:op "bash-finished" :tool_use_id path} 5000) "reported"))))
      (is (= 2 (count (sent-uris proxy))) "both gaps were queued before their checkpoints advanced")
      (finally
        (is (= 0 (stop! proxy)))))))

(deftest other-analysis-inputs-invalidate-reported-content-test
  (doseq [input [:native :explicit]]
    (let [proxy (start-proxy)
          file (str (fs/path (:project proxy) "src/a.clj"))
          restore! (if (= :native input)
                     #(spit file "(ns a) :A\n")
                     #(fs/delete-if-exists file))]
      (try
        (initialize! proxy)
        (configure! proxy (analysis-config 1100 100))
        (control! proxy {:op "bash-started" :tool_use_id "outer"} 5000)
        (restore!)
        (control! proxy {:op "bash-started" :tool_use_id "inner"} 5000)
        (spit file "(ns a) :B\n")
        (case input
          :native (do
                    (send! proxy {"jsonrpc" "2.0" "method" "textDocument/didOpen"
                                  "params" {"textDocument" {"uri" (uri proxy "src/a.clj")
                                                            "languageId" "clojure" "version" 1
                                                            "text" "(ns a) :B\n"}}})
                    (received! proxy "after-native"))
          :explicit (changed! proxy ["src/a.clj"] true))
        (restore!)
        (is (= 1 (get (control! proxy {:op "bash-finished" :tool_use_id "outer"} 5000) "reported"))
            (str input " replaced analysis, so restoring the cached disk state must be reported"))
        (wait-for-gate! proxy)
        (is (= [[(uri proxy "src/a.clj") (if (= :native input) 2 3)]]
               (last (sent-uris proxy))))
        (finally
          (is (= 0 (stop! proxy))))))))

(deftest bash-bracket-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 100))
    (testing "a command that writes a file"
      (is (= {"ok" true} (control! proxy {:op "bash-started" :tool_use_id "t1"} 5000)))
      (spit (str (fs/path (:project proxy) "src" "a.clj")) "(ns a) :edited\n")
      (is (= {"ok" true "reported" 1 "queued" false}
             (control! proxy {:op "bash-finished" :tool_use_id "t1" :bash_edit_diff nil} 5000)))
      (wait-for-gate! proxy))
    (testing "a read-only command reports nothing and never closes the gate"
      (Thread/sleep 1100)
      (control! proxy {:op "bash-started" :tool_use_id "t2"} 5000)
      (is (= {"ok" true "reported" 0 "queued" false}
             (control! proxy {:op "bash-finished" :tool_use_id "t2" :bash_edit_diff nil} 5000)))
      (is (true? (get (control! proxy {:op "status"} 5000) "gate_open"))))
    (testing "Claude Code's bashEditDiff is unioned with the git check"
      (Thread/sleep 1100)
      (control! proxy {:op "bash-started" :tool_use_id "t3"} 5000)
      (fs/delete (fs/path (:project proxy) "src" "b.clj"))
      (is (= {"ok" true "reported" 2 "queued" false}
             (control! proxy {:op "bash-finished" :tool_use_id "t3"
                              :bash_edit_diff {"changedFiles" [(str (fs/path (:project proxy) "src" "a.clj"))]}} 5000)))
      (wait-for-gate! proxy))
    (testing "a finish without a start falls back to the last check's snapshot"
      (Thread/sleep 1100)
      (spit (str (fs/path (:project proxy) "src" "c.clj")) "(ns c)\n")
      (is (= {"ok" true "reported" 1 "queued" false}
             (control! proxy {:op "bash-finished" :tool_use_id "t4" :bash_edit_diff nil} 5000)))
      (wait-for-gate! proxy))
    (testing "interleaved brackets each see their own snapshot"
      (Thread/sleep 1100)
      (control! proxy {:op "bash-started" :tool_use_id "t5"} 5000)
      (spit (str (fs/path (:project proxy) "src" "a.clj")) "(ns a) :by-t5\n")
      (Thread/sleep 1100)
      (control! proxy {:op "bash-started" :tool_use_id "t6"} 5000)
      (is (= {"ok" true "reported" 0 "queued" false}
             (control! proxy {:op "bash-finished" :tool_use_id "t5" :bash_edit_diff nil} 5000))
          "t6's start already reported t5's write; t5's finish does not repeat it")
      (is (= 0 (get (control! proxy {:op "bash-finished" :tool_use_id "t6" :bash_edit_diff nil} 5000) "reported")))
      (wait-for-gate! proxy))
    (is (= [[[(uri proxy "src/a.clj") 2]]
            [[(uri proxy "src/a.clj") 2] [(uri proxy "src/b.clj") 3]]
            [[(uri proxy "src/c.clj") 2]]
            [[(uri proxy "src/a.clj") 2]]]
           (mapv #(sort-by first %) (sent-uris proxy)))
        "the deleted file is reported as deleted, the rest as changed")
    (is (= 0 (stop! proxy)))))

(deftest hook-scripts-test
  (let [proxy (start-proxy)
        hook (fn [script input]
               (let [t0 (now)
                     {:keys [out err exit]} (p/shell {:in (json/generate-string input)
                                                      :extra-env {"CLAUDE_PROJECT_DIR" (:project proxy)}
                                                      :out :string :err :string :continue true}
                                                     (str (fs/path root "bin" script)))]
                 {:out out :err err :exit exit :ms (- (now) t0)}))]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 100))
    (let [started (hook "on-bash-start" {"tool_use_id" "h1" "tool_input" {"command" "echo"}})]
      (is (= {:out "" :exit 0} (select-keys started [:out :exit])) "silent on stdout, exit 0")
      (println "  on-bash-start took" (:ms started) "ms"))
    (spit (str (fs/path (:project proxy) "src" "a.clj")) "(ns a) :hooked\n")
    (let [finished (hook "on-bash-end" {"tool_use_id" "h1" "tool_response" {"stdout" ""}})]
      (is (= {:out "" :exit 0} (select-keys finished [:out :exit])))
      (println "  on-bash-end took" (:ms finished) "ms"))
    (wait-for-gate! proxy)
    (is (= [[[(uri proxy "src/a.clj") 2]]] (sent-uris proxy)) "the hooks reported the write")
    (is (= ["bash-started" "bash-finished" "changed"]
           (mapv #(get % "op") (filter #(= "control" (get % "event")) (log-entries proxy)))))
    (testing "a hook with no proxy to talk to is still silent and exits 0"
      (let [{:keys [out exit]} (p/shell {:in "{}" :extra-env {"CLAUDE_PROJECT_DIR" (str (fs/create-temp-dir {:prefix "no-proxy"}))}
                                         :out :string :err :string :continue true}
                                        (str (fs/path root "bin" "on-bash-end")))]
        (is (= ["" 0] [out exit]))))
    (is (= 0 (stop! proxy)))))

(deftest deadline-chain-test
  (testing "a send whose analysis outlives the deadline stays outstanding; the next send waits for its end"
    (let [proxy (start-proxy {:deadline-ms 2000})
          t0 (now)]
      (initialize! proxy)
      (configure! proxy (analysis-config 1100 3500))
      (changed! proxy ["src/a.clj"] false)
      (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
      (recv-response! proxy 10)
      (let [elapsed (- (now) t0)]
        (is (<= 1900 elapsed 3500) (str "released by the deadline, not by the end; took " elapsed " ms")))
      (is (= {"ok" true "reported" 1 "queued" true} (changed! proxy ["src/b.clj"] false))
          "the next report queues behind the outstanding send")
      (send! proxy {"jsonrpc" "2.0" "id" 11 "method" "textDocument/definition" "params" {}})
      (recv-response! proxy 11)
      (let [elapsed (- (now) t0)]
        (is (<= 3900 elapsed 5000)
            (str "a message held behind a pending report is released one deadline after the gate closed; took " elapsed)))
      (is (= ["initialize" "initialized" "fake/configure" "workspace/didChangeWatchedFiles" "response:fake-1"
              "textDocument/definition" "textDocument/definition" "workspace/didChangeWatchedFiles" "response:fake-2"
              "fake/received"]
             (received! proxy 12))
          "the second request went out before the second batch, which the first batch's end started")
      (let [entries (log-entries proxy)
            begins (filter #(= "send-analysis-begin" (get % "event")) entries)]
        (is (= [[1 true]]
               (mapv (juxt #(get % "send") #(get % "after-deadline"))
                     (filter #(= "send-end" (get % "event")) entries)))
            "the first send ended on its own batch, after its deadline")
        (is (= [1 2] (mapv #(get % "send") begins)))
        (is (every? #(<= 900 (get % "after-ms")) begins)
            "each batch's create is attributed to the send that produced it")
        (let [log (gate-log entries)]
          (is (= ["gate-close" "send-start" "hold" "send-analysis-begin" "send-deadline" "gate-open"
                  "gate-close" "send-queued" "hold" "gate-open"]
                 (take 10 log))
              "the second closure was released by its own timer, before the first batch ended")
          (is (= ["send-end" "send-start" "send-analysis-begin"]
                 (filter #{"send-end" "send-start" "send-analysis-begin"} (drop 10 log)))
              "the second send started only after the first batch ended"))
        (is (true? (get (second (filter #(= "gate-open" (get % "event")) entries)) "still-pending"))
            "the second release happened with the report still pending"))
      (is (= 0 (stop! proxy))))))

(deftest report-before-initialized-waits-test
  (let [proxy (start-proxy)]
    (configure! proxy (analysis-config 1100 100))
    (is (= {"ok" true "reported" 1 "queued" true} (changed! proxy ["src/a.clj"] false))
        "queued until the server is initialized")
    (initialize! proxy)
    (wait-for-gate! proxy)
    (let [entries (log-entries proxy)
          index-of (fn [pred] (first (keep-indexed #(when (pred %2) %1) entries)))]
      (is (= "server-not-ready" (get (event entries "send-queued") "reason")))
      (is (< (index-of #(= "initialized" (get % "method")))
             (index-of #(= "send-start" (get % "event"))))
          "the send starts after initialized")
      (is (= "analysis-end" (get (event entries "gate-open") "reason"))))
    (is (= 0 (stop! proxy)))))

(deftest other-progress-during-a-send-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy [(analysis-config 1100 300)
                       {"create_after_ms" 200 "end_after_ms" 100 "title" "Fetching libs for completion"}])
    (let [t0 (now)]
      (changed! proxy ["src/a.clj"] false)
      (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
      (recv-response! proxy 10)
      (is (<= 1300 (- (now) t0)) "the fetch-libs end during the send does not open the gate"))
    (let [entries (log-entries proxy)]
      (is (nil? (event entries "anomaly")))
      (is (= ["Fetching libs for completion" analysis-title]
             (keep #(get-in % ["body" "params" "value" "title"]) (filter #(= "s->p" (get % "dir")) entries)))
          "both progress sequences were consumed"))
    (is (= 0 (stop! proxy)))))

(deftest analysis-without-send-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (send! proxy {"jsonrpc" "2.0" "method" "fake/progress" "params" (analysis-config 0 50)})
    (Thread/sleep 400)
    (is (= "analysis-without-send" (get (event (log-entries proxy) "anomaly") "kind")))
    (is (true? (get (control! proxy {:op "status"} 5000) "gate_open")))
    (is (= 0 (stop! proxy)))))

(deftest responses-and-foreign-cancels-pass-the-closed-gate-test
  (let [proxy (start-proxy {:deadline-ms 5000})]
    (initialize! proxy)
    (configure! proxy (analysis-config 1100 nil))
    (changed! proxy ["src/a.clj"] false)
    (send! proxy {"jsonrpc" "2.0" "id" 10 "method" "textDocument/definition" "params" {}})
    (send! proxy {"jsonrpc" "2.0" "id" "server-req-1" "result" 42})
    (send! proxy {"jsonrpc" "2.0" "method" "$/cancelRequest" "params" {"id" 999}})
    (send! proxy {"jsonrpc" "2.0" "method" "$/cancelRequest" "params" {}})
    (Thread/sleep 300)
    (is (= 1 (get (control! proxy {:op "status"} 5000) "held_messages"))
        "the response, the cancel of an unheld request and a cancel without id are not held")
    (send! proxy {"jsonrpc" "2.0" "id" 11 "method" "shutdown"})
    (recv-response! proxy 10)
    (recv-response! proxy 11)
    (is (= ["initialize" "initialized" "fake/configure" "workspace/didChangeWatchedFiles" "response:server-req-1"
            "$/cancelRequest" "$/cancelRequest" "textDocument/definition" "shutdown" "fake/received"]
           (received! proxy 12)))
    (send! proxy {"jsonrpc" "2.0" "method" "exit"})
    (is (= 0 (wait-exit proxy 10000)))))

(deftest empty-report-gates-nothing-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (is (= {"ok" true "reported" 0 "queued" false} (changed! proxy [] false)))
    (is (= {"ok" true "reported" 0 "queued" false} (changed! proxy ["notes.txt" "target/x.clj"] false))
        "paths clojure-lsp would not analyze are filtered out")
    (let [reply (changed! proxy ["notes.txt"] true)]
      (is (= {"ok" true "reported" 0 "timed_out" false} (dissoc reply "waited_ms")))
      (is (> 200 (get reply "waited_ms")) "nothing to wait for"))
    (is (true? (get (control! proxy {:op "status"} 5000) "gate_open")))
    (is (= [] (gate-log (log-entries proxy))))
    (is (= 0 (stop! proxy)))))

(let [{:keys [fail error]} (run-tests 'gate-test)]
  (shutdown-agents)
  (System/exit (+ fail error)))

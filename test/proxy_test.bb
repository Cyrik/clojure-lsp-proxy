#!/usr/bin/env bb

(ns proxy-test
  "End-to-end tests: the proxy runs as a subprocess in front of
  test/fake_server.bb, and the test plays the client on its stdio."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-lsp-proxy.framing :as framing])
  (:import [java.util.concurrent TimeUnit]))

(def root (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(def launcher (str (fs/path root "bin" "clojure-lsp-proxy-server")))
(def fake-server (str (fs/path root "test" "fake_server.bb")))

(defn start-proxy []
  (let [log (str (fs/create-temp-file {:prefix "clojure-lsp-proxy-test" :suffix ".log"}))]
    {:proc (p/process [launcher "--fake-arg"]
                      {:extra-env {"CLOJURE_LSP_PROXY_SERVER" fake-server
                                   "CLAUDE_PLUGIN_LSP_LOG_FILE" log}
                       :err :string})
     :log log}))

(defn send! [{:keys [proc]} message]
  (framing/write-message (:in proc) (framing/encode message)))

(defn recv!
  "The next server-to-client message, or ::timeout after 10 s."
  [{:keys [proc]}]
  (deref (future (some-> (framing/read-message (:out proc)) :body framing/parse)) 10000 ::timeout))

(defn wait-exit
  "The proxy's exit code, or ::timeout when it is still running after `ms`."
  [{:keys [proc]} ms]
  (if (.waitFor ^Process (:proc proc) ms TimeUnit/MILLISECONDS)
    (.exitValue ^Process (:proc proc))
    (do (.destroyForcibly ^Process (:proc proc))
        ::timeout)))

(defn log-entries [{:keys [log]}]
  (mapv json/parse-string (str/split-lines (slurp log))))

(def informational-events #{"control-listening" "initialize-patched" "control"})

(defn events
  "The proxy's lifecycle events, in order."
  [entries]
  (into [] (comp (filter #(= "proxy" (get % "dir")))
                 (map #(get % "event"))
                 (remove informational-events))
        entries))

(defn methods-in [entries dir]
  (into [] (comp (filter #(= dir (get % "dir"))) (map #(get % "method"))) entries))

(defn process-alive? [pid]
  (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid)))))

(defn initialize! [proxy]
  (send! proxy {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                "params" {"capabilities" {"window" {"workDoneProgress" false}}}})
  (recv! proxy))

(deftest forwards-both-ways-and-exits-after-exit-test
  (let [proxy (start-proxy)
        init (initialize! proxy)
        server-pid (get-in init ["result" "serverInfo" "pid"])]
    (is (= 1 (get init "id")))
    (is (= "fake-server" (get-in init ["result" "serverInfo" "name"])))
    (is (process-alive? server-pid))
    (send! proxy {"jsonrpc" "2.0" "method" "initialized" "params" {}})
    (send! proxy {"jsonrpc" "2.0" "id" 2 "method" "textDocument/definition" "params" {"text" "λ"}})
    (is (= {"jsonrpc" "2.0" "id" 2 "result" {"echo" "textDocument/definition" "params" {"text" "λ"}}}
           (recv! proxy))
        "requests and responses cross unchanged, multibyte text included")
    (send! proxy {"jsonrpc" "2.0" "method" "fake/notify" "params" {"n" 1}})
    (is (= {"jsonrpc" "2.0" "method" "fake/hello" "params" {"n" 1}} (recv! proxy))
        "server notifications reach the client")
    (send! proxy {"jsonrpc" "2.0" "id" 3 "method" "fake/received"})
    (is (= ["initialize" "initialized" "textDocument/definition" "fake/notify" "fake/received"]
           (mapv #(get % "method") (get (recv! proxy) "result")))
        "the server sees the client's messages in order")
    (send! proxy {"jsonrpc" "2.0" "id" 4 "method" "shutdown"})
    (is (= {"jsonrpc" "2.0" "id" 4 "result" nil} (recv! proxy)))
    (send! proxy {"jsonrpc" "2.0" "method" "exit"})
    (is (= 0 (wait-exit proxy 10000)) "exit 0 after a forwarded exit")
    (is (not (process-alive? server-pid)) "the server is gone with the proxy")
    (let [entries (log-entries proxy)
          start (first entries)]
      (testing "the log records the start, every message in both directions, server stderr and the exit"
        (is (= "start" (get start "event")))
        (is (= ["--fake-arg"] (get start "args")))
        (is (= server-pid (get start "server-pid")))
        (is (= ["initialize" "initialized" "textDocument/definition" "fake/notify" "fake/received" "shutdown" "exit"]
               (methods-in entries "c->s")))
        (is (= [1 2 nil 3 4]
               (into [] (comp (filter #(= "s->c" (get % "dir"))) (map #(get % "id"))) entries)))
        (is (= {"window" {"workDoneProgress" false}}
               (get (first (filter #(= "initialize-patched" (get % "event")) entries)) "client-capabilities"))
            "the client's own capabilities are logged")
        (is (= {"window" {"workDoneProgress" true}
                "workspace" {"workspaceEdit" {"documentChanges" true "resourceOperations" ["create" "rename" "delete"]}}}
               (get-in (first (filter #(= "initialize" (get % "method")) entries)) ["body" "params" "capabilities"]))
            "the forwarded initialize is logged in full, patched")
        (is (some #(and (= "server-stderr" (get % "dir")) (= "fake server started" (get % "line"))) entries))
        (is (= ["start" "server-exit" "exit"] (events entries)))
        (is (= {"code" 0 "expected" true}
               (select-keys (first (filter #(= "server-exit" (get % "event")) entries)) ["code" "expected"])))))))

(deftest server-crash-test
  (let [proxy (start-proxy)
        init (initialize! proxy)]
    (is (= 1 (get init "id")))
    (send! proxy {"jsonrpc" "2.0" "method" "fake/crash"})
    (is (= 1 (wait-exit proxy 10000)) "a server death without exit or client EOF is a crash")
    (is (= {"code" 3 "expected" false}
           (select-keys (first (filter #(= "server-exit" (get % "event")) (log-entries proxy))) ["code" "expected"])))))

(deftest client-eof-shuts-the-server-down-test
  (let [proxy (start-proxy)
        init (initialize! proxy)
        server-pid (get-in init ["result" "serverInfo" "pid"])]
    (.close (:in (:proc proxy)))
    (is (= 0 (wait-exit proxy 10000)) "exit 0 after the client closed stdin")
    (is (not (process-alive? server-pid)))
    (let [entries (log-entries proxy)]
      (is (= ["start" "client-eof" "server-exit" "exit"] (events entries)))
      (is (= ["initialize"] (methods-in entries "c->s")))
      (is (= ["shutdown" "exit"] (methods-in entries "p->s"))
          "the proxy sends shutdown and exit itself")
      (is (= ["clojure-lsp-proxy/shutdown/1"]
             (mapv #(get % "id") (filter #(= "s->p" (get % "dir")) entries)))
          "the server answered the proxy's shutdown and the answer was not forwarded"))))

(deftest sigterm-after-shutdown-test
  (testing "Claude Code's way of ending a server: shutdown, then SIGTERM instead of exit"
    (let [proxy (start-proxy)
          init (initialize! proxy)
          server-pid (get-in init ["result" "serverInfo" "pid"])]
      (send! proxy {"jsonrpc" "2.0" "id" 2 "method" "shutdown"})
      (is (= {"jsonrpc" "2.0" "id" 2 "result" nil} (recv! proxy)))
      ;; kill(1) rather than Process.destroy, which would also close the
      ;; proxy's stdin and take the client-EOF path first
      (p/shell "kill" "-TERM" (str (.pid ^Process (:proc (:proc proxy)))))
      (is (= 143 (wait-exit proxy 10000)) "SIGTERM ends the proxy")
      (Thread/sleep 500)
      (is (not (process-alive? server-pid)) "the server ends when its stdin closes")
      (is (= ["start" "terminated"] (events (log-entries proxy)))))))

(deftest hanging-server-is-killed-test
  (let [proxy (start-proxy)
        init (initialize! proxy)
        server-pid (get-in init ["result" "serverInfo" "pid"])]
    (send! proxy {"jsonrpc" "2.0" "method" "fake/hang"})
    (.close (:in (:proc proxy)))
    (is (= 0 (wait-exit proxy 15000)) "exit 0 even when the server ignores shutdown and exit")
    (is (not (process-alive? server-pid)) "the server was killed")
    (is (= ["start" "client-eof" "shutdown-response-timeout" "server-killed" "server-exit" "exit"]
           (events (log-entries proxy))))))

(deftest server-fallback-test
  (testing "a CLOJURE_LSP_PROXY_SERVER path that is not an executable file falls back to clojure-lsp on PATH, logged"
    (let [bin (str (fs/create-temp-dir {:prefix "clojure-lsp-proxy-bin"}))
          _ (fs/create-sym-link (fs/path bin "clojure-lsp") fake-server)
          log (str (fs/create-temp-file {:prefix "clojure-lsp-proxy-test" :suffix ".log"}))
          proxy {:proc (p/process [launcher]
                                  {:extra-env {"CLOJURE_LSP_PROXY_SERVER" "/nonexistent/clojure-lsp"
                                               "CLAUDE_PLUGIN_LSP_LOG_FILE" log
                                               "PATH" (str bin ":" (fs/parent (fs/which "bb")) ":/usr/bin:/bin")}
                                   :err :string})
                 :log log}
          init (initialize! proxy)]
      (is (= "fake-server" (get-in init ["result" "serverInfo" "name"])) "the fallback server answered")
      (.close (:in (:proc proxy)))
      (is (= 0 (wait-exit proxy 10000)))
      (let [entries (log-entries proxy)]
        (is (= "clojure-lsp" (get (first entries) "command")))
        (is (= {"ignored" "/nonexistent/clojure-lsp" "command" "clojure-lsp"}
               (select-keys (first (filter #(= "server-fallback" (get % "event")) entries)) ["ignored" "command"])))))))

(let [{:keys [fail error]} (run-tests 'proxy-test)]
  (shutdown-agents)
  (System/exit (+ fail error)))

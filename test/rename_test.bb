#!/usr/bin/env bb

(ns rename-test
  "Applying workspace edits, and the rename operation end to end against
  the fake server with canned answers."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-lsp-proxy.client :as client]
            [clojure-lsp-proxy.control :as control]
            [clojure-lsp-proxy.framing :as framing]
            [clojure-lsp-proxy.project :as project]
            [clojure-lsp-proxy.rename :as rename])
  (:import [java.util.concurrent TimeUnit]))

(def root (str (fs/parent (fs/parent (fs/canonicalize *file*)))))
(def launcher (str (fs/path root "bin" "clojure-lsp-proxy-server")))
(def fake-server (str (fs/path root "test" "fake_server.bb")))

(defn range* [l1 c1 l2 c2]
  {"start" {"line" l1 "character" c1} "end" {"line" l2 "character" c2}})

(deftest apply-text-edits-test
  (testing "edits apply from the end so that earlier offsets stay valid"
    (is (= "(ns a)\n\n(defn yell [] (yell))\n"
           (rename/apply-text-edits "(ns a)\n\n(defn shout [] (shout))\n"
                                    [{"range" (range* 2 6 2 11) "newText" "yell"}
                                     {"range" (range* 2 16 2 21) "newText" "yell"}]))))
  (testing "multibyte text before an edit does not shift it, since positions count UTF-16 units"
    (is (= "λ yell" (rename/apply-text-edits "λ shout" [{"range" (range* 0 2 0 7) "newText" "yell"}]))))
  (testing "an edit spanning lines"
    (is (= "a\nX\nd" (rename/apply-text-edits "a\nb\nc\nd" [{"range" (range* 1 0 2 1) "newText" "X"}])))))

;;; end to end

(defn start-proxy
  ([] (start-proxy {}))
  ([{:keys [deadline-ms] :or {deadline-ms 30000}}]
  (let [project (str (fs/canonicalize (fs/create-temp-dir {:prefix "clojure-lsp-proxy-rename"})))
        log (str (fs/path project "proxy.log"))]
    (fs/create-dirs (fs/path project "src" "live"))
    (spit (str (fs/path project "src" "live" "util.clj")) "(ns live.util)\n\n(defn shout [s]\n  (str s \"!\"))\n")
    (spit (str (fs/path project "src" "live" "core.clj")) "(ns live.core\n  (:require [live.util :as util]))\n\n(defn greet [n]\n  (util/shout n))\n")
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

(defn recv! [{:keys [proc]}]
  (deref (future (some-> (framing/read-message (:out proc)) :body framing/parse)) 10000 ::timeout))

(defn initialize! [proxy]
  (send! proxy {"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" {"capabilities" {}}})
  (let [response (recv! proxy)]
    (send! proxy {"jsonrpc" "2.0" "method" "initialized" "params" {}})
    response))

(defn configure!
  "Configures the fake server and waits for a round trip, so that the
  configuration is in place before the next socket request reaches it."
  [proxy params]
  (send! proxy {"jsonrpc" "2.0" "method" "fake/configure" "params" params})
  (send! proxy {"jsonrpc" "2.0" "id" "configured" "method" "fake/received"})
  (loop []
    (let [m (recv! proxy)]
      (when-not (or (= ::timeout m) (= "configured" (get m "id")))
        (recur)))))

(defn stop! [{:keys [proc project] :as proxy}]
  (send! proxy {"jsonrpc" "2.0" "id" 999 "method" "shutdown"})
  (recv! proxy)
  (send! proxy {"jsonrpc" "2.0" "method" "exit"})
  (let [code (if (.waitFor ^Process (:proc proc) 10000 TimeUnit/MILLISECONDS)
               (.exitValue ^Process (:proc proc))
               (do (.destroyForcibly ^Process (:proc proc)) ::timeout))]
    (fs/delete-tree (project/state-dir project))
    code))

(defn uri [{:keys [project]} path] (str "file://" project "/" path))
(defn text [{:keys [project]} path] (slurp (str (fs/path project path))))

(defn var-rename-edit
  "What clojure-lsp answers for renaming shout to yell."
  [proxy]
  {"documentChanges" [{"textDocument" {"uri" (uri proxy "src/live/util.clj") "version" nil}
                       "edits" [{"range" (range* 2 6 2 11) "newText" "yell"}]}
                      {"textDocument" {"uri" (uri proxy "src/live/core.clj") "version" nil}
                       "edits" [{"range" (range* 4 8 4 13) "newText" "yell"}]}]})

(deftest rename-var-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy {"progress" {"create_after_ms" 1100 "end_after_ms" 100 "title" "Analyzing external file changes"}
                       "replies" {"textDocument/rename" (var-rename-edit proxy)}})
    (testing "dry run returns the edit and changes nothing"
      (let [reply (client/request! (:socket proxy)
                                   {:op "rename" :file (str (fs/path (:project proxy) "src/live/util.clj"))
                                    :line 2 :character 6 :new_name "yell" :apply false}
                                   10000)]
        (is (= {"ok" true "applied" false "summary" {"files" 2 "edits" 2 "renamed_files" 0}}
               (dissoc reply "edit")))
        (is (= (var-rename-edit proxy) (get reply "edit")))
        (is (str/includes? (text proxy "src/live/util.clj") "shout"))))
    (testing "apply edits both files, reports them and waits for the analysis"
      (let [t0 (System/currentTimeMillis)
            reply (client/request! (:socket proxy)
                                   {:op "rename" :file (str (fs/path (:project proxy) "src/live/util.clj"))
                                    :line 2 :character 6 :new_name "yell" :apply true}
                                   70000)]
        (is (= {"ok" true "applied" true "timed_out" false "summary" {"files" 2 "edits" 2 "renamed_files" 0}}
               (dissoc reply "edit" "touched")))
        (is (= #{"src/live/util.clj" "src/live/core.clj"}
               (into #{} (map #(str (fs/relativize (:project proxy) %))) (get reply "touched"))))
        (is (<= 1200 (- (System/currentTimeMillis) t0)) "replied after the re-analysis")
        (is (= "(ns live.util)\n\n(defn yell [s]\n  (str s \"!\"))\n" (text proxy "src/live/util.clj")))
        (is (= "(ns live.core\n  (:require [live.util :as util]))\n\n(defn greet [n]\n  (util/yell n))\n" (text proxy "src/live/core.clj")))))
    (let [entries (mapv json/parse-string (str/split-lines (slurp (:log proxy))))
          sent (first (filter #(= "workspace/didChangeWatchedFiles" (get % "method")) entries))]
      (is (= #{[(uri proxy "src/live/util.clj") 2] [(uri proxy "src/live/core.clj") 2]}
             (into #{} (map (juxt #(get % "uri") #(get % "type"))) (get-in sent ["body" "params" "changes"])))
          "both touched files were reported as changed")
      (is (= "clojure-lsp-proxy/textDocument/rename/1"
             (get (first (filter #(= "textDocument/rename" (get % "method")) entries)) "id"))
          "the rename was the proxy's own request"))
    (is (= 0 (stop! proxy)))))

(deftest rename-namespace-test
  (let [proxy (start-proxy)
        old-uri (uri proxy "src/live/util.clj")
        new-uri (uri proxy "src/live/text.clj")]
    (initialize! proxy)
    (configure! proxy {"progress" {"create_after_ms" 1100 "end_after_ms" 100 "title" "Analyzing external file changes"}
                       "replies" {"workspace/symbol" [{"name" "live.util" "kind" 3 "location" {"uri" old-uri "range" (range* 0 0 0 14)}}
                                                      {"name" "live.util" "kind" 3 "location" {"uri" (uri proxy "src/other/util.clj") "range" (range* 0 0 0 1)}}]
                                  "textDocument/documentSymbol"
                                  {"by_uri" {old-uri [{"name" "live.util" "kind" 3 "range" (range* 0 0 0 14) "selectionRange" (range* 0 4 0 13)
                                                       "children" [{"name" "shout" "kind" 12 "range" (range* 2 0 3 15) "selectionRange" (range* 2 6 2 11)}]}]
                                             (uri proxy "src/other/util.clj") [{"name" "other.util" "kind" 3 "range" (range* 0 0 0 1) "selectionRange" (range* 0 4 0 14)}]}}
                                  "textDocument/rename" {"documentChanges" [{"kind" "rename" "oldUri" old-uri "newUri" new-uri}]}
                                  "workspace/willRenameFiles" {"documentChanges" [{"textDocument" {"uri" old-uri "version" nil}
                                                                                   "edits" [{"range" (range* 0 4 0 13) "newText" "live.text"}]}
                                                                                  {"textDocument" {"uri" (uri proxy "src/live/core.clj") "version" nil}
                                                                                   "edits" [{"range" (range* 1 13 1 22) "newText" "live.text"}]}]}}})
    (testing "a namespace given by name resolves through workspace/symbol and documentSymbol"
      (let [reply (client/request! (:socket proxy) {:op "rename" :symbol "live.util" :new_name "live.text" :apply false} 10000)]
        (is (= {"files" 2 "edits" 2 "renamed_files" 1} (get reply "summary")))
        (is (= ["edit" "edit" "rename"] (mapv #(get % "kind" "edit") (get-in reply ["edit" "documentChanges"])))
            "the text edits from willRenameFiles come before the file rename")))
    (testing "a rename that would overwrite an existing file fails before anything is written"
      (spit (str (fs/path (:project proxy) "src/live/text.clj")) "occupied\n")
      (let [reply (client/request! (:socket proxy) {:op "rename" :symbol "live.util" :new_name "live.text" :apply true} 10000)]
        (is (false? (get reply "ok")))
        (is (str/includes? (get reply "error") "would overwrite"))
        (is (= "(ns live.util)\n\n(defn shout [s]\n  (str s \"!\"))\n" (text proxy "src/live/util.clj")) "untouched"))
      (fs/delete (fs/path (:project proxy) "src/live/text.clj")))
    (testing "apply edits the ns form and the require, then moves the file"
      (let [reply (client/request! (:socket proxy) {:op "rename" :symbol "live.util" :new_name "live.text" :apply true} 70000)]
        (is (true? (get reply "applied")))
        (is (not (fs/exists? (fs/path (:project proxy) "src/live/util.clj"))))
        (is (= "(ns live.text)\n\n(defn shout [s]\n  (str s \"!\"))\n" (text proxy "src/live/text.clj")))
        (is (str/includes? (text proxy "src/live/core.clj") "[live.text :as util]"))
        (is (= #{"src/live/util.clj" "src/live/text.clj" "src/live/core.clj"}
               (into #{} (map #(str (fs/relativize (:project proxy) %))) (get reply "touched"))))))
    (let [entries (mapv json/parse-string (str/split-lines (slurp (:log proxy))))
          sent (first (filter #(= "workspace/didChangeWatchedFiles" (get % "method")) entries))]
      (is (= {old-uri 3 new-uri 2 (uri proxy "src/live/core.clj") 2}
             (into {} (map (juxt #(get % "uri") #(get % "type"))) (get-in sent ["body" "params" "changes"])))
          "the old path is reported deleted, the new and the edited ones changed"))
    (testing "an unknown symbol is an error"
      (configure! proxy {"replies" {"workspace/symbol" [{"name" "shout" "kind" 12 "location" {"uri" (uri proxy "src/live/core.clj") "range" (range* 0 0 0 1)}}]
                                    "textDocument/documentSymbol" [{"name" "live.core" "kind" 3 "range" (range* 0 0 0 1) "selectionRange" (range* 0 4 0 13)}]}})
      (is (str/includes? (get (client/request! (:socket proxy) {:op "rename" :symbol "live.util/shout" :new_name "x" :apply false} 10000) "error")
                         "no definition")
          "the only candidate sits in a file declaring another namespace")
      (is (str/includes? (get (client/request! (:socket proxy) {:op "rename" :symbol "/x" :new_name "x" :apply false} 10000) "error")
                         "expected ns/name")))
    (is (= 0 (stop! proxy)))))

(deftest invalid-edit-is-refused-before-writing-test
  (let [proxy (start-proxy)
        before (text proxy "src/live/util.clj")]
    (initialize! proxy)
    (configure! proxy {"replies" {"textDocument/rename"
                                  {"documentChanges" [{"textDocument" {"uri" (uri proxy "src/live/util.clj") "version" nil}
                                                       "edits" [{"range" (range* 2 6 2 11) "newText" "yell"}]}
                                                      {"textDocument" {"uri" (uri proxy "src/live/core.clj") "version" nil}
                                                       "edits" [{"range" (range* 40 0 40 5) "newText" "yell"}]}]}}})
    (let [reply (client/request! (:socket proxy) {:op "rename" :file (str (fs/path (:project proxy) "src/live/util.clj"))
                                                  :line 2 :character 6 :new_name "yell" :apply true} 10000)]
      (is (false? (get reply "ok")))
      (is (str/includes? (get reply "error") "out of range"))
      (is (= before (text proxy "src/live/util.clj")) "the valid first file was not written either"))
    (is (= 0 (stop! proxy)))))

(deftest bracket-does-not-re-report-an-applied-rename-test
  (let [proxy (start-proxy)]
    (p/shell {:dir (:project proxy) :out :string :err :string} "git" "init" "-q")
    (initialize! proxy)
    (configure! proxy {"progress" {"create_after_ms" 1100 "end_after_ms" 100 "title" "Analyzing external file changes"}
                       "replies" {"textDocument/rename" (var-rename-edit proxy)}})
    (testing "a command that only renames"
      (is (= {"ok" true} (client/request! (:socket proxy) {:op "bash-started" :tool_use_id "r1"} 5000)))
      (is (true? (get (client/request! (:socket proxy) {:op "rename" :file (str (fs/path (:project proxy) "src/live/util.clj"))
                                                        :line 2 :character 6 :new_name "yell" :apply true} 70000)
                      "applied")))
      (is (= {"ok" true "reported" 0 "queued" false}
             (client/request! (:socket proxy) {:op "bash-finished" :tool_use_id "r1" :bash_edit_diff nil} 5000))
          "the rename's own writes are not reported a second time"))
    (testing "a command that renames and then edits a touched file again"
      (configure! proxy {"replies" {"textDocument/rename" (var-rename-edit proxy)}})
      (spit (str (fs/path (:project proxy) "src/live/util.clj")) "(ns live.util)\n\n(defn shout [s]\n  (str s \"!\"))\n")
      (spit (str (fs/path (:project proxy) "src/live/core.clj")) "(ns live.core\n  (:require [live.util :as util]))\n\n(defn greet [n]\n  (util/shout n))\n")
      (Thread/sleep 1100)
      (is (= {"ok" true} (client/request! (:socket proxy) {:op "bash-started" :tool_use_id "r2"} 5000)))
      (is (true? (get (client/request! (:socket proxy) {:op "rename" :file (str (fs/path (:project proxy) "src/live/util.clj"))
                                                        :line 2 :character 6 :new_name "yell" :apply true} 70000)
                      "applied")))
      (spit (str (fs/path (:project proxy) "src/live/core.clj")) (str (text proxy "src/live/core.clj") "\n;; formatted\n"))
      (is (= {"ok" true "reported" 1 "queued" false}
             (client/request! (:socket proxy) {:op "bash-finished" :tool_use_id "r2" :bash_edit_diff nil} 5000))
          "the file edited after the rename is reported, the other one is not")
      (let [entries (mapv json/parse-string (str/split-lines (slurp (:log proxy))))
            sent (last (filter #(= "workspace/didChangeWatchedFiles" (get % "method")) entries))]
        (is (= [{"uri" (uri proxy "src/live/core.clj") "type" 2}] (get-in sent ["body" "params" "changes"])))))
    (is (= 0 (stop! proxy)))))

(deftest rename-waits-for-settled-analysis-test
  (let [proxy (start-proxy {:deadline-ms 1500})
        util (str (fs/path (:project proxy) "src/live/util.clj"))
        rename (fn [apply?] (client/request! (:socket proxy) {:op "rename" :file util :line 2 :character 6 :new_name "yell" :apply apply?} 10000))]
    (initialize! proxy)
    (testing "a rename waits for the analysis of a reported change"
      (configure! proxy {"progress" {"create_after_ms" 1100 "end_after_ms" 300 "title" "Analyzing external file changes"}
                         "replies" {"textDocument/rename" (var-rename-edit proxy)}})
      (client/request! (:socket proxy) {:op "changed" :files [(str (fs/path (:project proxy) "src/live/core.clj"))]} 5000)
      (let [t0 (System/currentTimeMillis)
            reply (rename false)]
        (is (true? (get reply "ok")))
        (is (<= 1200 (- (System/currentTimeMillis) t0)) "answered after the analysis ended")))
    (testing "an analysis still running past the deadline refuses the rename although the gate is open"
      (configure! proxy {"progress" {"create_after_ms" 1100 "end_after_ms" nil "title" "Analyzing external file changes"}})
      (client/request! (:socket proxy) {:op "changed" :files [(str (fs/path (:project proxy) "src/live/core.clj"))]} 5000)
      (Thread/sleep 2000)
      (is (= {"gate_open" true "settled" false "in_flight" true}
             (select-keys (client/request! (:socket proxy) {:op "status"} 5000) ["gate_open" "settled" "in_flight"])))
      (let [reply (rename true)]
        (is (false? (get reply "ok")))
        (is (str/includes? (get reply "error") "not confirmed"))
        (is (str/includes? (text proxy "src/live/util.clj") "shout") "nothing was written")))
    (is (= 0 (stop! proxy)))))

(deftest server-error-test
  (let [proxy (start-proxy)]
    (initialize! proxy)
    (configure! proxy {"replies" {"textDocument/rename" {"error" {"code" -32602 "message" "Can't rename - no definition found."}}}})
    (let [reply (client/request! (:socket proxy) {:op "rename" :file (str (fs/path (:project proxy) "src/live/util.clj"))
                                                  :line 0 :character 0 :new_name "x" :apply false} 10000)]
      (is (= false (get reply "ok")))
      (is (str/includes? (get reply "error") "no definition found")))
    (is (= 0 (stop! proxy)))))

(defn status! [proxy]
  (select-keys (client/request! (:socket proxy) {:op "status"} 5000)
               ["settled" "unconfirmed_paths" "in_flight" "gate_open"]))

(defn log-entries [proxy]
  (mapv json/parse-string (str/split-lines (slurp (:log proxy)))))

(defn sent-uris
  "The URI sets of every didChangeWatchedFiles the proxy sent, in order."
  [proxy]
  (->> (log-entries proxy)
       (filter #(= "workspace/didChangeWatchedFiles" (get % "method")))
       (mapv #(into #{} (map (fn [c] (get c "uri"))) (get-in % ["body" "params" "changes"])))))

(deftest stale-send-leaves-changes-unconfirmed-test
  (let [proxy (start-proxy {:deadline-ms 1300})
        core (str (fs/path (:project proxy) "src/live/core.clj"))
        util (str (fs/path (:project proxy) "src/live/util.clj"))]
    (initialize! proxy)
    (configure! proxy {"progress" {"create_after_ms" 1000 "end_after_ms" nil "title" "Analyzing external file changes"}
                       "replies" {"textDocument/rename" (var-rename-edit proxy)}})
    (testing "an analysis that never ends is dropped after ten deadlines; its changes stay unconfirmed"
      (client/request! (:socket proxy) {:op "changed" :files [core]} 5000)
      (Thread/sleep 15000)
      (is (= {"settled" false "unconfirmed_paths" 1 "in_flight" false "gate_open" true} (status! proxy)))
      (is (= "no-end" (get (first (filter #(= "send-dropped" (get % "event")) (log-entries proxy))) "reason"))))
    (testing "a later send that ends on its analysis carries and confirms them"
      (configure! proxy {"progress" {"create_after_ms" 1000 "end_after_ms" 100 "title" "Analyzing external file changes"}})
      (is (false? (get (client/request! (:socket proxy) {:op "changed" :files [util] :wait true} 10000) "timed_out")))
      (is (= #{(uri proxy "src/live/core.clj") (uri proxy "src/live/util.clj")} (last (sent-uris proxy)))
          "the unconfirmed path rode along")
      (is (= {"settled" true "unconfirmed_paths" 0 "in_flight" false "gate_open" true} (status! proxy)))
      (is (true? (get (client/request! (:socket proxy) {:op "rename" :file util :line 2 :character 6 :new_name "yell" :apply false} 10000) "ok"))))
    (is (= 0 (stop! proxy)))))

(deftest rename-resends-unconfirmed-changes-test
  (let [proxy (start-proxy {:deadline-ms 1300})
        core (str (fs/path (:project proxy) "src/live/core.clj"))
        util (str (fs/path (:project proxy) "src/live/util.clj"))
        rename (fn [] (client/request! (:socket proxy) {:op "rename" :file util :line 2 :character 6 :new_name "yell" :apply false} 10000))
        events (fn [] (into [] (comp (filter #(= "proxy" (get % "dir"))) (map #(get % "event"))) (log-entries proxy)))]
    (initialize! proxy)
    (configure! proxy {"progress" nil "replies" {"textDocument/rename" (var-rename-edit proxy)}})
    (testing "a send that gets no analysis by its deadline is dropped; its changes stay unconfirmed"
      (client/request! (:socket proxy) {:op "changed" :files [core]} 5000)
      (Thread/sleep 1700)
      (is (= {"settled" false "unconfirmed_paths" 1 "in_flight" false "gate_open" true} (status! proxy))))
    (testing "the rename re-sends them and is refused while they get no analysis"
      (let [t0 (System/currentTimeMillis)
            reply (rename)]
        (is (false? (get reply "ok")))
        (is (str/includes? (get reply "error") "not confirmed"))
        (is (<= 1200 (- (System/currentTimeMillis) t0)) "waited for the re-send's deadline")
        (is (= 2 (count (filter #{"send-dropped"} (events)))))
        (is (= #{(uri proxy "src/live/core.clj")} (last (sent-uris proxy))))
        (is (= {"settled" false "unconfirmed_paths" 1 "in_flight" false "gate_open" true} (status! proxy)))))
    (testing "once the re-sent analysis ends, the rename proceeds"
      (configure! proxy {"progress" {"create_after_ms" 1000 "end_after_ms" 100 "title" "Analyzing external file changes"}})
      (let [t0 (System/currentTimeMillis)
            reply (rename)]
        (is (true? (get reply "ok")))
        (is (<= 1100 (- (System/currentTimeMillis) t0)) "answered after the re-sent analysis ended")
        (is (= 2 (count (filter #{"resend-unconfirmed"} (events)))))
        (is (= {"settled" true "unconfirmed_paths" 0 "in_flight" false "gate_open" true} (status! proxy)))))
    (is (= 0 (stop! proxy)))))

(let [{:keys [fail error]} (run-tests 'rename-test)]
  (shutdown-agents)
  (System/exit (+ fail error)))

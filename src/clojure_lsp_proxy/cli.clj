(ns clojure-lsp-proxy.cli
  "Command line over the control socket:

    clojure-lsp-proxy status
    clojure-lsp-proxy changed [--wait] <path>...
    clojure-lsp-proxy rename <file> <line> <col> <new-name> [--apply]
    clojure-lsp-proxy rename --symbol <ns/name or ns> <new-name> [--apply]
    clojure-lsp-proxy format <file> [--apply]

  `rename` and `format` print the edits clojure-lsp proposes and change
  nothing; with `--apply` the proxy applies them and tells every other
  proxy of the project about the touched files. Lines and columns are
  one-based, as editors show them.

  Proxies are found under `CLAUDE_PROJECT_DIR` when it is set (Claude Code
  sets it for its Bash tool); otherwise under the current directory and
  each of its parents up to the git top level, since a proxy's project root
  is the directory its Claude Code session started in. Every proxy found
  is addressed; one JSON line per proxy is printed. Exit status 1 when no
  proxy answered or one replied with an error, 2 on usage errors."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure-lsp-proxy.client :as client]))

(def request-timeout-ms 5000)

(def wait-timeout-ms
  "Long enough for two send deadlines: a report queued behind an in-flight
  send opens the gate only after both."
  120000)

(defn candidate-roots
  "The project roots whose proxies the command addresses, see the
  namespace docstring."
  []
  (if-let [dir (System/getenv "CLAUDE_PROJECT_DIR")]
    [(str (fs/canonicalize dir))]
    (let [cwd (fs/canonicalize (fs/cwd))
          {:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                      "git" "rev-parse" "--show-toplevel")
          top (when (zero? exit) (fs/canonicalize (str/trim out)))]
      (->> (iterate fs/parent cwd)
           (take-while some?)
           (take-while #(or (nil? top) (fs/starts-with? % top)))
           (mapv str)))))

(defn- usage []
  (binding [*out* *err*]
    (println "usage: clojure-lsp-proxy status")
    (println "       clojure-lsp-proxy changed [--wait] <path>...")
    (println "       clojure-lsp-proxy rename <file> <line> <col> <new-name> [--apply]")
    (println "       clojure-lsp-proxy rename --symbol <ns/name or ns> <new-name> [--apply]")
    (println "       clojure-lsp-proxy format <file> [--apply]")))

(defn- shown-path
  "A file URI as a path relative to the current directory."
  [uri]
  (str (fs/relativize (fs/cwd) (java.net.URI. uri))))

(defn- print-edit
  "The edit as people read it: each file with its edits, then the file
  operations, then the summary."
  [{:strs [edit summary applied touched]}]
  (doseq [{:strs [kind textDocument edits oldUri newUri uri]} (get edit "documentChanges")]
    (case kind
      nil (do (println (shown-path (get textDocument "uri")))
              (doseq [{:strs [range newText]} (sort-by #(get-in % ["range" "start" "line"]) edits)]
                (println (format "  %d:%d-%d:%d -> %s"
                                 (inc (get-in range ["start" "line"])) (inc (get-in range ["start" "character"]))
                                 (inc (get-in range ["end" "line"])) (inc (get-in range ["end" "character"]))
                                 (pr-str newText)))))
      "rename" (println "rename" (shown-path oldUri) "->" (shown-path newUri))
      (println kind (shown-path uri))))
  (println (str (if applied "applied: " "dry run: ")
                (get summary "edits") " edits in " (get summary "files") " files, "
                (get summary "renamed_files") " files renamed"
                (when applied (str "; " (count touched) " paths reported")))))

(defn- edit-command
  "Sends an edit-producing request (rename, format) to the first proxy
  that answers, prints the edit and, when it was applied, tells the other
  proxies of the project about the touched files."
  [roots request]
  (let [sockets (mapcat client/sockets roots)]
    (if (empty? sockets)
      (do (binding [*out* *err*]
            (println "clojure-lsp-proxy: no proxy is running for" (str/join ", " roots)))
          1)
      (let [[answering reply] (some (fn [socket]
                                      (when-let [reply (client/request! socket request wait-timeout-ms)]
                                        [socket reply]))
                                    sockets)]
        (cond
          (nil? reply) (do (binding [*out* *err*] (println "clojure-lsp-proxy: no proxy answered")) 1)
          (not (get reply "ok")) (do (binding [*out* *err*] (println "clojure-lsp-proxy:" (get reply "error"))) 1)
          :else (do (print-edit reply)
                    ;; other sessions' servers must re-read the touched files too
                    (when (and (get reply "applied") (seq (get reply "touched")))
                      (doseq [socket (remove #{answering} sockets)]
                        (client/request! socket {:op "changed" :files (get reply "touched")} request-timeout-ms)))
                    0))))))

(defn- rename-request [args]
  (let [apply? (boolean (some #{"--apply"} args))
        args (vec (remove #{"--apply"} args))]
    (if (= "--symbol" (first args))
      (let [[_ symbol new-name] args]
        (when (and symbol new-name)
          {:op "rename" :symbol symbol :new_name new-name :apply apply?}))
      (let [[file line col new-name] args
            line (some-> line parse-long)
            col (some-> col parse-long)]
        (when (and file line col new-name (pos? line) (pos? col))
          {:op "rename" :file (str (fs/absolutize file)) :line (dec line) :character (dec col)
           :new_name new-name :apply apply?})))))

(defn- format-request [args]
  (let [apply? (boolean (some #{"--apply"} args))
        [file & more] (remove #{"--apply"} args)]
    (when (and file (empty? more))
      {:op "format" :file (str (fs/absolutize file)) :apply apply?})))

(defn- run [roots request timeout-ms]
  (let [results (into [] (mapcat #(client/request-all! % request timeout-ms)) roots)]
    (doseq [{:keys [socket reply]} results]
      (println (json/generate-string {:socket socket :reply reply})))
    (cond
      (empty? results) (do (binding [*out* *err*]
                             (println "clojure-lsp-proxy: no proxy is running for" (str/join ", " roots)))
                           1)
      (not-every? #(get-in % [:reply "ok"]) results) 1
      :else 0)))

(defn -main [& [command & args]]
  (let [roots (candidate-roots)]
    (System/exit
     (case command
       "status" (run roots {:op "status"} request-timeout-ms)
       "changed" (let [wait? (boolean (some #{"--wait"} args))
                       paths (remove #{"--wait"} args)]
                   (if (empty? paths)
                     (do (usage) 2)
                     (run roots
                          {:op "changed"
                           :files (mapv #(str (fs/absolutize %)) paths)
                           :wait wait?}
                          (if wait? wait-timeout-ms request-timeout-ms))))
       "rename" (if-let [request (rename-request args)]
                  (edit-command roots request)
                  (do (usage) 2))
       "format" (if-let [request (format-request args)]
                  (edit-command roots request)
                  (do (usage) 2))
       (do (usage) 2)))))

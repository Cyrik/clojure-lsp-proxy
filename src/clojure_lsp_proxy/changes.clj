(ns clojure-lsp-proxy.changes
  "Turning changed paths into a `workspace/didChangeWatchedFiles` report
  (contract C4). Only paths clojure-lsp would analyze are reported: its
  watched extensions, inside the project root, outside the source paths it
  ignores by default. A path clojure-lsp drops would produce no analysis
  batch, and the gate would then wait for its deadline."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def watched-extensions
  "From clojure-lsp's known-files-pattern."
  #{"clj" "cljs" "cljc" "cljd" "edn" "bb" "clj_kondo"})

(def default-ignore-regexes
  "clojure-lsp's default `:source-paths-ignore-regex`."
  ["target.*"])

(defn- configured-ignore-regexes
  "`:source-paths-ignore-regex` from the config file at `path`, nil when
  the file is missing or unreadable."
  [path]
  (when (fs/exists? path)
    (try
      (:source-paths-ignore-regex (edn/read-string (slurp (str path))))
      (catch Exception _ nil))))

(defn ignored-source-path-regexes
  "The regexes clojure-lsp matches against the path relative to the project
  root to skip a file: the project's `.lsp/config.edn`, else the user's
  `config.edn` under `~/.config/clojure-lsp` or `~/.clojure-lsp`, else the
  default. A path they match would be dropped before any analysis batch,
  and the gate would then wait for its deadline."
  [root]
  (mapv re-pattern
        (or (configured-ignore-regexes (fs/path root ".lsp" "config.edn"))
            (configured-ignore-regexes (fs/path (fs/xdg-config-home) "clojure-lsp" "config.edn"))
            (configured-ignore-regexes (fs/path (fs/home) ".clojure-lsp" "config.edn"))
            default-ignore-regexes)))

(def file-change-type
  "LSP FileChangeType values."
  {:changed 2 :deleted 3})

(defn watched? [path]
  (contains? watched-extensions (fs/extension path)))

(defn- relative-to [root path]
  (when (str/starts-with? path (str root "/"))
    (subs path (inc (count root)))))

(defn analyzable?
  "Whether clojure-lsp would analyze `path` (absolute) for `root`, given
  the `ignore-regexes` in force."
  [root ignore-regexes path]
  (boolean
   (when-let [relative (relative-to root path)]
     (and (watched? path)
          (not-any? #(re-matches % relative) ignore-regexes)))))

(defn real-path
  "The absolute path with symlinks resolved as far as it exists."
  [path]
  (str (fs/canonicalize path)))

(defn path->uri [path]
  (str (.toUri (fs/path path))))

(defn report-for-paths
  "The report for `paths` (absolute or relative to `root`): a map of file
  URI to FileChangeType, `deleted` for a path that no longer exists and
  `changed` otherwise. Paths clojure-lsp would not analyze are left out."
  [root paths]
  (let [ignore-regexes (ignored-source-path-regexes root)]
    (into {}
          (comp (map #(real-path (if (fs/absolute? %) % (fs/path root %))))
                (filter #(analyzable? root ignore-regexes %))
                (map (fn [path]
                       [(path->uri path)
                        (file-change-type (if (fs/exists? path) :changed :deleted))])))
          paths)))

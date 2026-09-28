(ns clojure-lsp-proxy.project
  "Where a proxy instance finds its project and keeps its per-project files."
  (:require [babashka.fs :as fs])
  (:import [java.security MessageDigest]))

(defn root
  "The project root as an absolute path with symlinks resolved: the
  `CLAUDE_PROJECT_DIR` Claude Code sets for plugin LSP servers and hooks,
  else the current directory."
  []
  (str (fs/canonicalize (or (System/getenv "CLAUDE_PROJECT_DIR") (fs/cwd)))))

(defn- sha1-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn state-dir
  "Directory holding the logs and sockets of every proxy for `project-root`:
  `~/.cache/clojure-lsp-proxy/<first 20 hex digits of the root's sha1>`.
  Kept short because a Unix socket path is limited to about 100 bytes."
  [project-root]
  (str (fs/path (fs/home) ".cache" "clojure-lsp-proxy" (subs (sha1-hex project-root) 0 20))))

(ns clojure-lsp-proxy.log
  "JSONL log of everything the proxy sees and does: one object per line,
  stamped with `t` (local wall-clock time) and `ms` (epoch milliseconds).
  Message entries carry `dir` (\"c->s\", \"s->c\"), `method`, `id` and the
  full `body`; the proxy's own entries use `dir` \"proxy\" and an `event`.
  Writing never throws: the log must not stop the proxy from making
  progress, so a failed write is reported on stderr once and dropped."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.io IOException Writer]))

(defn open
  "Opens `path` for appending, creating its directories. Returns the log."
  [path]
  (fs/create-dirs (fs/parent path))
  {:path path
   :writer (io/writer (io/file path) :append true)
   :lock (Object.)
   :failed (atom false)})

(defn write!
  "Appends `entry` as one JSON line, flushed so that the file can be tailed."
  [{:keys [^Writer writer lock failed]} entry]
  (let [line (json/generate-string (merge {:t (str (java.time.LocalDateTime/now))
                                           :ms (System/currentTimeMillis)}
                                          entry))]
    (locking lock
      (try
        (.write writer line)
        (.write writer "\n")
        (.flush writer)
        (catch IOException e
          (when (compare-and-set! failed false true)
            (binding [*out* *err*]
              (println "clojure-lsp-proxy: log write failed, further entries are dropped:" (str e)))))))))

(defn close! [{:keys [^Writer writer lock]}]
  (locking lock
    (try
      (.close writer)
      (catch IOException _ nil))))

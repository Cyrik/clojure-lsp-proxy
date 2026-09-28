(ns clojure-lsp-proxy.client
  "Talking to running proxies over their control sockets (contract C5):
  one JSON object per line each way, one request per connection. Every
  socket in the project's state directory is addressed, since one proxy
  runs per Claude Code session; a socket whose connection is refused
  belongs to a dead proxy and is deleted."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure-lsp-proxy.project :as project])
  (:import [java.io BufferedReader InputStreamReader IOException OutputStreamWriter]
           [java.net UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn sockets
  "Socket paths of the proxies for `project-root`, oldest pid first."
  [project-root]
  (let [dir (project/state-dir project-root)]
    (when (fs/exists? dir)
      (->> (fs/glob dir "*.sock")
           (map str)
           (sort-by #(parse-long (fs/strip-ext (fs/file-name %))))))))

(defn request!
  "Sends `request` to the proxy at `socket-path` and returns its reply, or
  nil when the proxy is gone (its socket is then removed) or does not
  answer within `timeout-ms`."
  [socket-path request timeout-ms]
  (let [channel (try
                  (SocketChannel/open (UnixDomainSocketAddress/of socket-path))
                  (catch IOException _
                    (fs/delete-if-exists socket-path)
                    nil))]
    (when channel
      (with-open [^SocketChannel channel channel]
        (let [writer (OutputStreamWriter. (Channels/newOutputStream channel) "UTF-8")
              reader (BufferedReader. (InputStreamReader. (Channels/newInputStream channel) "UTF-8"))
              reply (future
                      (.write writer (json/generate-string request))
                      (.write writer "\n")
                      (.flush writer)
                      (some-> (.readLine reader) json/parse-string))
              result (deref reply timeout-ms ::timeout)]
          (when (= ::timeout result)
            (.close channel))
          (when (not= ::timeout result)
            result))))))

(defn request-all!
  "Sends `request` to every proxy of `project-root`. Returns a vector of
  `{:socket path :reply reply-or-nil}` for the sockets that existed."
  [project-root request timeout-ms]
  (mapv (fn [socket-path]
          {:socket socket-path
           :reply (request! socket-path request timeout-ms)})
        (sockets project-root)))

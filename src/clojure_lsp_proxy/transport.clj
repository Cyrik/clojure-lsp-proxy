(ns clojure-lsp-proxy.transport
  "Writing messages to either side and logging them. The proxy map carries
  `:client-out`/`:client-lock` and `:server-in`/`:server-lock`; every write
  to a side takes that side's lock so that threads never interleave frames."
  (:require [clojure.string :as str]
            [clojure-lsp-proxy.framing :as framing]
            [clojure-lsp-proxy.log :as log])
  (:import [java.io IOException OutputStream]))

(defn log-event! [{:keys [log]} event & {:as fields}]
  (log/write! log (merge {:dir "proxy" :event event} fields)))

(defn log-message!
  "Logs one message. `dir` names the sender and the receiver: c (client),
  s (server) or p (the proxy itself), as in \"c->s\" or \"p->s\". Extra
  fields, such as how long a message was held, go after the message."
  [{:keys [log]} dir msg & {:as fields}]
  (log/write! log (cond-> (merge {:dir dir} fields {:body msg})
                    (contains? msg "method") (assoc :method (get msg "method"))
                    (contains? msg "id") (assoc :id (get msg "id")))))

(defn warn! [& parts]
  (binding [*out* *err*]
    (println "clojure-lsp-proxy:" (apply str (interpose " " parts)))))

(defn- write-framed!
  "Writes one message under `lock`. A closed server pipe is logged, not
  thrown: the server reader notices the dead peer and drives the exit.
  (Client stdout is a PrintStream, which swallows write errors itself; a
  gone client shows up as EOF on stdin.)"
  [proxy lock ^OutputStream out target ^bytes body]
  (try
    (locking lock
      (framing/write-message out body))
    (catch IOException e
      (log-event! proxy "write-failed" :target target :error (str e)))))

(defn send-to-server! [{:keys [server-in server-lock] :as proxy} body]
  (write-framed! proxy server-lock server-in "server" body))

(defn send-to-client! [{:keys [client-out client-lock] :as proxy} body]
  (write-framed! proxy client-lock client-out "client" body))

(defn send-own-message-to-server!
  "Sends a message the proxy itself composed, logged as p->s."
  [proxy message]
  (log-message! proxy "p->s" message)
  (send-to-server! proxy (framing/encode message)))

(defn send-own-message-to-client!
  "Sends a message the proxy itself composed, logged as p->c."
  [proxy message]
  (log-message! proxy "p->c" message)
  (send-to-client! proxy (framing/encode message)))

(def own-request-id-prefix
  "Ids of the requests the proxy itself sends to the server; their
  responses are consumed by the proxy, never forwarded."
  "clojure-lsp-proxy/")

(defn own-response?
  "Whether `msg` answers a request the proxy sent itself."
  [msg]
  (let [id (get msg "id")]
    (and (string? id) (str/starts-with? id own-request-id-prefix))))

(defn request-server!
  "Sends `method` with `params` to the server as the proxy's own request
  and returns a promise of the response message (result or error)."
  [{:keys [state] :as proxy} method params]
  (let [id (str own-request-id-prefix method "/" (swap! (:own-request-counter proxy) inc))
        response (promise)]
    (swap! state assoc-in [:own-requests id] response)
    (send-own-message-to-server! proxy (cond-> {"jsonrpc" "2.0" "id" id "method" method}
                                         (some? params) (assoc "params" params)))
    response))

(defn deliver-own-response!
  "Hands the server's response to the waiting `request-server!` caller."
  [{:keys [state]} msg]
  (let [id (get msg "id")
        response (get-in @state [:own-requests id])]
    (swap! state update :own-requests dissoc id)
    (some-> response (deliver msg))))

(def request-timeout-ms 30000)

(defn request!
  "The result of the proxy's own request to the server, waited for.
  Throws with the server's error, or when no answer arrives within
  `request-timeout-ms`."
  [proxy method params]
  (let [response (deref (request-server! proxy method params) request-timeout-ms ::timeout)]
    (cond
      (= ::timeout response)
      (throw (ex-info (str method " got no answer within " request-timeout-ms " ms") {:method method}))

      (get response "error")
      (throw (ex-info (str method " failed: " (get-in response ["error" "message"]))
                      {:method method :error (get response "error")}))

      :else (get response "result"))))

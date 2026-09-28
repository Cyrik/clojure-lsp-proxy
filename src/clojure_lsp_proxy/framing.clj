(ns clojure-lsp-proxy.framing
  "Content-Length framing of JSON-RPC messages, as LSP uses it over stdio.

  A framed message is a block of `Name: value` header lines, each ended by
  CRLF, then an empty line, then a body of exactly `Content-Length` bytes.
  Bodies are handled as byte arrays so that a proxy can forward them
  unchanged; `parse` and `encode` convert between body bytes and JSON data
  with string keys."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream OutputStream]))

(def ^:private header-block-end (mapv int [\return \newline \return \newline]))

(defn- read-header-block
  "Reads bytes up to and including the empty line that ends the headers.
  Returns nil when the stream is at EOF before the first byte."
  [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [tail []]
      (let [b (.read in)]
        (if (neg? b)
          (when (pos? (.size buf))
            (throw (ex-info "EOF inside message headers" {:partial (.toString buf "US-ASCII")})))
          (let [tail (conj (if (= 4 (count tail)) (subvec tail 1) tail) b)]
            (.write buf b)
            (if (= tail header-block-end)
              (.toString buf "US-ASCII")
              (recur tail))))))))

(defn- parse-headers
  "Header names lower-cased, values trimmed."
  [^String block]
  (into {}
        (for [line (str/split-lines block)
              :when (not (str/blank? line))
              :let [[header-name value] (str/split line #":" 2)]]
          [(str/lower-case (str/trim header-name)) (str/trim (or value ""))])))

(defn- read-fully [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (= off n)
        buf
        (let [k (.read in buf off (- n off))]
          (when (neg? k)
            (throw (ex-info "EOF inside message body" {:expected n :read off})))
          (recur (+ off k)))))))

(defn read-message
  "Reads one framed message from `in`. Returns `{:headers {...} :body bytes}`
  with header names lower-cased, or nil when the stream ends between
  messages. Throws when the stream ends inside a message or the headers
  carry no Content-Length."
  [^InputStream in]
  (when-let [block (read-header-block in)]
    (let [headers (parse-headers block)
          length (some-> (get headers "content-length") parse-long)]
      (when-not length
        (throw (ex-info "Message headers carry no Content-Length" {:headers headers})))
      {:headers headers :body (read-fully in length)})))

(defn parse
  "The JSON body as data with string keys."
  [^bytes body]
  (json/parse-string (String. body "UTF-8")))

(defn encode
  "JSON body bytes for `message`, a map with string or keyword keys."
  ^bytes [message]
  (.getBytes (json/generate-string message) "UTF-8"))

(defn write-message
  "Writes `body` as one framed message and flushes."
  [^OutputStream out ^bytes body]
  (.write out (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "US-ASCII"))
  (.write out body)
  (.flush out))

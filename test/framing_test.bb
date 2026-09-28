#!/usr/bin/env bb

(ns framing-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [clojure-lsp-proxy.framing :as framing])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream SequenceInputStream]))

(defn framed-bytes [message]
  (let [out (ByteArrayOutputStream.)]
    (framing/write-message out (framing/encode message))
    (.toByteArray out)))

(defn concat-bytes [& arrays]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes a arrays] (.write out a))
    (.toByteArray out)))

(defn stream [^bytes bs] (ByteArrayInputStream. bs))

(defn split-stream
  "A stream that hands out `bs` in pieces of at most `n` bytes per read,
  like a pipe whose writer is slower than its reader."
  [^bytes bs n]
  (let [pieces (map (fn [[from to]] (stream (java.util.Arrays/copyOfRange bs (int from) (int to))))
                    (partition 2 1 (concat (range 0 (alength bs) n) [(alength bs)])))]
    (SequenceInputStream. (java.util.Collections/enumeration pieces))))

(deftest round-trip-test
  (let [message {"jsonrpc" "2.0" "id" 1 "method" "textDocument/definition" "params" {"text" "λ → ✓"}}
        {:keys [headers body]} (framing/read-message (stream (framed-bytes message)))]
    (is (= message (framing/parse body)))
    (is (= (str (alength (framing/encode message))) (get headers "content-length")))
    (testing "the length counts UTF-8 bytes, not characters"
      (is (> (alength (framing/encode message)) (count (str message)))))))

(deftest several-messages-in-one-buffer-test
  (let [in (stream (concat-bytes (framed-bytes {"id" 1}) (framed-bytes {"id" 2}) (framed-bytes {"method" "n"})))]
    (is (= {"id" 1} (framing/parse (:body (framing/read-message in)))))
    (is (= {"id" 2} (framing/parse (:body (framing/read-message in)))))
    (is (= {"method" "n"} (framing/parse (:body (framing/read-message in)))))
    (is (nil? (framing/read-message in)) "a clean EOF between messages reads as nil")))

(deftest body-split-across-reads-test
  (let [message {"id" 7 "params" {"text" (apply str (repeat 500 "λx "))}}
        bs (concat-bytes (framed-bytes message) (framed-bytes {"id" 8}))]
    (doseq [n [1 3 7 64 1000]]
      (let [in (split-stream bs n)]
        (is (= message (framing/parse (:body (framing/read-message in)))) (str "pieces of " n))
        (is (= {"id" 8} (framing/parse (:body (framing/read-message in)))))))))

(deftest extra-headers-test
  (let [body (framing/encode {"id" 3})
        bs (concat-bytes (.getBytes (str "content-type: application/vscode-jsonrpc; charset=utf-8\r\n"
                                         "Content-Length: " (alength body) "\r\n\r\n")
                                    "US-ASCII")
                         body)
        {:keys [headers] :as message} (framing/read-message (stream bs))]
    (is (= {"id" 3} (framing/parse (:body message))))
    (is (= "application/vscode-jsonrpc; charset=utf-8" (get headers "content-type")))))

(deftest malformed-input-test
  (testing "EOF inside the headers"
    (is (thrown? clojure.lang.ExceptionInfo
                 (framing/read-message (stream (.getBytes "Content-Length: 5\r\n" "US-ASCII"))))))
  (testing "EOF inside the body"
    (is (thrown? clojure.lang.ExceptionInfo
                 (framing/read-message (stream (.getBytes "Content-Length: 50\r\n\r\n{\"id\":1}" "US-ASCII"))))))
  (testing "no Content-Length"
    (is (thrown? clojure.lang.ExceptionInfo
                 (framing/read-message (stream (.getBytes "Content-Type: x\r\n\r\n{}" "US-ASCII")))))))

(let [{:keys [fail error]} (run-tests 'framing-test)]
  (System/exit (+ fail error)))

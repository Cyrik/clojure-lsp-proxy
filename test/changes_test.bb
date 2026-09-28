#!/usr/bin/env bb

(ns changes-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-lsp-proxy.changes :as changes]))

(defn write! [root path]
  (let [file (fs/path root path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) "x\n")))

(deftest report-for-paths-test
  (let [root (str (fs/canonicalize (fs/create-temp-dir {:prefix "clojure-lsp-proxy-changes"})))]
    (try
      (doseq [path ["src/a.clj" "src/b.cljs" "src/c.cljc" "src/d.edn" "src/e.bb" "src/f.cljd" "src/x.clj_kondo"
                    "src/with space.clj" "target/gen.clj" "notes.txt" "src/plain"]]
        (write! root path))
      (fs/create-sym-link (fs/path root "link.clj") (fs/path root "src" "a.clj"))
      (testing "watched extensions, relative and absolute paths, deletions"
        (is (= {(str "file://" root "/src/a.clj") 2
                (str "file://" root "/src/b.cljs") 2
                (str "file://" root "/src/c.cljc") 2
                (str "file://" root "/src/d.edn") 2
                (str "file://" root "/src/e.bb") 2
                (str "file://" root "/src/f.cljd") 2
                (str "file://" root "/src/x.clj_kondo") 2
                (str "file://" root "/src/gone.clj") 3}
               (changes/report-for-paths root ["src/a.clj" (str root "/src/b.cljs") "src/c.cljc" "src/d.edn"
                                               "src/e.bb" "src/f.cljd" "src/x.clj_kondo" "src/gone.clj"]))))
      (testing "paths clojure-lsp would not analyze are left out"
        (is (= {} (changes/report-for-paths root ["notes.txt" "src/plain" "target/gen.clj" "/tmp/elsewhere.clj"
                                                  "src/nope.txt"]))))
      (testing "the project's clojure-lsp config can widen the ignored source paths"
        (fs/create-dirs (fs/path root ".lsp"))
        (spit (str (fs/path root ".lsp" "config.edn")) "{:source-paths-ignore-regex [\"target.*\" \"src/gen.*\"]}")
        (write! root "src/gen/x.clj")
        (is (= {} (changes/report-for-paths root ["src/gen/x.clj" "target/gen.clj"])))
        (is (= {(str "file://" root "/src/a.clj") 2} (changes/report-for-paths root ["src/a.clj"])))
        (fs/delete-tree (fs/path root ".lsp")))
      (testing "symlinks resolve to the real path and spaces are encoded"
        (is (= {(str "file://" root "/src/a.clj") 2} (changes/report-for-paths root ["link.clj"])))
        (is (= {(str "file://" root "/src/with%20space.clj") 2} (changes/report-for-paths root ["src/with space.clj"]))))
      (finally
        (fs/delete-tree root)))))

(let [{:keys [fail error]} (run-tests 'changes-test)]
  (System/exit (+ fail error)))

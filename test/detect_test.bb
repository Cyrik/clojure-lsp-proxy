#!/usr/bin/env bb

(ns detect-test
  "Snapshot-and-check detection against temporary git repositories, one
  bracket per operation a shell command might perform."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure-lsp-proxy.detect :as detect]))

(defn git! [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(defn write! [root path content]
  (let [file (fs/path root path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) content)))

(defn with-temp-repo
  "A repository with committed files, one of them already dirty, and an
  ignored directory."
  [f]
  (let [root (str (fs/real-path (fs/create-temp-dir {:prefix "clojure-lsp-proxy-detect"})))]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (write! root ".gitignore" "ignored/\n")
      (write! root "src/a.clj" "(ns a)\n")
      (write! root "src/b.clj" "(ns b)\n")
      (write! root "src/gone.clj" "(ns gone)\n")
      (write! root "src/moved.clj" "(ns moved)\n")
      (write! root "src/dirty.clj" "(ns dirty)\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (write! root "src/dirty.clj" "(ns dirty) :edited-before-the-bracket\n")
      (f root)
      (finally
        (fs/delete-tree root)))))

(defn abs [root & paths]
  (into #{} (map #(str (fs/path root %))) paths))

(defn snapshot!
  "A snapshot taken in a later second than anything written so far, since
  a write in the snapshot's own second is reported either way."
  [root]
  (Thread/sleep 1100)
  (detect/snapshot root))

(defn check
  "Runs `f` inside a bracket and returns the changed paths."
  [root f]
  (let [snapshot (snapshot! root)]
    (f)
    (:paths (detect/changed-paths snapshot []))))

(deftest status-entries-test
  (let [nul (str (char 0))]
    (is (= [{:status " M" :path "a.txt"}
            {:status "??" :path "new file.clj"}
            {:status "R " :path "new.clj" :origin "old.clj"}
            {:status "A " :path "added.clj"}]
           (detect/status-entries
            (str " M a.txt" nul "?? new file.clj" nul "R  new.clj" nul "old.clj" nul "A  added.clj" nul))))
    (is (= [] (detect/status-entries "")))))

(deftest working-tree-operations-test
  (with-temp-repo
    (fn [root]
      (testing "a modification, a new file, a deletion, a move outside git and a git mv"
        (is (= (abs root "src/a.clj" "src/new.clj" "src/gone.clj" "src/moved.clj" "src/elsewhere.clj"
                    "src/b.clj" "src/renamed.clj")
               (check root (fn []
                             (write! root "src/a.clj" "(ns a) :edited\n")
                             (write! root "src/new.clj" "(ns new)\n")
                             (fs/delete (fs/path root "src/gone.clj"))
                             (fs/move (fs/path root "src/moved.clj") (fs/path root "src/elsewhere.clj"))
                             (git! root "mv" "src/b.clj" "src/renamed.clj")
                             (write! root "ignored/gen.clj" "(ns gen)\n"))))
            "every touched path, both names of each move, nothing ignored"))
      (testing "a file dirty before the bracket and untouched inside it is not reported"
        (is (= #{} (check root (fn [] nil)))))
      (testing "a dirty file rewritten inside the bracket is"
        (is (= (abs root "src/dirty.clj")
               (check root (fn [] (write! root "src/dirty.clj" "(ns dirty) :edited-again\n")))))))))

(deftest reverts-test
  (with-temp-repo
    (fn [root]
      (testing "git checkout -- <file> restores the committed text"
        (is (= (abs root "src/dirty.clj")
               (check root (fn [] (git! root "checkout" "--" "src/dirty.clj"))))))
      (testing "git stash reverts and git stash pop restores"
        (write! root "src/a.clj" "(ns a) :stashed\n")
        (is (= (abs root "src/a.clj") (check root (fn [] (git! root "stash" "-q")))))
        (is (= (abs root "src/a.clj") (check root (fn [] (git! root "stash" "pop" "-q")))))))))

(deftest commits-and-branches-test
  (with-temp-repo
    (fn [root]
      (testing "a commit inside the bracket reports what it touched, not the unchanged dirty file it committed"
        (is (= (abs root "src/c.clj")
               (check root (fn []
                             (write! root "src/c.clj" "(ns c)\n")
                             (git! root "add" "src/c.clj")
                             (git! root "commit" "-q" "-m" "c"))))))
      (testing "committing an already dirty file without rewriting it reports nothing"
        (is (= #{} (check root (fn [] (git! root "commit" "-q" "-am" "dirty"))))))
      (testing "a branch switch reports the files that differ, deletions included"
        (git! root "checkout" "-q" "-b" "feature")
        (write! root "src/a.clj" "(ns a) :feature\n")
        (write! root "src/feature-only.clj" "(ns feature-only)\n")
        (git! root "rm" "-q" "src/gone.clj")
        (git! root "add" "-A")
        (git! root "commit" "-q" "-m" "feature")
        (is (= (abs root "src/a.clj" "src/feature-only.clj" "src/gone.clj")
               (check root (fn [] (git! root "checkout" "-q" "main")))))
        (is (= (abs root "src/a.clj" "src/feature-only.clj" "src/gone.clj")
               (check root (fn [] (git! root "checkout" "-q" "feature")))))))))

(deftest staged-rename-test
  (with-temp-repo
    (fn [root]
      ;; a clean tree, so that reset --hard reverts the rename and nothing else
      (git! root "commit" "-q" "-am" "clean")
      (testing "git mv reports both names"
        (is (= (abs root "src/b.clj" "src/renamed.clj")
               (check root (fn [] (git! root "mv" "src/b.clj" "src/renamed.clj"))))))
      (testing "a rename that merely stays staged reports nothing"
        (is (= #{} (check root (fn [] nil)))))
      (testing "reverting it reports the restored origin and the removed destination"
        (is (= (abs root "src/b.clj" "src/renamed.clj")
               (check root (fn [] (git! root "reset" "-q" "--hard" "HEAD"))))))
      (testing "committing a staged rename reports nothing new"
        (git! root "mv" "src/b.clj" "src/renamed.clj")
        (is (= #{} (check root (fn [] (git! root "commit" "-q" "-m" "mv")))))))))

(deftest reported-state-test
  (with-temp-repo
    (fn [root]
      (let [path (str (fs/path root "src/a.clj"))
            reported (detect/reported-state path)]
        (testing "untouched since the report"
          (is (detect/as-reported? reported path)))
        (testing "rewritten with the same bytes: a write happened, so the analysis may have seen something else in between"
          (Thread/sleep 5)
          (spit path (slurp path))
          (is (not (detect/as-reported? reported path))))
        (testing "a different content"
          (spit path "(ns a) :other\n")
          (is (not (detect/as-reported? (detect/reported-state path) (str (fs/path root "src/b.clj"))))))
        (testing "a path reported as absent stays as reported while it is absent"
          (let [gone (str (fs/path root "src/nope.clj"))
                reported (detect/reported-state gone)]
            (is (= :absent (:state reported)))
            (is (detect/as-reported? reported gone))
            (spit gone "(ns nope)\n")
            (is (not (detect/as-reported? reported gone)))))))))

(deftest same-second-write-test
  (with-temp-repo
    (fn [root]
      (testing "a write in the same second as the snapshot is not missed"
        (let [snapshot (snapshot! root)]
          (write! root "src/a.clj" "(ns a) :at-once\n")
          (is (= (abs root "src/a.clj") (:paths (detect/changed-paths snapshot [])))))))))

(deftest reported-paths-and-fallback-test
  (with-temp-repo
    (fn [root]
      (testing "paths another source names are unioned in"
        (is (= (abs root "src/x.clj") (check root (fn [] (write! root "src/x.clj" "(ns x)\n")))))
        (let [snapshot (snapshot! root)]
          (write! root "src/x.clj" "(ns x) :again\n")
          (is (= (abs root "src/a.clj" "src/x.clj")
                 (:paths (detect/changed-paths snapshot [(str (fs/path root "src/a.clj"))])))))
        (is (= (abs root "src/a.clj")
               (:paths (detect/changed-paths (snapshot! root) [(str (fs/path root "src/a.clj"))])))))
      (testing "the fresh snapshot serves the next check"
        (let [{:keys [snapshot]} (detect/changed-paths (snapshot! root) [])]
          (Thread/sleep 1100)
          (write! root "src/b.clj" "(ns b) :later\n")
          (is (= (abs root "src/b.clj") (:paths (detect/changed-paths snapshot []))))))
      (testing "outside git only the reported paths remain"
        (let [dir (str (fs/create-temp-dir {:prefix "clojure-lsp-proxy-plain"}))]
          (try
            (is (nil? (detect/snapshot dir)))
            (is (= {:paths #{"/x/y.clj"} :snapshot nil} (detect/changed-paths nil ["/x/y.clj"])))
            (finally (fs/delete-tree dir))))))))

(let [{:keys [fail error]} (run-tests 'detect-test)]
  (System/exit (+ fail error)))

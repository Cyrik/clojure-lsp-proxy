(ns clojure-lsp-proxy.detect
  "Finding the files a shell command changed, bracketed by a snapshot
  taken before the command and a check after it (plan Decision 6).

  A snapshot records a marker time, the git status listing keyed by path
  and HEAD. The check compares a fresh listing and HEAD with the snapshot:
  paths new to the listing; paths in both listings whose file changed at
  or after the marker, or which vanished since; paths that left the
  listing because a stash, checkout or restore rewrote them, or because a
  deletion got committed; every path the commits between the two HEADs
  touched; and whatever Claude Code's own `bashEditDiff` names. The origin
  of a rename is listed as a deleted path, so the same rules report it
  when the rename happens, when it is reverted and not while it merely
  stays staged. The result is a set of absolute paths; whether each is a
  change or a deletion is decided by its existence when the report is
  built (`clojure-lsp-proxy.changes`).

  A path the proxy reported itself (an applied rename) is remembered with
  its content state, so that the bracket around the command can tell the
  reported write from a later one (`reported-state`, `as-reported?`).

  Adapted from rule-fairy's `rule-fairy.changes` (MIT, Lukas Domagala):
  the status parsing, the second-granularity time comparison and the
  checkout lookup, extended with rename origins, deletions and reverted
  files, which rule-fairy leaves out on purpose."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileTime]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def ^:private nul-pattern (re-pattern (str (char 0))))

(defn- git
  "Runs git in `dir` and returns stdout; throws unless the exit code is in
  `ok-exits`."
  ([dir args] (git dir args #{0}))
  ([dir args ok-exits]
   (let [{:keys [exit out err]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                                         "git" args)]
     (when-not (contains? ok-exits exit)
       (throw (ex-info (str "git " (first args) " failed: " (str/trim err)) {:exit exit :args args})))
     out)))

(defn status-entries
  "The entries of `git status --porcelain=v1 -z` output as maps of
  `:status`, `:path` and, for a rename or copy, `:origin`."
  [output]
  (loop [tokens (remove str/blank? (str/split output nul-pattern))
         entries []]
    (if-let [token (first tokens)]
      (let [status (subs token 0 2)
            path (subs token 3)
            renamed? (some #{\R \C} status)]
        (recur (if renamed? (nnext tokens) (next tokens))
               (conj entries (cond-> {:status status :path path}
                               renamed? (assoc :origin (second tokens))))))
      entries)))

(defn- changed-at-or-after?
  "Whether the file's content or inode changed at or after `marker-ms`,
  compared at second granularity so that a filesystem keeping whole
  seconds never hides an edit made in the marker's second. A move keeps
  the modification time, so the inode change time counts as well; a
  symlink is examined itself, not its target. False for a missing file."
  [^Path path marker-ms]
  (try
    (let [options (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
          mtime (.toMillis (Files/getLastModifiedTime path options))
          ctime (try
                  (.toMillis ^FileTime (Files/getAttribute path "unix:ctime" options))
                  (catch Exception _ mtime))]
      (>= (quot (max mtime ctime) 1000) (quot marker-ms 1000)))
    (catch java.io.IOException _ false)))

(defn locate-checkout
  "The checkout holding `dir` as `{:top real-path :head oid}`, the head
  nil before the first commit; nil outside git. One process answers both:
  before the first commit git echoes the unresolved `HEAD` and exits 128,
  outside git it prints nothing."
  [dir]
  (let [[top head] (str/split-lines (git dir ["rev-parse" "--show-toplevel" "HEAD"] #{0 128}))]
    (when-not (str/blank? top)
      {:top (str (fs/real-path top))
       :head (when (and head (re-matches #"[0-9a-f]+" head)) head)})))

(defn- listing
  "The status listing of the checkout at `top`, keyed by path. The origin
  of a rename gets an entry of its own with a deleted status (the copy's
  own status for a copy whose origin still exists), so that the rules in
  `listing-changes` treat it like any other path that is gone or comes
  back."
  [top]
  (let [entries (status-entries (git top ["status" "--porcelain=v1" "-z" "--untracked-files=all"]))
        origins (into {}
                      (keep (fn [{:keys [status origin]}]
                              (when origin
                                [origin (if (fs/exists? (fs/path top origin) {:nofollow-links true}) status "D ")])))
                      entries)]
    {:by-path (into origins (map (juxt :path :status)) entries)}))

(defn snapshot
  "The state of the checkout holding `dir` before a command: the marker
  time (taken before git runs, so a write made while it runs counts as
  after the marker), the listing and HEAD. Nil outside git."
  [dir]
  (let [marker (System/currentTimeMillis)]
    (when-let [{:keys [top head]} (locate-checkout dir)]
      (merge {:marker-ms marker :top top :head head}
             (listing top)))))

(defn- deleted-status? [status]
  (str/includes? status "D"))

(defn- listing-changes
  "Repository-relative paths that the two listings and the file times say
  changed since the snapshot."
  [{:keys [top marker-ms by-path]} {new-by-path :by-path}]
  (let [file (fn [path] (fs/path top path))
        exists? (fn [path] (fs/exists? (file path) {:nofollow-links true}))
        changed-since-marker? (fn [path] (changed-at-or-after? (file path) marker-ms))]
    (concat
     ;; new to the listing: written, created, deleted or renamed by the command
     (remove #(contains? by-path %) (keys new-by-path))
     ;; in both listings: rewritten since the marker, or gone since
     (for [[path status] new-by-path
           :let [old-status (get by-path path)]
           :when (and old-status
                      (if (exists? path)
                        (changed-since-marker? path)
                        (not (deleted-status? old-status))))]
       path)
     ;; left the listing: reverted to HEAD by stash, checkout or restore, or
     ;; a deletion committed (then already deleted before the snapshot)
     (for [[path old-status] by-path
           :when (and (not (contains? new-by-path path))
                      (if (exists? path)
                        (changed-since-marker? path)
                        (not (deleted-status? old-status))))]
       path))))

(defn- commit-changes
  "Repository-relative paths the commits between the two HEADs touched,
  renames as their old and new names, deletions included."
  [top old-head new-head]
  (when (and new-head (not= old-head new-head))
    (let [base (or old-head (str/trim (git top ["hash-object" "-t" "tree" "/dev/null"])))
          output (git top ["diff" "--name-status" "-z" "--no-renames" base new-head])]
      (->> (str/split output nul-pattern)
           (remove str/blank?)
           (partition 2)
           (map second)))))

(defn- file-state
  "The file's content fingerprint (SHA-1, hex) with its modification and
  inode change times in nanoseconds, or `{:state :absent}`. The times tell
  a write apart from the content it left behind: clojure-lsp reads a
  reported file only after its debounce, so a file written and restored
  in between was analyzed with the intermediate content although its
  bytes match."
  [path]
  (let [options (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        file (fs/path path)]
    (if (fs/exists? file {:nofollow-links true})
      (let [digest (.digest (MessageDigest/getInstance "SHA-1") (Files/readAllBytes file))]
        {:state (str/join (map #(format "%02x" %) digest))
         :mtime (.to (Files/getLastModifiedTime file options) TimeUnit/NANOSECONDS)
         :ctime (try
                  (.to ^FileTime (Files/getAttribute file "unix:ctime" options) TimeUnit/NANOSECONDS)
                  (catch Exception _ nil))})
      {:state :absent})))

(defn reported-state
  "What to remember about a path the proxy reports itself: when, and the
  file state (`file-state`) of the write it reports."
  [path]
  (assoc (file-state path) :at (System/currentTimeMillis)))

(defn as-reported?
  "Whether the path is still exactly as `reported-state` recorded it:
  same content and no write since."
  [reported path]
  (= (select-keys reported [:state :mtime :ctime]) (file-state path)))

(defn changed-paths
  "The absolute paths a command changed since the snapshot `before`, from
  the listing and HEAD comparison plus `reported-paths`, the absolute paths
  another source names (Claude Code's bashEditDiff). A nil `before`
  (outside git) yields only `reported-paths`. Also returns the fresh
  snapshot for the next check as `:snapshot`. A file written in the same
  second as `before` is reported even when the write preceded it; that is
  the price of never missing a write made in that second."
  [before reported-paths]
  (let [fresh (some-> before :top snapshot)
        ;; a path dirty before the bracket is decided by the listing rules
        ;; alone: committing it moves HEAD without touching the file
        dirty-before? #(contains? (:by-path before) %)
        paths (into (set reported-paths)
                    (when before
                      (map #(str (fs/path (:top before) %))
                           (concat (listing-changes before fresh)
                                   (remove dirty-before?
                                           (commit-changes (:top before) (:head before) (:head fresh)))))))]
    {:paths paths
     :snapshot fresh}))

(ns clojure-lsp-proxy.gate
  "Change injection and request gating (contracts C2 and C3).

  The proxy is the only sender of `workspace/didChangeWatchedFiles` and
  keeps one send outstanding: a send stays outstanding until clojure-lsp
  has finished the analysis batch it produced, and paths reported in the
  meantime are coalesced into the next send. From the moment a report is
  accepted until no send holds the gate and nothing is pending, the gate
  is closed and the client's requests and notifications are held in
  arrival order, so that a query never reaches clojure-lsp between a
  change and its analysis.

  The analysis batch of a send is observed through the work-done progress
  the proxy owns: a `window/workDoneProgress/create` at least 900 ms after
  the send (clojure-lsp debounces for 1000 ms), whose `begin` carries the
  analysis title, and whose `end` then arrives. Because clojure-lsp
  analyzes one batch at a time and the proxy sends the next batch only
  after the previous one ended, every analysis `create` belongs to the
  outstanding send.

  A deadline bounds every hold, because Claude Code never times out an
  LSP request: a held message is released at the latest one deadline
  after the gate closed, whatever the sends are doing. Each closure of the
  gate carries its own release timer, and a send's deadline releases as
  well; a message held after such a release closes the gate anew with a
  fresh bound. A send whose analysis had begun by its deadline stays
  outstanding until its `end` (or a much later stale timeout); a send for
  which no analysis began by then gets none, since nothing else was being
  analyzed, and is dropped. Reports that arrive before the client's
  `initialized` wait for it, since the server analyzes the whole project
  during `initialize` anyway.

  State, in three parts that move independently:

  - Sends. `:in-flight` is nil, a holding send (deadline not passed) or an
    expired send (deadline passed with its analysis begun, waiting for the
    `end`); `:pending` holds the paths coalesced for the next send;
    `:unconfirmed` the changes of dropped sends, which the next send
    carries until one ends on its analysis. The sends are idle when
    nothing is in flight or pending (`:idle`, a promise delivered at that
    moment) and settled when they are idle with nothing unconfirmed: every
    reported change analyzed, which is more than an open gate says.
  - Closure. `:gate-closed-ms` is nil (open) or the time the gate closed;
    `:held` the messages held since, `:gate-opened` the promise the
    release delivers. Whether a new message is held is decided by
    `closed?`: a holding send or pending paths. A closure ends only by a
    release, so it can outlive `closed?` (a deadline released it while
    sends remain) and the gate can be open with an expired send running.
  - Progress. `:progress-tokens` maps the tokens the proxy created to
    their begin titles.

  Transitions, each under the gate lock:

  - report: paths join `:pending`, or start a send when nothing is
    outstanding and the server is initialized; the gate closes if open;
    the sends leave idle.
  - client message while `closed?`: held; the gate closes if open.
  - `create` then `begin` with the analysis title, 900 ms or more after
    the send: the send owns the token. Earlier, or without a send, or
    for a send that already has a token: an anomaly, logged.
  - `end` of the owned token: the send ends and the unconfirmed changes it
    carried are cleared; pending paths start the next send, otherwise the
    sends go idle and a running closure is released.
  - send deadline: analysis begun, the send expires, the closure is
    released and a stale timer starts; not begun, the send is dropped: its
    changes become unconfirmed, then pending paths start the next send or
    the sends go idle.
  - closure deadline (one deadline plus slack after the close): released,
    if that closure is still running.
  - stale (ten deadlines): an expired send is dropped the same way.
  - resend (a rename finding the sends idle with unconfirmed changes): a
    send of those changes starts and the gate closes.
  - `initialized`: pending paths start a send.
  - `shutdown`: every send is forgotten, the sends go idle, a running
    closure is released.
  - release: held messages go to the server in order; `:gate-opened` gets
    true when `closed?` is false afterwards, false when sends remain.

  Every transition runs under the proxy's `:gate-lock`; `:state` changes
  only while holding it. Lock order when both are needed: `:gate-lock`
  before the transport locks. Pipe writes happen under the gate lock; that
  is safe because clojure-lsp keeps reading its stdin on its own thread
  whatever the proxy does."
  (:require [clojure-lsp-proxy.transport :as transport]))

(def analysis-title "Analyzing external file changes")

(def create-min-delay-ms
  "clojure-lsp debounces watched-file changes for 1000 ms before it
  creates the batch's progress, so a `create` sooner after a send cannot
  belong to that send's batch."
  900)

(def stale-send-deadlines
  "A send whose analysis began but never ended is dropped after this many
  deadlines, so that a lost `end` cannot block later sends for good."
  10)

(def closure-timer-slack-ms
  "A closure's own release timer fires this much after the send deadline
  it usually coincides with, so that the send's deadline handles the
  common case and the closure timer only covers closures that outlived
  their send."
  100)

(def request-cancelled-code -32800)
(def internal-error-code -32603)

(defn initial-state []
  {:progress-tokens {}
   :pending {}
   ;; the outstanding send: {:id :sent-ms :token :expired?}
   :in-flight nil
   :send-counter 0
   :held []
   ;; the current closure of the gate: when it closed, its release promise
   ;; and its id (for the closure's own release timer)
   :gate-closed-ms nil
   :gate-opened (doto (promise) (deliver true))
   :closure 0
   ;; the changes of dropped sends, {uri type}, until a send ends on its analysis
   :unconfirmed {}
   :idle (doto (promise) (deliver true))
   :server-ready? false
   :shutdown? false})

(defn- now [] (System/currentTimeMillis))

(defn- holding-send
  "The outstanding send while it still holds the gate, that is, before
  its deadline passed."
  [state]
  (let [send (:in-flight state)]
    (when (and send (not (:expired? send)))
      send)))

(defn closed? [state]
  (boolean (or (holding-send state) (seq (:pending state)))))

(defn- idle?
  "Whether no send is outstanding or pending."
  [state]
  (and (nil? (:in-flight state)) (empty? (:pending state))))

(defn settled?
  "Whether every change the proxy reported has been analyzed: the sends
  are idle and no dropped send left changes unconfirmed."
  [state]
  (and (idle? state) (empty? (:unconfirmed state))))

(defn snapshot
  "Gate figures for the `status` operation."
  [{:keys [state]}]
  (let [{:keys [held in-flight pending unconfirmed] :as current} @state]
    {:held_messages (count held)
     :gate_open (not (closed? current))
     :settled (settled? current)
     :in_flight (some? in-flight)
     :pending_paths (count pending)
     :unconfirmed_paths (count unconfirmed)}))

;;; sends

(declare start-send! forward!)

(defn- schedule!
  "Runs `f` under the gate lock after `delay-ms`."
  [{:keys [gate-lock]} delay-ms f]
  (future
    (Thread/sleep delay-ms)
    (locking gate-lock
      (f))))

(defn- release-held!
  "Forwards the held messages in arrival order and ends the current
  closure. Nothing happens when there is no closure. The closure's
  promise gets true only when the gate is really open afterwards; false
  means a deadline released the messages while sends are still pending."
  [{:keys [state] :as proxy} reason]
  (let [{:keys [held gate-closed-ms gate-opened]} @state
        t (now)]
    (when gate-closed-ms
      (doseq [{:keys [body msg held-ms]} held]
        (forward! proxy body msg :held-ms (- t held-ms)))
      (swap! state assoc :held [] :gate-closed-ms nil)
      (let [open? (not (closed? @state))]
        (transport/log-event! proxy "gate-open"
                              :reason reason
                              :closed-ms (- t gate-closed-ms)
                              :released (count held)
                              :still-pending (not open?))
        (deliver gate-opened open?)))))

(defn- settle!
  "Once the outstanding send is gone: starts the pending send, or marks
  the sends idle and opens the gate when it is still closed."
  [{:keys [state] :as proxy} reason]
  (let [{:keys [pending gate-closed-ms idle]} @state]
    (if (seq pending)
      (start-send! proxy pending)
      (do (deliver idle true)
          (when gate-closed-ms
            (release-held! proxy reason))))))

(defn- busy!
  "Renews the idle promise when the sends leave the idle state."
  [{:keys [state]}]
  (when (realized? (:idle @state))
    (swap! state assoc :idle (promise))))

(defn- outstanding? [state id]
  (= id (:id (:in-flight state))))

(defn- drop-send!
  "Forgets the outstanding send without an analysis end. Its changes stay
  unconfirmed: the next send carries them, and only a send ending on its
  analysis clears them."
  [{:keys [state] :as proxy} reason]
  (let [{:keys [in-flight]} @state]
    (apply transport/log-event! proxy "send-dropped"
           (cond-> [:send (:id in-flight) :reason reason :duration-ms (- (now) (:sent-ms in-flight))]
             (= "no-analysis" reason)
             (conj :hint "no analysis progress arrived; does the server report watched-file analysis (clojure-lsp master from 2026-10-05 on, a nightly or the next release; README, The server)?")))
    (swap! state #(-> %
                      (assoc :in-flight nil)
                      (update :unconfirmed merge (:changes in-flight))))))

(defn- drop-stale! [{:keys [state] :as proxy} id]
  (when (outstanding? @state id)
    (drop-send! proxy "no-end")
    (settle! proxy "stale")))

(defn- on-deadline! [{:keys [state send-deadline-ms] :as proxy} id]
  (let [{:keys [in-flight]} @state]
    (when (and (outstanding? @state id) (not (:expired? in-flight)))
      (if (:token in-flight)
        ;; a long analysis: stop holding, keep waiting for its end
        (do (swap! state assoc-in [:in-flight :expired?] true)
            (transport/log-event! proxy "send-deadline" :send id :analysis-running true)
            (release-held! proxy "deadline")
            (schedule! proxy (* stale-send-deadlines send-deadline-ms) #(drop-stale! proxy id)))
        ;; nothing was being analyzed, so no batch is coming for it
        (do (drop-send! proxy "no-analysis")
            (release-held! proxy "deadline")
            (settle! proxy "deadline"))))))

(defn- start-send!
  "Sends `uri->type` along with the unconfirmed changes of dropped sends."
  [{:keys [state send-deadline-ms] :as proxy} uri->type]
  (let [{:keys [send-counter unconfirmed]} @state
        id (inc send-counter)
        changes (merge unconfirmed uri->type)]
    (swap! state assoc
           :send-counter id
           :in-flight {:id id :sent-ms (now) :token nil :expired? false :changes changes}
           :pending {})
    (transport/send-own-message-to-server!
     proxy {"jsonrpc" "2.0"
            "method" "workspace/didChangeWatchedFiles"
            "params" {"changes" (mapv (fn [[uri type]] {"uri" uri "type" type}) changes)}})
    (transport/log-event! proxy "send-start" :send id :changes (count changes) :unconfirmed (count unconfirmed))
    (schedule! proxy send-deadline-ms #(on-deadline! proxy id))))

(defn- end-send! [{:keys [state] :as proxy} reason]
  (let [{:keys [in-flight]} @state]
    (transport/log-event! proxy "send-end"
                          :send (:id in-flight)
                          :reason reason
                          :duration-ms (- (now) (:sent-ms in-flight))
                          :token (:token in-flight)
                          :after-deadline (:expired? in-flight))
    ;; the send carried every unconfirmed change, so this analysis covered them
    (swap! state assoc :in-flight nil :unconfirmed {})
    (settle! proxy reason)))

(defn- on-closure-deadline! [{:keys [state] :as proxy} closure]
  (when (= closure (:closure @state))
    (release-held! proxy "deadline")))

(defn- close-gate!
  "Starts a closure: from now, client messages are held, at most one
  deadline (plus the slack) whatever the sends do."
  [{:keys [state send-deadline-ms] :as proxy}]
  (let [closure (inc (:closure @state))]
    (swap! state assoc :gate-closed-ms (now) :gate-opened (promise) :closure closure)
    (transport/log-event! proxy "gate-close" :closure closure)
    (schedule! proxy (+ send-deadline-ms closure-timer-slack-ms) #(on-closure-deadline! proxy closure))))

(defn- ensure-closed!
  "Closes the gate unless a closure is already running: after a deadline
  released the held messages while sends were still pending, the next
  message starts a fresh closure with its own bound."
  [{:keys [state] :as proxy}]
  (when-not (:gate-closed-ms @state)
    (close-gate! proxy)))

(defn report-changes!
  "Accepts `uri->type`, a map of file URI to LSP FileChangeType, for the
  next `workspace/didChangeWatchedFiles`: sent now, or coalesced into the
  next send while one is outstanding or the server is not initialized
  yet. Returns `{:reported n :queued bool}`. An empty report changes
  nothing; a report after `shutdown` is dropped."
  [{:keys [gate-lock state] :as proxy} uri->type]
  (locking gate-lock
    (let [{:keys [shutdown? server-ready? in-flight]} @state
          n (count uri->type)]
      (cond
        (empty? uri->type)
        {:reported 0 :queued false}

        shutdown?
        (do (transport/log-event! proxy "report-dropped" :reason "shutdown" :changes n)
            {:reported 0 :queued false})

        :else
        (do (busy! proxy)
            (ensure-closed! proxy)
            (if (or in-flight (not server-ready?))
              (do (swap! state update :pending merge uri->type)
                  (transport/log-event! proxy "send-queued" :changes n
                                        :reason (if in-flight "send-outstanding" "server-not-ready"))
                  {:reported n :queued true})
              (do (start-send! proxy uri->type)
                  {:reported n :queued false})))))))

(defn await-open
  "Blocks until the current closure ends, at most `timeout-ms`. Returns
  true when the gate is open afterwards (or was open), false when the
  wait timed out or a deadline released the messages with sends still
  pending."
  [{:keys [gate-lock state]} timeout-ms]
  (let [opened (locking gate-lock
                 (when (closed? @state)
                   (:gate-opened @state)))]
    (if opened
      (boolean (deref opened timeout-ms false))
      true)))

(defn await-settled
  "Blocks until the sends are settled (`settled?`), at most `timeout-ms`;
  true when they are, false when the wait timed out or when only
  unconfirmed changes remain, which no waiting resolves (see
  `resend-unconfirmed!`). An open gate is weaker: after a deadline
  released the held messages, the gate is open while the analysis of a
  reported change may still be running."
  [{:keys [gate-lock state]} timeout-ms]
  (let [deadline (+ (now) timeout-ms)]
    (loop []
      (let [current (locking gate-lock @state)
            remaining (- deadline (now))]
        (cond
          (settled? current) true
          (idle? current) false
          (not (pos? remaining)) false
          (deref (:idle current) remaining false) (recur)
          :else false)))))

(defn resend-unconfirmed!
  "Starts a send of the unconfirmed changes when the sends are idle, so
  that a waiter can have them confirmed; returns whether one started.
  Nothing happens while a send is in flight or pending (it carries them
  anyway), before `initialized` or after `shutdown`."
  [{:keys [gate-lock state] :as proxy}]
  (locking gate-lock
    (let [{:keys [unconfirmed server-ready? shutdown?] :as current} @state]
      (if (and (seq unconfirmed) (idle? current) server-ready? (not shutdown?))
        (do (busy! proxy)
            (ensure-closed! proxy)
            (transport/log-event! proxy "resend-unconfirmed" :changes (count unconfirmed))
            (start-send! proxy {})
            true)
        false))))

(defn- server-ready! [{:keys [state] :as proxy}]
  (swap! state assoc :server-ready? true)
  (let [{:keys [pending in-flight]} @state]
    (when (and (seq pending) (nil? in-flight))
      (start-send! proxy pending))))

(defn- shutting-down!
  "Forgets every send and opens the gate; the server is shutting down."
  [{:keys [state] :as proxy}]
  (let [{:keys [in-flight pending gate-closed-ms idle]} @state]
    (swap! state assoc :shutdown? true :in-flight nil :pending {})
    (deliver idle true)
    (when (or in-flight (seq pending))
      (transport/log-event! proxy "sends-abandoned" :reason "shutdown"
                            :in-flight (:id in-flight) :pending (count pending)))
    (when gate-closed-ms
      (release-held! proxy "shutdown"))))

;;; server-side progress (C2)

(defn- on-begin! [{:keys [state] :as proxy} token {:keys [created-ms]} title]
  (swap! state assoc-in [:progress-tokens token :title] title)
  (when (= title analysis-title)
    (let [{:keys [in-flight shutdown?]} @state
          after-ms (when in-flight (- created-ms (:sent-ms in-flight)))]
      (cond
        (and (nil? in-flight) shutdown?)
        nil

        (nil? in-flight)
        (transport/log-event! proxy "anomaly" :kind "analysis-without-send" :token token)

        (:token in-flight)
        (transport/log-event! proxy "anomaly" :kind "second-analysis-for-send"
                              :send (:id in-flight) :token token)

        (< after-ms create-min-delay-ms)
        (transport/log-event! proxy "anomaly" :kind "create-too-early"
                              :send (:id in-flight) :token token :after-ms after-ms)

        :else
        (do (swap! state assoc-in [:in-flight :token] token)
            (transport/log-event! proxy "send-analysis-begin"
                                  :send (:id in-flight) :token token :after-ms after-ms
                                  :after-deadline (:expired? in-flight)))))))

(defn- on-end! [{:keys [state] :as proxy} token]
  (let [{:keys [in-flight]} @state]
    (swap! state update :progress-tokens dissoc token)
    (when (and in-flight (= token (:token in-flight)))
      (end-send! proxy "analysis-end"))))

(defn handle-server-message!
  "Consumes a `window/workDoneProgress/create` request (answered with a
  null result, never forwarded) or a `$/progress` on a token the proxy
  accepted. Returns true when the message was consumed, false when the
  caller should forward it."
  [{:keys [gate-lock state] :as proxy} msg]
  (let [method (get msg "method")]
    (cond
      (= "window/workDoneProgress/create" method)
      (locking gate-lock
        (let [token (get-in msg ["params" "token"])]
          (transport/log-message! proxy "s->p" msg)
          (swap! state assoc-in [:progress-tokens token] {:created-ms (now)})
          (transport/send-own-message-to-server! proxy {"jsonrpc" "2.0" "id" (get msg "id") "result" nil})
          true))

      (= "$/progress" method)
      (locking gate-lock
        (let [token (get-in msg ["params" "token"])
              value (get-in msg ["params" "value"])]
          (if-let [owned (get-in @state [:progress-tokens token])]
            (do (transport/log-message! proxy "s->p" msg)
                (case (get value "kind")
                  "begin" (on-begin! proxy token owned (get value "title"))
                  "end" (on-end! proxy token)
                  nil)
                true)
            false)))

      :else false)))

;;; client-side gating (C3)

(defn- forward! [{:keys [state] :as proxy} body msg & log-fields]
  ;; These inputs can replace analysis without passing through the disk check.
  ;; A later restoration of previously reported bytes must be reported again.
  (when (contains? #{"textDocument/didOpen" "textDocument/didChange"
                     "textDocument/didSave" "textDocument/didClose"
                     "workspace/didChangeWatchedFiles"}
                   (get msg "method"))
    (swap! state assoc :self-reported {}))
  (apply transport/log-message! proxy "c->s" msg log-fields)
  (transport/send-to-server! proxy body))

(defn- hold! [{:keys [state] :as proxy} body msg]
  (ensure-closed! proxy)
  (swap! state update :held conj {:body body :msg msg :held-ms (now)})
  (transport/log-event! proxy "hold" :method (get msg "method") :id (get msg "id")))

(defn- cancel!
  "A `$/cancelRequest` for a held request removes it and answers
  RequestCancelled; any other one is forwarded."
  [{:keys [state] :as proxy} body msg]
  (let [target (get-in msg ["params" "id"])
        {:keys [held]} @state
        [kept dropped] (when (some? target)
                         ((juxt remove filter) #(= target (get (:msg %) "id")) held))]
    (if (seq dropped)
      (do (swap! state assoc :held (vec kept))
          (transport/log-message! proxy "c->p" msg)
          (transport/log-event! proxy "held-request-cancelled" :id target)
          (transport/send-own-message-to-client!
           proxy {"jsonrpc" "2.0" "id" target
                  "error" {"code" request-cancelled-code "message" "Request cancelled while held by clojure-lsp-proxy"}}))
      (forward! proxy body msg))))

(defn handle-client-message!
  "Forwards, holds or answers one client message per C3. `body` is what
  goes to the server when forwarded; `msg` its parsed form."
  [{:keys [gate-lock state] :as proxy} body msg]
  (locking gate-lock
    (let [method (get msg "method")]
      (case method
        nil (forward! proxy body msg)
        "$/cancelRequest" (cancel! proxy body msg)
        "initialize" (forward! proxy body msg)
        "initialized" (do (forward! proxy body msg)
                          (server-ready! proxy))
        "shutdown" (do (shutting-down! proxy)
                       (forward! proxy body msg))
        "exit" (forward! proxy body msg)
        (if (closed? @state)
          (hold! proxy body msg)
          (forward! proxy body msg))))))

(defn fail-held!
  "Answers every held request with an InternalError; the server is gone.
  Returns how many were answered. Safe to call more than once."
  [{:keys [gate-lock state] :as proxy}]
  (locking gate-lock
    (let [requests (filter #(contains? (:msg %) "id") (:held @state))]
      (doseq [{:keys [msg]} requests]
        (transport/send-own-message-to-client!
         proxy {"jsonrpc" "2.0" "id" (get msg "id")
                "error" {"code" internal-error-code "message" "clojure-lsp died while clojure-lsp-proxy held the request"}}))
      (swap! state assoc :held [])
      (count requests))))

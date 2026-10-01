# clojure-lsp-proxy

A Claude Code plugin that runs clojure-lsp behind a small Babashka proxy.
Claude Code's native LSP client talks to the proxy over stdio; the proxy
talks to clojure-lsp. The proxy exists to keep navigation correct after
files change outside Claude's `Edit` and `Write` tools (shell commands,
formatters, git commands that move the working tree), and to offer a rename
that runs in the same long-lived clojure-lsp.

Status: all five phases of the plan are implemented: the transparent
proxy with a traffic log (C1), progress ownership (C2), change injection
with gating (C3), change reports (C4), the control socket (C5), the hooks
that bracket every Bash command (C6) and rename (C7).

## Layout

The repository is the plugin. `.claude-plugin/plugin.json` is the manifest,
`.lsp.json` the LSP server configuration, `bin/` the executables Claude Code
and hooks run, `src/` the Babashka code, `test/` the suites (`bb test`).

- `bin/clojure-lsp-proxy-server`: the LSP entry point. Starts the server
  named by `CLOJURE_LSP_PROXY_SERVER` (default `clojure-lsp` on PATH) with
  the proxy's own arguments and proxies stdio.
- `bin/clojure-lsp-proxy`: the command line over the control socket
  (`status`, `changed [--wait] <path>...`, `rename`).
- `skills/clojure-rename/SKILL.md`, `skills/clojure-format/SKILL.md`: tell
  Claude to use the rename and format commands instead of editing by hand.
- `src/clojure_lsp_proxy/rename.clj`, `format.clj`: rename and formatting
  through the running clojure-lsp; `edit.clj` applies the resulting
  workspace edit, reports the touched files and waits for the analysis.
- `bin/on-bash-start`, `bin/on-bash-end`, `hooks/hooks.json`: the Claude
  Code hooks around every Bash command (PreToolUse, PostToolUse and
  PostToolUseFailure).
- `src/clojure_lsp_proxy/detect.clj`: which files a Bash command changed,
  from a git snapshot before it and a check after it (adapted from
  rule-fairy).
- `src/clojure_lsp_proxy/hooks.clj`: the hook scripts' logic.
- `src/clojure_lsp_proxy/main.clj`: process lifecycle and the two pumps.
- `src/clojure_lsp_proxy/gate.clj`: progress ownership, the send queue and
  the gate (C2, C3).
- `src/clojure_lsp_proxy/changes.clj`: which paths are reported and how
  (C4).
- `src/clojure_lsp_proxy/control.clj`, `client.clj`, `cli.clj`: the
  socket's server side, its client and the command line (C5).
- `src/clojure_lsp_proxy/framing.clj`, `transport.clj`, `log.clj`,
  `project.clj`: Content-Length framing, locked writes with logging, the
  traffic log and the per-project directory under
  `~/.cache/clojure-lsp-proxy/`.

## Running it

The repository is its own marketplace (`.claude-plugin/marketplace.json`,
one plugin with source `./`). Installed once with

```
claude plugin marketplace add /Users/lukas/Workspace/clojure/clojure-lsp-proxy
claude plugin install clojure-lsp-proxy@clojure-lsp-proxy
```

every new session loads it, whatever started Claude Code. A relative-path
plugin of a directory marketplace runs in place: the proxy's `start` event
logs `CLAUDE_PLUGIN_ROOT` as this repository, so an edit here is live in
the next session; `claude plugin update` only acts on a version change.
For a one-off session, `claude --plugin-dir <this repository>` works too
and registers before any installed plugin.

Only one plugin may serve `.clj`: Claude Code gives each extension to the
first LSP server registered and logs the losers under `--debug`
("extension .clj already handled by ..."). Among installed plugins
`clojure-lsp-cc` registers before this one, so it is disabled
(`claude plugin disable clojure-lsp-cc@clojure-lsp-cc`).

`.lsp.json` sets `startupTimeout` to five minutes because clojure-lsp runs
the whole project analysis before it answers `initialize`.

## The server

The proxy runs `CLOJURE_LSP_PROXY_SERVER` when that variable is set (a
path, or a command on PATH), else `clojure-lsp` on PATH. A path that is
not an executable file is ignored: the proxy logs `server-fallback` and
runs `clojure-lsp` from PATH, so a stale setting degrades instead of
failing to start. Claude Code sets the variable for every session from
the `env` block of `~/.claude/settings.json`:

```
"env": { "CLOJURE_LSP_PROXY_SERVER": "/path/to/clojure-lsp/clojure-lsp" }
```

The gate needs the clojure-lsp fork that reports watched-file analysis as
work-done progress (upstream PR
[clojure-lsp#2474](https://github.com/clojure-lsp/clojure-lsp/pull/2474),
open). Until that is merged and released, build it:

```
git clone https://github.com/Cyrik/clojure-lsp.git
cd clojure-lsp && git checkout Cyrik/watched-files-progress
bb prod-cli
```

and point the variable at the `clojure-lsp` launcher it produces. With
stock clojure-lsp instead, navigation works but no analysis progress ever
arrives: every reported change holds the client's requests for the full
deadline (default 30 s), the log says so (`send-dropped` with a hint), and
rename and format are refused after the first change report because no
analysis confirms it.

The traffic log is a JSONL file at
`~/.cache/clojure-lsp-proxy/<sha1 of the project root>/<proxy pid>.log`, or
at `CLAUDE_PLUGIN_LSP_LOG_FILE` when that variable is set (the test suites
set it; the current CLI has no flag for it). Every line has `t` and `ms` (wall-clock
time), a `dir` (`c->s`, `s->c` for forwarded messages; `p->s`, `s->p` for
messages the proxy itself sends or consumes; `proxy` for its own events;
`server-stderr` for the server's stderr lines) and, for messages, `method`,
`id` and the full `body`.

The project root is `CLAUDE_PROJECT_DIR` when set (Claude Code sets it for
plugin LSP servers and hooks), else the current directory. The control
socket sits next to the log as `<proxy pid>.sock`.

Proxy events in the log (`dir` `proxy`, field `event`): `gate-close`,
`send-start`, `send-queued`, `hold`, `send-analysis-begin`, `send-end`
(with its `reason`: `analysis-end`, `deadline` or `shutdown`), `gate-open`
(with `closed-ms` and the number of `released` messages), `anomaly` (a
progress `create` that cannot belong to the send in flight), `send-dropped`
(no analysis by the deadline, with a hint that the server may not be the
fork, or no end), `resend-unconfirmed`, `rename-applied`, `format-applied`,
`server-fallback` (a `CLOJURE_LSP_PROXY_SERVER` path that is not an
executable file), plus the
lifecycle events `start`, `client-eof`, `server-exit`, `terminated`,
`exit`. A released message is logged with its `held-ms`.

`CLOJURE_LSP_PROXY_SEND_DEADLINE_MS` (default 30000) bounds how long one
send can keep the gate closed; a value that is not a positive integer is
ignored with a warning.

## How a Bash command's changes reach clojure-lsp

Claude's `Edit` and `Write` tools need nothing: Claude Code's LSP client
sends `didOpen`, `didChange` and `didSave` for them. For the Bash tool, the
plugin's PreToolUse hook sends `bash-started` and the proxy checks the
project's git state (a marker time, `git status`, HEAD) against the previous
checkpoint, queues changes made between commands, and saves the new
snapshot for the bracket; the PostToolUse and PostToolUseFailure hooks
send `bash-finished` with the `bashEditDiff`
Claude Code recorded, and the proxy unions that with its own check against
the snapshot: paths new to the status listing, paths rewritten or removed
since the marker, paths that left the listing because a stash, checkout or
restore rewrote them, the old names of renames (and an old name that a
revert of the rename brought back), and what the commits between the two
HEADs touched (for paths that were clean before). The
result goes through the C4 filter and into the send queue, and the hook
returns as soon as the report is queued. Snapshots are keyed by Claude
Code's `tool_use_id`, since parallel Bash calls interleave their hooks; a
finish whose start never arrived uses the previous check's snapshot, or
the one taken at startup. Checks at Bash start, Bash finish and before
rename share a checkpoint. Each scan and report is serialized with the
checkpoint update, so overlapping brackets cannot advance it past an
unreported interval. Analysis runs independently of these checks.
Content fingerprints of reported files suppress duplicate reports from
overlapping checks; the proxy does not hash the whole project.

Set `bashEditDiffEnabled` to `true` in `~/.claude/settings.json` so that
Claude Code records its diff in every permission mode; without it the git
check catches git-visible changes made between commands by the next Bash
start or rename, including writes from editors and background commands.
Known gaps: native LSP queries before that next check can still see stale
analysis; automatic disk checks do not cover projects outside git;
files git ignores are never discovered by the git check, even a
gitignored `.clj` under a source path that clojure-lsp
analyzed at startup; nested repositories show up as one untracked
directory; a file written in the same second as a snapshot may be reported
once more than necessary.

Reporting a change by hand, from the project:

```
/path/to/clojure-lsp-proxy/bin/clojure-lsp-proxy changed --wait src/my/ns.clj
/path/to/clojure-lsp-proxy/bin/clojure-lsp-proxy status
```

## Rename

```
clojure-lsp-proxy rename <file> <line> <col> <new-name> [--apply]
clojure-lsp-proxy rename --symbol <ns/name> <new-name> [--apply]
clojure-lsp-proxy rename --symbol <ns> <new.ns> [--apply]
```

The proxy asks the running clojure-lsp for the rename; for a namespace it
also asks `workspace/willRenameFiles` for the `ns` form and `:require`
edits that go with the file move, as an editor does. Lines and columns
are one-based. `--symbol` resolves vars and namespaces through
`workspace/symbol`; keywords and locals have no workspace symbol and take
the position form. Before asking for the rename the proxy asks
`clojure/cursorInfo/raw` what sits at the position and refuses the key of
a destructuring map (`:keys`, `:syms`, `:strs`, qualified or not), which
clj-kondo marks as such; its rename would be one edit that breaks the
destructuring instead of renaming anything. The same spelling in plain
data, `{:api/keys [1 2]}`, carries no mark and is renamed. Without
`--apply` the edits are printed and nothing
changes. With `--apply` the proxy applies them (text edits from the last
position backwards, then file operations), reports every touched path
through C4, waits for the re-analysis and then replies; the command line
also tells every other proxy of the project about the touched files. A
file rename or creation that would overwrite an existing file fails before
anything is written. Before asking clojure-lsp for the edits, both dry-run
and applied rename check for git-visible disk changes since the shared
checkpoint and queue them. The rename then waits until every change the
proxy reported has been analyzed (the gate's sends are settled; `status`
shows this as `settled`), at most two
deadlines, and is refused when they are not. An open gate is not enough:
after a deadline released the held messages, the analysis of a reported
change may still be running. A send that got no analysis by its deadline,
or whose analysis never ended, leaves its changes unconfirmed (`status`:
`unconfirmed_paths`); the rename re-sends them first and proceeds only
once a send ends on its analysis. With a server that reports no progress
for watched-file changes (stock clojure-lsp, or
`:compute-external-file-changes false`) nothing ever confirms, so the
rename stays refused after the first change report: the fork with default
settings is required. The disk check is a point-in-time check: writes made
after it, ignored files and files outside git are not covered by it.

## Format

```
clojure-lsp-proxy format <file> [--apply]
```

The file's `textDocument/formatting` edits from the running clojure-lsp,
cljfmt with the project's settings as clojure-lsp reads them. Without
`--apply` they are printed; with `--apply` they go through the same path
as a rename: checked before writing, reported, waited for. A file that is
already formatted yields no edits and is not written. Whole files only.

## Contracts

- C1 Transparency. When no change has been reported, every message crosses
  the proxy unchanged except the `initialize` params patch (C2's progress
  capability and, for C7, `workspace.workspaceEdit` with `documentChanges`
  and resource operations, which only the proxy's own requests exercise)
  and the progress ownership of C2. If the server process dies unexpectedly, the proxy
  answers every held request with an InternalError (-32603) response and
  exits non-zero so that Claude Code's `restartOnCrash` restarts both. After
  a forwarded `exit`, or after the client closes stdin, the proxy exits 0:
  it sends `shutdown` and `exit` to the server if the client did not, waits
  briefly, kills it if needed. Claude Code 2.1.281 sends `shutdown` and then
  terminates the proxy with SIGTERM instead of sending `exit`; the proxy's
  shutdown hook logs that and the server ends when its stdin closes.
- C2 Progress ownership. The proxy sets
  `capabilities.window.workDoneProgress` to true in the `initialize` params
  it forwards, answers every `window/workDoneProgress/create` request from
  the server itself with a null result, never forwards those requests, and
  does not forward `$/progress` notifications for tokens it accepted.
  Progress on any other token, including the client's
  `initialize.workDoneToken`, passes through. The `begin` title is recorded
  per token.
- C3 Gating. The proxy is the only source of
  `workspace/didChangeWatchedFiles` and keeps at most one send in flight:
  changes reported while a send is in flight are coalesced and sent as the
  next notification once the current one has ended. The gate is closed from
  the moment a change report is accepted until no send is in flight or
  pending. While closed, the proxy holds every client-to-server message in
  arrival order except responses, `$/cancelRequest`, `initialize`,
  `initialized`, `shutdown` and `exit`; `shutdown` opens the gate. A
  `$/cancelRequest` for a held request removes it and answers
  RequestCancelled (-32800). A send ends when the `end` of a token arrives
  whose `begin` title is "Analyzing external file changes" and whose
  `create` arrived after the send. A deadline (default 30 s) bounds every
  hold: a held message is released at the latest one deadline after the
  gate closed, whatever the sends are doing, and a message held after such
  a release starts a fresh closure with its own bound. A send whose
  analysis had begun by its deadline stays outstanding until its `end` (or
  ten deadlines later), so the next send starts only after that batch
  ended and never ends on an earlier send's batch; a send for which no
  analysis began by the deadline gets none, since nothing else was being
  analyzed, and is dropped. A dropped send's changes stay unconfirmed: they
  join the next send and count as analyzed only when a send ends on its
  analysis; they do not hold the gate. `changed --wait` reports a timeout
  when its report was still pending at the release. Reports before the
  client's `initialized` wait for it. A
  `create` arriving under 900 ms after a send cannot belong to that send's
  batch (the server debounces for 1000 ms) and is logged as an anomaly, as
  is any `create` of that title arriving while no send is outstanding.
  Reports after `shutdown` are dropped. Every hold is logged with its
  duration and the reason the gate opened.
- C4 Change reports. Reported paths are filtered to the server's
  watched extensions (`clj cljs cljc cljd edn bb clj_kondo`) and to the
  project root, and sent as `file://` URIs of the real (symlink-resolved)
  path. Paths under clojure-lsp's ignored source paths (its
  `:source-paths-ignore-regex`, read from the project's `.lsp/config.edn`,
  else the user's clojure-lsp `config.edn`, else the default `target.*`)
  are left out too, since clojure-lsp would not analyze them and the gate
  would wait for its deadline; a regex set through `initializationOptions`
  is not seen. Type is `deleted` when the path no longer
  exists and `changed` otherwise (clojure-lsp treats created and changed
  alike). Deletions come
  from listing differences, not from file times: a status entry newly `D`,
  a path that left the listing and no longer exists, and the origin path of
  a rename or copy entry. A path that left the listing but exists (reverted
  by stash, restore or checkout) is `changed`. An empty report sends nothing
  and gates nothing.
- C5 Control socket. Each proxy listens on a Unix domain socket at
  `~/.cache/clojure-lsp-proxy/<sha1 of the real project root path>/<pid>.sock`
  and removes it on exit. Protocol: one JSON object per line in each
  direction, one request per connection. Operations: `status`,
  `bash-started`, `bash-finished`, `changed`, `rename`. `bash-started` and
  `bash-finished` reply as soon as the proxy has snapshotted or queued,
  never after analysis; `changed` waits for the gate only when asked.
  Clients address every socket in the project's directory and delete a
  socket whose connection is refused. The project root is
  `CLAUDE_PROJECT_DIR` when set, else the current directory; the command
  line, when the variable is unset, looks under the current directory and
  each of its parents up to the git top level. A `changed --wait`
  outstanding when the proxy exits gets no reply; the command line prints
  `"reply":null` for that socket and exits 1.
- C6 Hooks never fail Claude. Each hook exits 0 and prints nothing
  on stdout whatever happens; it gives up on a proxy after 5 s. Errors go to
  stderr.
- C7 Rename. Without `--apply` the CLI prints the edits
  clojure-lsp returned and changes nothing. Either way the rename first
  re-sends the changes a dropped send left unconfirmed, then is refused
  unless every change the proxy reported has been analyzed within two
  deadlines (C3's sends settled: nothing in flight, pending or
  unconfirmed), so that the edits never come from an analysis behind a
  reported change. With `--apply` the proxy applies
  the workspace edit to disk, reports the touched paths through C4, waits
  for that send to end per C3, and then replies. Every text edit is
  computed and checked against the file, and every file rename or creation
  checked for an existing target, before anything is written; a failure
  while writing leaves the files written so far in place, and those paths
  are still reported. A rename applied inside a Bash command's bracket is
  not reported again by that bracket, unless the file was written again
  afterwards, even with the same content. `--symbol` names a var or a
  namespace; keywords and locals are renamed by position. A rename aimed
  at the key of a destructuring map (`:keys`, `:syms`, `:strs`, qualified
  or not, as clj-kondo marks them) is refused before anything is
  requested; the same spelling in plain data is renamed.
- C8 Format. `format <file>` asks the running clojure-lsp for the file's
  `textDocument/formatting` edits. Without `--apply` the CLI prints them
  and changes nothing; with `--apply` they go through C7's path: checked
  before writing, reported through C4, waited for per C3, and not reported
  again by the Bash bracket around the command. A file without edits is
  not written. Like rename, format waits for every reported change to be
  analyzed first.

## Development

`bb test` runs the unit suites (framing, change reports, detection against
temporary git repositories) and three end-to-end suites, which start the
proxy as a subprocess in front of `test/fake_server.bb` and play the client
on its stdio; the gate suite also drives the control socket and the hook
scripts, the rename suite uses canned server answers and checks the files. The fake server emulates the fork's progress sequence on request
(`create` after the debounce, `begin`, `end`), so the suites take about a
minute of real waiting.

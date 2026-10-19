---
name: clojure-format
description: Format a Clojure file with clojure-lsp's formatter (cljfmt with the project's settings) through the clojure-lsp-proxy plugin. Use when asked to format or re-indent a Clojure file, or after edits that left indentation or whitespace off.
---

# Format a Clojure file with clojure-lsp

The `clojure-lsp-proxy` plugin offers the formatting clojure-lsp applies in
editors: cljfmt, with the project's own settings as clojure-lsp reads them.
Prefer it over re-indenting by hand.

Dry run first, then apply:

```
"${CLAUDE_PLUGIN_ROOT}/bin/clojure-lsp-proxy" format src/my/ns.clj
"${CLAUDE_PLUGIN_ROOT}/bin/clojure-lsp-proxy" format src/my/ns.clj --apply
```

Without `--apply` the command prints the edits and changes nothing. With
`--apply` it writes the file, tells clojure-lsp about it and waits for the
re-analysis, up to two send deadlines (a minute by default). A file that
is already formatted yields no edits and nothing is written. Re-read the
file before editing it again after an applied format; your copy of it is
stale. The command formats whole files, one per call.
If the output says "analysis not confirmed", clojure-lsp did not confirm
its analysis of the written files within the wait. The next edit command
first waits for that analysis, re-sending the changes if no analysis is
running, and is refused if it is still not confirmed within two deadlines.

When the command reports that no proxy is running: if Claude Code has
not started the LSP server for this project yet, run any LSP query
first, then retry.

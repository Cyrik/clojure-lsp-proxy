---
name: clojure-clean-ns
description: Clean a Clojure file's ns form (remove unused requires, refers and imports, sort what remains) with clojure-lsp's clean-ns through the clojure-lsp-proxy plugin. Use after editing a file's requires, when clj-kondo reports an unused namespace, alias, refer or import, or when asked to clean up or sort an ns form.
---

# Clean a Clojure ns form with clojure-lsp

The `clojure-lsp-proxy` plugin offers clojure-lsp's clean-ns refactoring:
it removes the requires, refers and imports the file does not use and
sorts the rest, from clojure-lsp's analysis of the project and with the
project's clean-ns settings. Prefer it over editing the `ns` form by hand.

Dry run first, then apply:

```
"${CLAUDE_PLUGIN_ROOT}/bin/clojure-lsp-proxy" clean-ns src/my/ns.clj
"${CLAUDE_PLUGIN_ROOT}/bin/clojure-lsp-proxy" clean-ns src/my/ns.clj --apply
```

Without `--apply` the command prints the new `ns` form and changes
nothing. With `--apply` it writes the file, tells clojure-lsp about it and
waits for the re-analysis, up to two send deadlines (a minute by default).
clojure-lsp always proposes the whole `ns` form, so a file that is already
clean still shows one edit in the dry run; applying it writes nothing and
reports 0 paths. Re-read the file before editing it again after an applied
clean-ns; your copy of it is stale. One file per call.
If the output says "analysis not confirmed", clojure-lsp did not confirm
its analysis of the written files within the wait. The next edit command
first waits for that analysis, re-sending the changes if no analysis is
running, and is refused if it is still not confirmed within two deadlines.

A file without an `ns` form is refused with "no edit proposed" ("clean-ns
proposed no edit for ...").

When the command reports that no proxy is running: if Claude Code has
not started the LSP server for this project yet, run any LSP query
first, then retry.

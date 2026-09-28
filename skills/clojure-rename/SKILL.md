---
name: clojure-rename
description: Rename a Clojure var or namespace across the project with clojure-lsp instead of editing call sites by hand. Use when asked to rename a function, var, or namespace, or when a rename would touch more than one file.
---

# Rename a Clojure symbol with clojure-lsp

The `clojure-lsp-proxy` plugin runs clojure-lsp behind a proxy and offers a
rename that uses the same analysis as the LSP tool. Prefer it over editing
call sites yourself: it finds every reference, handles aliases and keyword
namespaces, and for a namespace rename moves the file and rewrites every
`require` in one step.

Dry run first, then apply:

```
"${CLAUDE_PLUGIN_ROOT}/bin/clojure-lsp-proxy" rename --symbol my.ns/old-name new-name
"${CLAUDE_PLUGIN_ROOT}/bin/clojure-lsp-proxy" rename --symbol my.ns/old-name new-name --apply
```

Forms:

- `rename --symbol <ns/name> <new-name>` renames a var by its fully
  qualified name.
- `rename --symbol <ns> <new.ns>` renames a namespace: the file moves to
  the path matching the new name and every `ns` form and `:require` is
  updated.
- `rename <file> <line> <col> <new-name>` renames whatever symbol sits at
  that one-based position.

Without `--apply` the command prints the edits and changes nothing. With
`--apply` it writes the files, tells clojure-lsp about them and returns
once the re-analysis is done, so LSP queries right after it are current.
Re-read a file before editing it again after an applied rename; your copy
of it is stale.

If the command reports that no proxy is running, the LSP server for this
project has not started yet: run any LSP tool query first.

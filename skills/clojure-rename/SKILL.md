---
name: clojure-rename
description: Rename a Clojure var, namespace, keyword or local across the project with clojure-lsp instead of editing call sites by hand. Use when asked to rename a function, var, namespace, keyword or binding, or when a rename would touch more than one file.
---

# Rename a Clojure symbol with clojure-lsp

The `clojure-lsp-proxy` plugin runs clojure-lsp behind a proxy and offers a
rename that uses the same analysis as the LSP tool. Prefer it over editing
call sites yourself: it finds every reference, handles aliases and
auto-resolved keywords, and for a namespace rename moves the file and
rewrites every `require` in one step.

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
- `rename <file> <line> <col> <new-name>` renames whatever sits at that
  one-based position: a var, a keyword or a local. Keywords and locals have
  no workspace symbol, so this is their form; `grep -n` gives the line, and
  the column is the match offset plus one.

Aim precisely when renaming a keyword:

- Point at an occurrence of the keyword itself, `:user/id` or `::id`,
  anywhere it is used. clojure-lsp renames every spelling of it, aliased
  forms included.
- Do not point at a symbol bound by destructuring: in `{:keys [id]}` or
  `{:user/keys [id]}` the symbol `id` is a local, and renaming it renames
  that binding, not the keyword.
- Do not point at `:keys`, `:syms`, `:strs` or a qualified form such as
  `:user/keys`. They are destructuring syntax, not keywords of the domain;
  the command refuses them.

Quote a new name that contains `?` or `*`, such as `'valid?'`, or the shell
expands it as a glob.

Without `--apply` the command prints the edits and changes nothing. With
`--apply` it writes the files, tells clojure-lsp about them and returns
once the re-analysis is done, so LSP queries right after it are current.
Re-read a file before editing it again after an applied rename; your copy
of it is stale.

If the command reports that no proxy is running, the LSP server for this
project has not started yet: run any LSP tool query first.

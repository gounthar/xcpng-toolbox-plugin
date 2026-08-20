#!/usr/bin/env bash
#
# Refuses tooling attribution in tracked content and in commit messages.
#
# This project's convention is that nothing tracked names the development tools used to
# write it: not in code, not in comments, not in commit messages, not in pull requests or
# issues. The rule existed before this script and was applied by hand, which is exactly why
# it needs a script -- a squashed merge landed seven Co-Authored-By trailers naming a tool
# on the default branch, and five source comments cited an uncommitted working file by a
# name that breaks the same rule. Both survived review. History had to be rewritten.
#
# Two things it checks:
#
#   1. Tracked file contents.
#   2. Commit messages in a range, defaulting to the range of the pull request.
#
# The patterns live base64-encoded in .github/tooling-patterns.b64 so that this checker does
# not trip the rule it enforces. That is the whole reason for the encoding; it is not
# obfuscation, and the file decodes with `base64 -d`.
#
# Usage:
#   check-tooling-references.sh                 # tracked files only
#   check-tooling-references.sh <base> <head>   # also commit messages in base..head
#
# Exit 0 clean, 1 a violation, 2 the check could not run. Read the 2: it means nothing was
# verified, and a check that cannot run must not look like a check that passed.

set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "error: not inside a git repository" >&2; exit 2
}
cd "$repo_root" || exit 2

patterns_file=".github/tooling-patterns.b64"
[ -r "$patterns_file" ] || { echo "error: cannot read $patterns_file" >&2; exit 2; }

pattern="$(base64 -d < "$patterns_file" 2>/dev/null | tr -d '\n')"
[ -n "$pattern" ] || { echo "error: pattern list decoded to nothing" >&2; exit 2; }

# The checker and its pattern file are the two places the strings legitimately appear.
self=".github/scripts/check-tooling-references.sh"
hook=".githooks/commit-msg"

violations=0

echo "Scanning tracked files..."
while IFS= read -r -d '' f; do
  case "$f" in "$self"|"$patterns_file"|"$hook") continue ;; esac
  if grep -PIqn "$pattern" "$f" 2>/dev/null; then
    echo "  $f:"
    grep -PIn "$pattern" "$f" 2>/dev/null | head -5 | sed 's/^/    /'
    violations=$((violations + 1))
  fi
done < <(git ls-files -z)

if [ $# -ge 2 ] && [ -n "${1:-}" ] && [ -n "${2:-}" ]; then
  echo "Scanning commit messages in $1..$2 ..."
  # A missing base (a force-push, a shallow fetch) must not read as "no bad commits".
  if ! git rev-parse --verify --quiet "$1^{commit}" >/dev/null; then
    echo "error: base commit $1 is not present; commit messages were NOT checked" >&2
    exit 2
  fi
  while IFS= read -r sha; do
    [ -z "$sha" ] && continue
    if git show -s --format='%B' "$sha" | grep -Pq "$pattern"; then
      echo "  $sha  $(git show -s --format='%s' "$sha")"
      git show -s --format='%B' "$sha" | grep -Pn "$pattern" | head -3 | sed 's/^/    /'
      violations=$((violations + 1))
    fi
  done < <(git rev-list "$1..$2")
else
  echo "No commit range given; commit messages were not checked."
fi

if [ "$violations" -gt 0 ]; then
  cat >&2 <<'MSG'

Refused: tracked content or a commit message names a development tool.

Nothing tracked in this repository names the tools used to write it. If a trailer was added
automatically, remove it and amend. If a comment points at a working file whose name breaks
the rule, state the fact inline instead -- the pointer is almost always redundant.

MSG
  exit 1
fi

echo "Clean."

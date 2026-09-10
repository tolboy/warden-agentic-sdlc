#!/usr/bin/env sh
# The repository hygiene gate. Two scans share this file so they cannot drift apart:
#
#   scripts/hygiene.sh tip   <check>                  the checked-out tree
#   scripts/hygiene.sh range <check> [base] [head]    every commit a push introduces
#
# <check> is `paths` or `uuids`. The tip scan alone is not enough, and that is not a
# hypothetical: a commit once added three absolute paths to a document and the very next
# commit reworded them away. The tip was green the whole time, and the paths were still in
# the history a push would publish. The range scan reads the lines each commit *added*, so a
# later removal does not hide them. Both scans take their pattern and their file list from
# the definitions below; adding a pattern in one place adds it to both.
#
# Both scans are `git`-only, so they see tracked content only — a maintainer's local run
# directories under .warden/runs/ can neither fail nor pass this job by accident.
set -e
cd "$(dirname "$0")/.."

# These patterns are checks, not style preferences: every one of them matched something real
# before this repository was opened.
#
# The segment after the home directory must *start* with a letter or a digit. A dot is legal
# inside a Windows user name but never begins an elision, so `C:\Users\...\npm` in a comment
# illustrating what `where.exe` prints is not a personal path — and the first run of this job
# failed on exactly that.
#
# `[\\/]` is one class holding a backslash and a forward slash, so the check reads
# `C:\Users\...` and `C:/Users/...` alike — the paths that started this were written with
# forward slashes. Spelling the backslash twice is the same set under a POSIX bracket
# expression and under an engine that reads a backslash there as an escape.
#
# `Idea[P]rojects` matches the plain string without matching this line. The file that defines
# a pattern is scanned by that pattern, so a literal spelling here would fail the gate on
# itself. The bracket is the whole trick; the character class matches one literal `P`.
paths_pattern='(C:[\\/]+Users[\\/]+[A-Za-z0-9][A-Za-z0-9._-]*|/home/[a-z][a-z0-9._-]*|/Users/[a-z][a-z0-9._-]*|Idea[P]rojects)'
paths_message='an absolute personal path is committed; use <warden> / <project> placeholders'

uuids_pattern='\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b'
uuids_message='a UUID is committed in documentation; describe the id, do not print it'

# Runs the git command in "$@" against the check's file list. The list lives here once, so the
# tip scan and the range scan cannot come to disagree about which files are in scope.
# examples/demo/sample-evidence carries a deliberately sanitised `C:\Temp\warden-demo` prefix
# (documented in examples/demo/README.md); it is in scope and does not match — the patterns
# ask for a home directory, not for any absolute path.
hygiene_paths_of() {
  hygiene_check=$1
  shift
  case "$hygiene_check" in
    paths)
      "$@" -- '*.md' '*.java' '*.yaml' '*.yml' '*.mjs' '*.sh' '*.cmd' '*.json' '*.jsonl'
      ;;
    uuids)
      "$@" -- 'docs/*.md' 'spec/*.md' 'docs/ru/*.md' 'spec/ru/*.md' README.md README.ru.md
      ;;
  esac
}

usage() {
  echo "usage: scripts/hygiene.sh tip <paths|uuids>" >&2
  echo "       scripts/hygiene.sh range <paths|uuids> [base-sha] [head-sha]" >&2
  exit 2
}

mode=${1:-}
check=${2:-}
case "$check" in
  paths | uuids) ;;
  *) usage ;;
esac
eval "pattern=\$${check}_pattern"
eval "message=\$${check}_message"

case "$mode" in

  tip)
    if hygiene_paths_of "$check" git grep -nE "$pattern"; then
      echo "::error::$message"
      exit 1
    fi
    ;;

  range)
    base=${3:-}
    head=${4:-HEAD}

    if ! git rev-parse -q --verify "$head^{commit}" >/dev/null 2>&1; then
      echo "::error::hygiene: head commit '$head' is not in this checkout"
      exit 1
    fi

    # A push hands over the commit it started from, and `head --not base` is then the exact
    # set this push introduces. It needs no ancestry, so it stays right after a force-push.
    # `base..head` says the same thing, but git first asks the filesystem whether
    # `<sha>..<sha>` is a file, and Windows answers with an error rather than with "no".
    usable=""
    if [ -n "$base" ] && [ -n "$(printf '%s' "$base" | tr -d 0)" ]; then
      if ! git rev-parse -q --verify "$base^{commit}" >/dev/null 2>&1; then
        # A named commit that is not here was never fetched, which is what a force-push does.
        # The server often still serves it by name, so ask once before giving up on the exact
        # range. No prompt: an unauthenticated remote must fail rather than wait for a password.
        GIT_TERMINAL_PROMPT=0 git fetch --quiet --no-tags origin "$base" >/dev/null 2>&1 || :
      fi
      if git rev-parse -q --verify "$base^{commit}" >/dev/null 2>&1; then
        usable=$base
      fi
    fi

    if [ -n "$usable" ]; then
      commits=$(git rev-list --no-merges "$head" --not "$usable")
    else
      # No usable base: a branch or tag created by this push reports all zeros, a manual run
      # names nothing, and a force-push can name a commit the server will not serve. Reading
      # the tip commit alone would put back the hole this file exists to close, so read
      # everything the published branch does not already carry instead. A tag of a commit
      # already on that branch is then an empty range, which is the right answer and not worth
      # a warning. Two things this cannot do: when the tip *is* the published branch, which is
      # what a push to it looks like once its starting commit is unusable, the range is empty
      # and only the tip scan speaks; and a branch with no ancestor in common with the
      # published one is read back to its root.
      fallback=""
      for candidate in ${HYGIENE_FALLBACK_BASE:-} origin/main origin/master; do
        if git rev-parse -q --verify "$candidate^{commit}" >/dev/null 2>&1; then
          fallback=$candidate
          break
        fi
      done

      if [ -n "$fallback" ]; then
        commits=$(git rev-list --no-merges "$head" --not "$fallback")
        short=$(git rev-parse --short "$head")
        echo "hygiene: no usable base ('${base:-none}'); reading $short against $fallback."
      else
        commits=$(git rev-list --no-merges "$head" --not "$head^@")
        short=$(git rev-parse --short "$head")
        note="hygiene: no usable base ('${base:-none}') and no published branch to"
        note="$note compare against; reading $short alone. Only the tip scan covers what"
        note="$note an earlier commit of this push added and a later one removed."
        echo "::warning::$note"
      fi
    fi

    # A merge commit is skipped: every commit it brings in is read on its own, and the tree it
    # produces is read by the tip scan. Only a line that exists nowhere but in a merge's own
    # conflict resolution, and is then removed again, escapes both.
    status=0
    for commit in $commits; do
      hits=$(
        hygiene_paths_of "$check" git show --format= --name-only --diff-filter=d "$commit" |
          while IFS= read -r file; do
            [ -n "$file" ] || continue
            # Added lines only, and only from inside a hunk: `+++ b/<path>` is a header, and a
            # line of content that itself begins with `+++` must not be mistaken for one.
            git show --format= --unified=0 "$commit" -- "$file" |
              awk '/^@@ / { hunk = 1; next }
                   hunk && substr($0, 1, 1) == "+" { print substr($0, 2) }' |
              grep -E "$pattern" |
              awk -v c="$commit" -v f="$file" '{ print substr(c, 1, 9) " " f ": " $0 }'
          done
      )
      if [ -n "$hits" ]; then
        printf '%s\n' "$hits"
        status=1
      fi
    done

    if [ "$status" -ne 0 ]; then
      echo "::error::$message — added by a commit above, and still in the history this push" \
        "publishes even if a later commit removed the line"
      exit 1
    fi
    ;;

  *) usage ;;
esac

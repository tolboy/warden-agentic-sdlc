#!/usr/bin/env sh
# A complete Warden gate cycle that spends nothing: no vendor, no credential, no network.
#
# It builds a throwaway Git repository from examples/demo/fixture, then runs the machine
# half of the loop twice — once against a tree that does not satisfy the contract, once
# against a tree that does — and prints the evidence both runs left behind.
#
# Usage:  examples/demo/run.sh [--keep]
#         --keep leaves the scratch repository in place and prints its path.
set -e

DEMO="$(cd "$(dirname "$0")" && pwd)"
WARDEN_HOME="$(cd "$DEMO/../.." && pwd)"
WARDEN="$WARDEN_HOME/bin/warden"
KEEP=""
[ "$1" = "--keep" ] && KEEP=1

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

if [ ! -d "$WARDEN_HOME/out/classes" ]; then
  say "building warden"
  "$WARDEN_HOME/build.sh"
fi

WORK="${TMPDIR:-/tmp}/warden-demo-$$"
rm -rf "$WORK"
mkdir -p "$WORK"
if [ -z "$KEEP" ]; then
  trap 'rm -rf "$WORK"' EXIT INT TERM
fi

cp -R "$DEMO/fixture/." "$WORK/"
cd "$WORK"

# Warden pins a base commit and judges the working tree against it, so the fixture needs a
# real repository — not because Warden wants to commit anything (it never does), but because
# "what changed" has to mean something.
git init -q
git checkout -q -b main 2>/dev/null || true
git add -A
git -c user.email=demo@example.invalid -c user.name="Warden demo" \
    commit -q -m "baseline: contract and checker, no result.txt yet"

say "1. validate — is the contract even coherent? (no run directory, no cost)"
"$WARDEN" validate create-result

say "2. gates on a tree that does not satisfy the contract"
# result.txt is absent, so the acceptance command must fail. A demo that could not show the
# red half would be proving nothing: a gate that has never refused is not known to work.
if "$WARDEN" gates create-result --run-id demo-red; then
  echo "UNEXPECTED: gates passed with result.txt absent" >&2
  exit 1
fi
echo "  (exit 1 above is the point: gates refused, and wrote why)"

say "3. do the work by hand — this is the line an implementer role would have written"
printf 'WARDEN_OK\n' > result.txt

say "4. gates on the same tree, now satisfying the contract"
"$WARDEN" gates create-result --run-id demo-green

say "5. what the two runs left behind"
find .warden/runs -type f | sort

say "6. the machine report the green run wrote"
# `warden report <run-id>` joins a whole run — every stage, every vendor call, the human
# decision. This demo never ran a vendor, so there is no run summary to join; the gate's own
# report is the evidence that exists, and Warden does not invent the rest.
cat .warden/runs/demo-green/machine-gate.json

say "7. warden ledger — every run in this project, joined"
"$WARDEN" ledger

say "done"
echo "Two runs, both recorded: demo-red refused, demo-green passed."
echo "No vendor was called, so no money was spent and nothing left this machine."
if [ -n "$KEEP" ]; then
  echo "Scratch repository kept at: $WORK"
else
  echo "Scratch repository removed. Re-run with --keep to poke at it."
fi

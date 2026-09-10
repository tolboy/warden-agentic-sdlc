#!/usr/bin/env sh
# Proves the hygiene gate still catches what scripts/hygiene.sh claims to catch. It builds
# throwaway repositories under a temporary directory and runs the real gate against them, so a
# change that quietly stops catching something fails here instead of on the next push. The Java
# suite cannot hold this down: the gate is shell, run by GitHub Actions.
#
# The case that matters most is the one this file is named for. A commit adds a private path
# and the next commit rewords it away. The tree is clean at the tip and the history is not.
#
# This script has to write the very strings the gate rejects, and the gate scans this script.
# Every fixture below is therefore assembled from fragments, so no rejected string appears
# whole in this file. It is the same reason scripts/hygiene.sh spells `Idea[P]rojects` with a
# bracket, and a new fixture has to keep doing it.
set -e
cd "$(dirname "$0")/.."
gate_source=$(pwd)/scripts/hygiene.sh

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

passed=0
failed=0

# expect <wanted-exit> <label> <command...>
expect() {
  want=$1
  label=$2
  shift 2
  if out=$("$@" 2>&1); then got=0; else got=$?; fi
  if [ "$got" -eq "$want" ]; then
    passed=$((passed + 1))
    echo "ok    $label"
  else
    failed=$((failed + 1))
    echo "FAIL  $label: wanted exit $want, got $got"
    printf '%s\n' "$out" | sed 's/^/          /'
  fi
}

# says <substring> <label> <command...> — the command must succeed and say this
says() {
  want=$1
  label=$2
  shift 2
  if out=$("$@" 2>&1); then got=0; else got=$?; fi
  case "$out" in
    *"$want"*) said=yes ;;
    *) said=no ;;
  esac
  if [ "$got" -eq 0 ] && [ "$said" = yes ]; then
    passed=$((passed + 1))
    echo "ok    $label"
  else
    failed=$((failed + 1))
    echo "FAIL  $label: wanted exit 0 saying '$want', got exit $got"
    printf '%s\n' "$out" | sed 's/^/          /'
  fi
}

new_repo() {
  mkdir -p "$1/scripts"
  cp "$gate_source" "$1/scripts/hygiene.sh"
  chmod +x "$1/scripts/hygiene.sh"
  git init -q "$1"
  git -C "$1" config user.email hygiene@example.invalid
  git -C "$1" config user.name "hygiene selftest"
  git -C "$1" config core.autocrlf false
  git -C "$1" config commit.gpgsign false
}

# write <path> <line> — one line into a file, creating its directory
write() {
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$2" > "$1"
}

save() {
  git add -A
  git commit -q -m "$1"
  git rev-parse HEAD
}

# The fragments. None of these is a forbidden string until it is put together at run time.
bs='\'
name=anatoly
users="U""sers"
idea="Idea""Projects"

win_path="C:${bs}${users}${bs}Anato${bs}src"
slash_path="C:/${users}/${name}/orca/workspaces/warden-p1/.warden"
posix_path="/home/${name}/warden/out"
mac_path="/${users}/${name}/warden"
bare_ident="built under ${idea}/warden/out"
# A directory named like a home directory. Written in two pieces for the same reason as the
# rest: spelled whole, this line would fail the gate that is scanning this file.
home_named_dir="docs/ho""me/notes"
demo_path="C:${bs}Temp${bs}warden-demo${bs}.warden${bs}runs${bs}demo-green"
elision="where.exe prints C:${bs}${users}${bs}...${bs}npm here"
uuid="3f2a1b8c-9d4e-4f10-8a2b-77c6e0d51234"

# ---------------------------------------------------------------- the linear history

repo=$work/linear
new_repo "$repo"
cd "$repo"
gate=./scripts/hygiene.sh

write README.md "# a repository"
base=$(save "base")

write docs/P1.md "evidence under $slash_path"
save "add the paths" > /dev/null
write docs/P1.md "evidence under the run directory"
reworded=$(save "reword them away")

expect 0 "the tree is clean once the paths are reworded away" $gate tip paths
expect 1 "the range still sees what the reworded commit added" $gate range paths "$base" "$reworded"

write docs/W.md "checkout at $win_path"
backslash=$(save "a backslash path with a capitalised user name")
expect 1 "a backslash path is caught, whatever the case of the name" \
  $gate range paths "$reworded" "$backslash"

write docs/M.md "the reviewer read $mac_path"
mac=$(save "a POSIX home under Users")
expect 1 "a POSIX home path is caught" $gate range paths "$backslash" "$mac"

write docs/H.md "the log is in $posix_path"
posix=$(save "a POSIX home under home")
expect 1 "a home directory path is caught" $gate range paths "$mac" "$posix"

write docs/I.md "$bare_ident"
bare=$(save "an identifier with no drive letter")
expect 1 "a bare machine-local identifier is caught" $gate range paths "$posix" "$bare"

git rm -q docs/W.md docs/M.md docs/H.md docs/I.md
cleaned=$(save "drop them all")

# What must not trip. The demo fixture's sanitised prefix is documented in
# examples/demo/README.md, and the elision is what where.exe prints.
write examples/demo/sample-evidence/demo-green/evidence.jsonl "{\"path\":\"$demo_path\"}"
write docs/E.md "$elision"
write "$home_named_dir/readme.md" "a directory named like a home directory is not one"
write docs/F.md "+++ a line of content that begins like a diff header"
sanitised=$(save "sanitised fixtures")

expect 0 "the sanitised demo prefix, the elision and a home directory name do not trip" \
  $gate range paths "$cleaned" "$sanitised"
expect 0 "and neither does the tree they leave" $gate tip paths

write docs/F.md "+++ $win_path"
fence=$(save "a path on a line that begins like a diff header")
expect 1 "a content line beginning with +++ is read, not mistaken for a header" \
  $gate range paths "$sanitised" "$fence"
git revert --no-edit "$fence" > /dev/null
unfenced=$(git rev-parse HEAD)

write docs/RUN.md "run id $uuid was accepted"
save "a machine-local id in documentation" > /dev/null
write docs/RUN.md "the run was accepted"
idgone=$(save "describe the id instead")
expect 0 "the tree is clean once the id is described" $gate tip uuids
expect 1 "the range still sees the id that commit added" $gate range uuids "$unfenced" "$idgone"

write src/main/java/Fixture.java "class Fixture { String id = \"$uuid\"; }"
injava=$(save "the same id in a source file")
expect 0 "the same id outside the documentation file list is left alone" \
  $gate range uuids "$idgone" "$injava"

git checkout -q -b side "$injava"
write docs/S.md "a side note about $posix_path"
save "a path on a side branch" > /dev/null
git checkout -q -
git merge -q --no-ff side -m "merge the side branch"
expect 1 "a commit reached through a merge is still read" $gate range paths "$injava" HEAD

# ------------------------------------------------------- no usable base to work from

# This is the case the tip-only fallback used to miss. A manual run and a force-push whose
# starting commit was never fetched both arrive with no base worth reading, and the pair of
# commits that adds a path and rewords it away is then earlier in the branch than the tip.
repo=$work/nobase
new_repo "$repo"
cd "$repo"
gate=./scripts/hygiene.sh

write README.md "# a repository"
published=$(save "base")
git update-ref refs/remotes/origin/main "$published"

git checkout -q -b feature
write docs/P1.md "evidence under $slash_path"
save "add the paths" > /dev/null
write docs/P1.md "evidence under the run directory"
feature=$(save "reword them away")

expect 0 "a branch with no base leaves a clean tree" $gate tip paths
expect 1 "with no base, the published branch is what the range is read against" \
  $gate range paths "" "$feature"
expect 1 "an all-zero base is read the same way" \
  $gate range paths 0000000000000000000000000000000000000000 "$feature"
expect 1 "a base the checkout does not have is read the same way" \
  $gate range paths deadbeefdeadbeefdeadbeefdeadbeefdeadbeef "$feature"

says "against origin/main" "the log says which branch stood in for the base" \
  $gate range uuids "" "$feature"

# A tag of a commit that is already published introduces nothing, and should pass without
# complaining about it.
says "against origin/main" "a tag of a published commit is an empty range" \
  $gate range paths "" "$published"

# With nothing published to compare against, the gate says out loud that it read the tip alone
# rather than claiming it read a range.
git update-ref -d refs/remotes/origin/main
says "::warning::" "with no published branch either, the gate says what it could not read" \
  $gate range paths "" "$feature"

# ------------------------------------------------------------------------- the verdict

cd /
echo
if [ "$failed" -ne 0 ]; then
  echo "hygiene selftest: $passed passed, $failed failed"
  exit 1
fi
echo "hygiene selftest: $passed passed, 0 failed"

#!/usr/bin/env bash
# Reinstall Codex CLI + Claude Code (cloud box or local). No login. No secrets. Safe to re-run.
set -euo pipefail

TOOLS_DIR="${AGENT_TOOLS_DIR:-${1:-$HOME/.local/agent-tools}}"
export PATH="${HOME}/.local/bin:${PATH}"
mkdir -p "$TOOLS_DIR" "${HOME}/.local"

echo "[bootstrap] node=$(node -v) npm=$(npm -v) TOOLS_DIR=$TOOLS_DIR"

npm prefix -g >/dev/null
PREFIX="$(npm prefix -g)"
mkdir -p "$PREFIX/bin" "$PREFIX/lib"

echo "[bootstrap] Installing @anthropic-ai/claude-code (global under $PREFIX)..."
npm i -g @anthropic-ai/claude-code

echo "[bootstrap] Installing @openai/codex (pinned working linux pair; latest linux-x64 tarball may 404)..."
# Fact observed 2026-09-29: @openai/codex@0.159.1-linux-x64 tarball returns HTTP 404.
# 0.159.0 + 0.159.0-linux-x64 works on Debian glibc x86_64.
npm i -g @openai/codex@0.159.0
CODEx_DIR="$(npm root -g)/@openai/codex"
if [[ -d "$CODEx_DIR" ]]; then
  (cd "$CODEx_DIR" && npm i --no-save '@openai/codex-linux-x64@npm:@openai/codex@0.159.0-linux-x64')
fi

echo "[bootstrap] Mirroring into $TOOLS_DIR ..."
cd "$TOOLS_DIR"
[[ -f package.json ]] || npm init -y >/dev/null 2>&1
npm i @openai/codex@0.159.0 @anthropic-ai/claude-code
(cd "$TOOLS_DIR/node_modules/@openai/codex" && npm i --no-save '@openai/codex-linux-x64@npm:@openai/codex@0.159.0-linux-x64')

echo "[bootstrap] Ensuring PATH hint"
if ! grep -q '.local/bin' "${HOME}/.bashrc" 2>/dev/null; then
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> "${HOME}/.bashrc"
fi

echo "[bootstrap] Versions:"
command -v codex && codex --version || true
command -v claude && claude --version || true
echo "[bootstrap] Also: $TOOLS_DIR/node_modules/@openai/codex/bin/codex.js"
echo "[bootstrap] Done. Do not run login here unless the user explicitly asks."

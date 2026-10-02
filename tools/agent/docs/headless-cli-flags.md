# Headless CLI flags (agent box)

Do **not** pass `--reasoning-effort` or `-c model_reasoning_effort` to Grok/Codex when the user already set CLI defaults (Codex `xhigh` in `~/.codex/config.toml`; Grok high as model default). Verify configs if unsure; report actual effort; do not override.

## Codex

- Non-interactive: `codex exec "…"` (or project-equivalent).
- Images: `codex exec -i path.png …` (confirm with `codex exec --help` on your version).
- Avoid stdin-only prompts when `-i` is used (pass the task as an argv string).
- Do not pass `--reasoning-effort` / `model_reasoning_effort` unless the user asks.

## Grok (Grok CLI / Grok Build)

- Prefer short headless prompts; long image/pptx sessions may hit wall-clock timeouts.
- Do not pass `--reasoning-effort` when defaults are already configured.
- In-session `image_gen` is available inside Grok Build; there is no separate cheap imagine CLI.

## Claude Code

- Non-interactive: `claude -p "…" --allowedTools … --add-dir … --permission-mode acceptEdits|plan`
- Do **not** pass `--effort`.
- For read-only review: `--allowedTools Read --permission-mode plan`
- For write/build: `--allowedTools Read,Write,Bash --permission-mode acceptEdits`
- Feed absolute paths in the prompt when reviewing PNGs.

## agy

- Visual review CLI; use one call when the pipeline says so.
- Prefer attaching or pointing at concrete PNG paths; keep the rubric short and mechanical.

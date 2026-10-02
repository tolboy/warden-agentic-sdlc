# Shared agent tools (Warden)

Cross-project helpers for agent loops on Windows and the cloud box.

## Layout

| Path | Purpose |
|------|---------|
| `snap.ps1` | PrintWindow capture of Berloga-AI (or titled window) to PNG. |
| `uia.ps1` | Dump UIA tree JSON for the target window. |
| `bootstrap-cli.sh` | Reinstall Codex + Claude Code; mirror under `$AGENT_TOOLS_DIR` or `$1` (default `~/.local/agent-tools`). |
| `prompts/` | Role prompt templates (planner / reviewer / executor / agy). |
| `docs/headless-cli-flags.md` | Headless flags for codex / grok / claude / agy. |
| `docs/review-rubric.md` | Visual review rubric (1–5). |

## Windows

```powershell
Unblock-File .\tools\agent\*.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\agent\snap.ps1 -Out .\snap.png
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\agent\uia.ps1 -Out .\uia.json
```

## Bootstrap (Linux / cloud box)

```bash
bash tools/agent/bootstrap-cli.sh
# or: AGENT_TOOLS_DIR=/path/to/tools bash tools/agent/bootstrap-cli.sh
```

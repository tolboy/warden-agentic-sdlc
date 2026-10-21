# Four-vendor live pilot

This is an intentionally incomplete, dependency-free Node fixture for real CLI calls.
Use a disposable Orca worktree. Keep the test and contract unchanged, commit the
three fixture files first, then run the pilot. Never install it over an existing app.

The experiment compares AGY Gemini and DeepSeek as the second reader. Both variants
use Grok as the writer and Codex as the first reader. Three paired repeats share the
same base commit, task, budgets and Warden build. The order is A/B, B/A, A/B.

| Profile | CLI/provider | Requested model | Effort |
| --- | --- | --- | --- |
| grok-implement | Grok | grok-4.7 | high |
| codex-review | Codex/OpenAI | gpt-6.1-sol | medium |
| agy-review | AGY/Google | gemini-3.8-flash-high | high |
| deepseek-review | Codex/OpenRouter | deepseek/deepseek-v4.1-flash-1017 | medium |

The supplied profiles are examples, with no inherited verification stamp. Install
Warden's normal prompts/schemas, copy the selected profiles and `verification-fixtures/`
into the Warden config home, configure the provider, and run
`warden profiles --verify <name>` before using them. Model IDs are the values checked
on 2026-10-21, not a promise that a vendor will keep them available. Orca 1.4.233
refuses launch-time model selection for Grok, so its pinned profile uses `direct`.
Codex profiles use stdin, avoiding multiline prompts through Windows npm shims.

Configure a named Codex `deepseek.config.toml` profile, not the default model. Add an
OpenRouter provider to the base config with `base_url = "https://openrouter.ai/api/v1"`
and `wire_api = "responses"`. Use command-backed provider authentication that reads
the key file; never put its contents in this repository, a task or a command line.
The actual provider command and optional MCP exclusions are personal settings.
See [Codex configuration](https://learn.chatgpt.com/docs/config-file/config-advanced),
[OpenRouter Responses](https://openrouter.ai/docs/api_reference/responses/overview)
and the [pinned model](https://openrouter.ai/deepseek/deepseek-v4.1-flash-1017).

`warden-read.config.toml` selects OpenAI and `deepseek.config.toml` selects OpenRouter.
Disable optional integrations in those worker profiles when they are unnecessary;
an unrelated MCP startup can otherwise dominate a small task. The API credential
must stay available only to the CLI's provider authentication.

After preparing and committing this fixture in the disposable project:

```powershell
python -X utf8 run-pairs.py --project C:/path/to/disposable-worktree `
  --classes C:/path/to/frozen/warden/classes --output C:/path/to/private/evidence `
  --prefix fresh-pilot --fixture-accept
```

The command makes paid vendor calls. It restores only `pilot-queue.mjs` from the
recorded HEAD between runs and refuses existing IDs. Keep the build, personal
policy, profiles, templates and schemas frozen until all runs finish. Do not run a
probe or another controller in the same checkout. Run unrelated probes in a separate
Orca worktree. Warden's execution lease enforces that boundary for `probe orca`.

`--fixture-accept` records **automated test-operator acceptance**, after both actual
reviews and an independent Node check. It is not human user acceptance, and lands
nothing. Legitimate stopped runs remain in the experiment with an abort outcome;
read-only mutations require inspection and stop the driver. Setup-contaminated
attempts must be retained as excluded evidence, never silently counted as successes.

Outputs are `paired-baseline.json`, `receipts.json`, per-run command/decision logs,
and Warden's `comparison.json`. Operator time remains unknown. Unreported vendor
costs remain unknown; the OpenRouter account balance is separate account evidence.
Three complete pairs permit descriptive comparison only: they establish neither a
quality ranking, escaped-defect rate nor statistical significance.

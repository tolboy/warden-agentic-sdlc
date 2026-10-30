# API profile examples

These are templates for an operator's own models and credentials, not a current model
catalog or a recommended personal roster. A model ID in an example may disappear,
change gateway spelling or be inaccessible to a particular key. Keep that distinction
when refreshing a catalog: listing does not prove access, and a pong does not prove
coding-tool capability.

- `openrouter-review.yaml`, `gonkarouter-review.yaml` and `joingonka-review.yaml` describe
  read-only text roles. Configure your own connection and verify the exact model.
- `joingonka-coder.yaml` illustrates the verified native OpenCode implementer path.
  Set the installed pinned executable and model/connection explicitly, then run the native
  verification from a disposable project. Only its matching receipt grants coding admission.
- `opencode-implementer.yaml` is the **historical offline contract** from 2026-10-26.
  It deliberately lacks the native executable and is not eligible for execution. It is
  retained to exercise compatibility/refusal behavior, not as the starting coding profile.
- `jev-classifier.yaml` illustrates a decision profile with a pinned requested model and
  reported identity. It is not a worker and cannot fill an implementation/review role.
  Updating a model list does not authorize a new classifier version or reported snapshot.

See [current API setup and verification](../../docs/API-CONNECTIONS.md),
[Jev routing](../../docs/JEV-ROUTING.md) and
[historical example guidance](../README.md). The legacy offline adapter and the verified
native adapter have different admission rules; a failure of the former is not evidence
that native OpenCode is unavailable. Neither path supplies a strict spending guarantee
or transfers API credentials to a remote Orca worker.

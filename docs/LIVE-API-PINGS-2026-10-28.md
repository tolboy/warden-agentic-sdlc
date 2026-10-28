# Live API pings, 2026-10-28 (Stage 2)

One `warden check --profile NAME` per API model used by the box profiles, run through the box
launcher (keys loaded from the box secret store, never printed) with a temporary
`WARDEN_CONFIG_HOME` holding copies of the connections and the example reviewer profiles.
Pings only; no coding run was made. OpenRouter was not pinged: no box profile uses it.

Times are America/Chicago (CDT). Branch `grok/api-resilience-stage2`.

| Time | Service | Model | Profile | Result | HTTP | Attempts | Ping limit | Reasoning | Tokens in/out | Duration | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 17:52 | GonkaRouter | `deepseek-ai/DeepSeek-V4-Flash-0731` | `gonkarouter-review` | pong, exact model | 200 | 1 | 2048 | yes | 139 / 73 | 2.2 s | unknown |
| 17:52 | GonkaRouter | `zai-org/GLM-5.3-Flash` | `gonkarouter-glm-review` (copy of the example with the GLM id) | pong, exact model | 200 | 1 | 2048 | yes | 74 / 85 | 4.8 s | unknown |
| 17:52 | JoinGonka | `zai-org/GLM-5.3-Flash` (the `joingonka-coder` model) | `joingonka-review` | pong, exact model | 200 | 1 | 2048 | yes | 74 / 128 | 32.6 s | unknown |

Notes:

- All three used the `check` retry policy; none needed a retry, so the retry path was not
  exercised live (it is covered by the fake-HTTP suite `api-resilience`).
- Neither service reported a price, so cost is recorded as `unknown`, not 0.
- Request ids were recorded in the receipts (GonkaRouter `req-<digits>`, JoinGonka short ids).
- The earlier GonkaRouter GLM 502 did not recur in this ping.
- JoinGonka took about 30 s for a pong; the check deadline (90 s) leaves room, but slow
  answers there are normal, not a failure.

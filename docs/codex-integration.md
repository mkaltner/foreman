# Codex integration

## Choice

Foreman uses the installed `codex app-server` over its default JSONL stdio
transport. The official Codex manual describes app-server as the interface for
rich clients needing conversation history, approvals, and streamed agent
events. The general OpenAI SDK is not an interface to local Codex CLI threads.
The Codex SDK is aimed at programmatic coding workflows; app-server exposes the
thread history and control surface Foreman needs.

Sources:

- [Codex app-server manual](https://developers.openai.com/codex/codex-manual.md#codex-app-server)
- [Codex open-source app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server)

The installed version verified on 2026-07-26 is `codex-cli 0.145.0`. Foreman
generated that binary's JSON schemas with:

```sh
codex app-server generate-json-schema --experimental --out /tmp/schema
```

## Methods and events

Foreman initializes each connection with `initialize` and `initialized`, then
uses:

- `thread/list`, `thread/read`, `thread/start`, `thread/resume`,
  `thread/archive`, and `thread/delete`;
- `turn/start`, `turn/steer`, and `turn/interrupt`;
- `thread/status/changed`, `turn/started`, and `turn/completed`;
- `item/started`, `item/completed`, `item/agentMessage/delta`, and command
  output deltas.

The server also exposes command/file approval requests,
`item/tool/requestUserInput`, and permission approval requests. Foreman detects
these as waiting states but does not answer them in this milestone.

## Installed-version observations

- App-server is officially described as primarily for development/debugging and
  may change; Foreman keeps all version-sensitive parsing in one adapter.
- `turn/start` can return before a turn is steerable. Wait for `turn/started`
  before enabling steer or interrupt.
- Ephemeral threads reject `thread/read` with `includeTurns: true`.
- `thread/read(includeTurns: true)` provides persisted messages and lossy
  command/tool history. Codex remains authoritative; Foreman stores no
  transcript.

## Proof result

`scripts/codex_poc.py` passed against the real installed CLI. It initialized and
cleanly stopped app-server, listed existing threads, read persisted turns,
created an ephemeral thread in a temporary Git repository, sent a harmless
prompt, streamed `FOREMAN_POC_OK`, observed completed terminal status, accepted
a steer after `turn/started`, and completed an interrupted turn with status
`interrupted`.

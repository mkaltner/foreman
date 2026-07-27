# Codex integration

## Choice

Foreman first attempts the installed Desktop `codex app-server` over its Unix
control socket and standard WebSocket frames. The official Codex manual describes
app-server as the interface for
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
- `model/list`;
- `permissionProfile/list`;
- `thread/status/changed`, `turn/started`, and `turn/completed`;
- `item/started`, `item/completed`, `item/agentMessage/delta`, and command
  output deltas.

At startup, Foreman generates the installed app-server JSON schema and only
advertises optional archive/delete capabilities present in that Codex version.
If schema discovery is unavailable, those destructive UI actions stay disabled.

The server also exposes command/file approval requests,
`item/tool/requestUserInput`, and permission approval requests. Foreman detects
these as waiting states but does not answer them in this milestone.

The default socket is
`$CODEX_HOME/app-server-control/app-server-control.sock` (or Codex's normal
home when `CODEX_HOME` is unset). `FOREMAN_CODEX_SOCKET` overrides it. Foreman
only attaches to this path. It never unlinks, replaces, or launches an
app-server on the Desktop socket; a listener with a failed WebSocket handshake
is an explicit attach failure. A present but stale Desktop socket is likewise an
explicit attach failure and is left untouched. Only when the Desktop path is
absent does Foreman use `~/.local/state/foreman/codex-app-server.sock` for an
independent owned runtime and report
`SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`. A closed connection triggers bounded
reconnect, state refresh, and thread resubscription without prompt/control
replay.

## Installed-version observations

- App-server is officially described as primarily for development/debugging and
  may change; Foreman keeps all version-sensitive parsing in one adapter.
- `turn/start` can return before a turn is steerable. Wait for `turn/started`
  before enabling steer or interrupt.
- A new zero-turn thread may reject `thread/read` with `includeTurns: true`;
  Foreman immediately projects the `thread/start` result as an empty
  conversation and falls back to metadata-only reads until history exists.
- `thread/read(includeTurns: true)` provides persisted messages and lossy
  command/tool history. Codex remains authoritative; Foreman stores no
  transcript.
- `turn/start` supports per-turn permissions, approval policy/reviewer, model,
  and effort; `turn/steer` supports image input but not route overrides in the
  verified schema.
- Access presets use allowed `:workspace` and `:danger-full-access` permission
  profiles. Standard approval routes to the user, automatic approval uses
  `auto_review`, and full access disables approval prompts.

## Proof result

`scripts/codex_poc.py` is the opt-in installed-Codex proof. It attaches to the
requested Desktop socket or uses the separate Foreman fallback, opens an empty
ephemeral thread, and sends a harmless text prompt. `--attach-only` forbids the
fallback; `--with-image` also verifies inline image input.

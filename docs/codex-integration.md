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

The installed version verified on 2026-07-30 is `codex-cli 0.145.0`. Foreman
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

Foreman handles these server-initiated requests separately from notifications:

- `item/commandExecution/requestApproval`;
- `item/fileChange/requestApproval`;
- `item/permissions/requestApproval`;
- `item/tool/requestUserInput` (recognized but not generally answerable);
- `mcpServer/elicitation/request` (recognized; only decline/cancel is exposed).

## Installed approval contract

The generated 0.145.0 schemas define command decisions as `accept`,
`acceptForSession`, `decline`, `cancel`, structured
`acceptWithExecpolicyAmendment`, or structured
`applyNetworkPolicyAmendment`. Foreman uses `availableDecisions` when supplied;
only command/file legacy requests without that field use the installed fallback
decision set. File-change decisions are `accept`, `acceptForSession`, `decline`,
or `cancel`.

Command and file responses use the original request ID and
`{"result":{"decision":...}}`. Permission responses instead use
`{"result":{"permissions":<requested subset>,"scope":"turn|session"}}`;
an empty permissions object denies all. Permission grants are structurally
checked against the original request. `serverRequest/resolved` carries
`{threadId,requestId}` and is the authoritative resolution signal.

`item/tool/requestUserInput` requires an `answers` map and has no schema-valid
generic decline/cancel representation, so Foreman explains that another client
is required. Arbitrary MCP forms remain unsupported, but the installed MCP
elicitation response contract permits safe `decline` or `cancel` with null
content. No approval is inferred from a generic waiting status.

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
- Approval state is connection-bound and in memory. Reconnect, disconnect,
  interruption, replacement, or turn completion expires uncertain mappings;
  no response is retried or replayed.

## Proof result

`scripts/codex_poc.py` is the opt-in installed-Codex proof. It attaches to the
requested Desktop socket or uses the separate Foreman fallback, opens an empty
ephemeral thread, and sends a harmless text prompt. `--attach-only` forbids the
fallback; `--with-image` also verifies inline image input.

The automated fake app-server proof covers command acceptance, file decline,
permission subset grant, exact upstream-ID correlation,
`serverRequest/resolved`, disconnect, and Desktop-first resolution.

Disposable installed-Codex proof on 2026-07-30 produced all three supported
request classes. A network command emitted a command request advertising
`accept`, a structured exec-policy amendment, and `cancel`; a one-time accept
resolved the original request and the turn continued. A read-only temporary Git
workspace emitted a file-change request advertising all four file decisions;
decline resolved it and the proposed file was not created. With Codex's
`request_permissions_tool` development feature enabled, a combined network and
temporary-write request emitted a permission approval; granting only network
access for the turn resolved it and the turn completed. A harmless local
command under the default policy did not request approval, confirming that the
selected access mode does not itself fabricate approval state.

The production web bundle was also paired with a disposable Foreman service in
Chromium, opened the concrete Dashboard attention item, focused the inline
command card, and rendered only the supplied decisions. Physical-device Android
validation and live Codex Desktop-first resolution were not available in this
test environment; those results must not be inferred from the automated race
tests.

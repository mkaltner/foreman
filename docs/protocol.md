# Foreman protocol v1

Foreman uses the same versioned messages over two transports:

- UTF-8 newline-delimited JSON over raw TCP on port `8765`;
- one UTF-8 JSON message per WebSocket text frame at `/ws` on port `8766`.

Every logical message is at most 16 MiB and has:

```json
{"version":1,"id":"req-1","type":"session.list","payload":{}}
```

Responses echo `id` and append `.result` to the request type. Errors use type
`error` with `payload.code` and `payload.message`. Server events omit `id`:

```json
{"version":1,"type":"session.event","payload":{"sessionId":"…","event":{}}}
```

Before authentication a client may send `hello`, `pair`, `authenticate`, and
`ping`. All other requests require a successful pair or authentication.
`hello.codexRuntime` is either `SHARED_DESKTOP_LIVE_STATUS_AVAILABLE` for an
attach to the configured Desktop socket or
`SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE` for Foreman's independent fallback.

The browser transport rejects binary frames and malformed, oversized, or
unsupported messages with the same protocol error envelope where the
WebSocket state permits it. Application operations are never exposed as REST
resources. `GET /health` is operational status only and reports the Codex
connection/runtime mode without paths, tokens, prompts, or logs.

Implemented types:

- `hello`, `pair`, `authenticate`, `ping`;
- `repository.list`;
- `model.list`;
- `access.list`;
- `service.status`;
- `client.list`, `client.revoke`;
- `session.list`, `session.search`, `session.read`, `session.start`, `session.resume`,
  `session.subscribe`, `session.unsubscribe`, `session.archive`, `session.delete`;
- `turn.prompt`, `turn.steer`, `turn.interrupt`.

`pair` accepts `pairingKey` and `deviceName`, then returns one persistent
`deviceToken`. `service.status` returns authenticated, user-facing host health:
Foreman and Codex versions, uptime, runtime mode, listener ports, repository
root, aggregate browser/TCP client counts, last Codex event and successful
request times, attach time, loaded/subscribed thread counts, and narrow runtime
ownership diagnostics. Authenticated browsers receive `service.event` when safe
aggregate client counts change; raw-TCP behavior is unchanged. Status never
includes pairing material, device tokens, environment variables, logs, source
addresses, or unrestricted process details.

`client.list` returns safe paired-client projections: opaque client ID, saved
label, browser/Android type, pairing time, live connection count, and whether
the caller uses that token. It includes offline paired tokens so they can be
revoked. `client.revoke` accepts only the opaque `clientId`, deletes that token,
and immediately disconnects all live connections authenticated with it. Neither
request exposes a token or digest, and revocation does not alter Codex sessions.

Session summaries include authoritative active-turn start, terminal time,
duration, safe failure, and wait metadata when Codex supplies it.
`session.event` carries normalized status, lifecycle, assistant delta, public
activity, and command/tool item events.
Reconnect is intentionally a fresh list/read/subscribe sequence; there are no
cursors, replay logs, or persistent dashboard history.

Approval support keeps protocol version 1. Authenticated clients use:

- `approval.list` → `{approvals:[...]}` for current pending/submitting requests;
- `approval.respond` with `{approvalId,decision}`;
- `approval.requested`, `approval.updated`, and `approval.resolved` server events.

Approval IDs are opaque Foreman IDs, never upstream JSON-RPC IDs. Projections are
bounded and include the request class, session/turn/item correlation, safe
display details, and only available decisions. Command/file responses identify
an advertised decision, including structured policy amendments. Permission
responses contain `type: grant`, an explicit turn/session scope, and only a
selected subset, or `type: deny`. Unknown, stale, duplicate, malformed, or
unadvertised responses fail. All authenticated clients observe the winning
lifecycle; older clients may ignore these event types and keep generic waiting
UI.

`session.search` is authenticated and accepts `query`, one canonical
`repository`/workspace path or `null`, a `statuses` array, `dateFrom`, `dateTo`,
and a requested `limit`. Search is case-insensitive plain substring matching.
It matches compact titles, canonical workspace paths, and normalized visible
user, assistant, command, or tool text. Reasoning, raw events, raw command
output, images, tokens, and internal errors are not searched. Results contain
summary projections plus at most three 200-character snippets; they never
contain full histories or image data. The server caps results at 100 and lazy
transcript reads at 100 candidates, cancels an older in-flight search from the
same client, and maintains no persistent index or transcript mirror.

`session.archive` moves an inactive Codex thread out of the active list and is
reversible through Codex's `thread/unarchive` API. `session.delete` requires
`confirm: true` and permanently deletes the inactive thread plus its spawned
descendants. Foreman rejects both operations while a session is working or
waiting for input. Per-session locks serialize this check and mutation with
prompt, steer, and interrupt requests from other connected Foreman clients.

`model.list` returns only picker fields from Codex's installed catalog.
`access.list` returns the access levels allowed by Codex's installed permission
profiles. A `turn.prompt` may include `accessLevel`, `model`, and
`reasoningEffort`; `turn.steer` keeps the active turn's route. Both accept up to
four images:

```json
{"text":"Inspect this","images":[{"mimeType":"image/jpeg","data":"<base64>"}]}
```

JPEG, PNG, and WebP payloads are accepted, with an 8 MiB combined encoded
limit. Foreman converts them to Codex inline data-URL image items and does not
persist or log image bytes.

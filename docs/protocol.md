# TCP protocol v1

Foreman uses UTF-8 newline-delimited JSON over raw TCP. Every frame is at most
16 MiB and has:

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

Implemented types:

- `hello`, `pair`, `authenticate`, `ping`;
- `repository.list`;
- `model.list`;
- `access.list`;
- `session.list`, `session.read`, `session.start`, `session.resume`,
  `session.subscribe`, `session.archive`, `session.delete`;
- `turn.prompt`, `turn.steer`, `turn.interrupt`.

`pair` accepts `pairingKey` and `deviceName`, then returns one persistent
`deviceToken`. `session.event` carries normalized status, assistant delta, and
command/tool item events. Reconnect is intentionally a fresh list, read, and
subscribe sequence; there are no cursors or replay logs.

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

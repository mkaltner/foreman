# TCP protocol v1

Foreman uses UTF-8 newline-delimited JSON over raw TCP. Every frame is at most
1 MiB and has:

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
waiting for input.

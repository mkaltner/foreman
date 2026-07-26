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
  `session.subscribe`;
- `turn.prompt`, `turn.steer`, `turn.interrupt`.

`pair` accepts `pairingKey` and `deviceName`, then returns one persistent
`deviceToken`. `session.event` carries normalized status, assistant delta, and
command/tool item events. Reconnect is intentionally a fresh list, read, and
subscribe sequence; there are no cursors or replay logs.

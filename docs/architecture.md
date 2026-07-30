# Architecture

Foreman has one Linux service and thin Android and browser clients:

```text
Android Foreman ── authenticated JSONL/TCP :8765 ── Linux Foreman
Browser Foreman ── static HTTP + authenticated WS :8766 ─┘
                                                    │
                                                    └─ WebSocket/Unix socket
                                                               │
                                                    Desktop codex app-server
```

The Linux process treats Codex's Desktop control socket as attach-only. It never
unlinks, replaces, or launches a process on that path. When attachment is
unavailable it may own a child only on Foreman's separate fallback socket and
classifies that mode as `SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`. It switches
directly on message type, normalizes only user-visible thread items, and pushes
live events to subscribed connections. On reconnect it asks Codex for current
threads and history again, resubscribes, and never retries a prompt or control.

Both clients use the same protocol-v1 request, result, error, and event shapes.
Only framing differs: TCP uses newline-delimited JSON and WebSocket uses one
text message per frame. The browser assets and `/health` share the WebSocket
listener; there is no application REST API, browser backend, database, cookie,
or server-side rendering layer.

Linux files:

- `foreman_service.py`: listener, request switch, repository discovery;
- `codex.py`: app-server lifecycle, calls, and event normalization;
- `approvals.py`: bounded in-memory approval projection, correlation, and validation;
- `protocol.py`: bounded JSONL frames;
- `state.py`: one-time pairing, opaque client IDs, and hashed device tokens;
- `foreman`, `install.sh`, and `foreman.service`: operation and installation.

The React/TypeScript SPA under `web/` uses native WebSocket and local component
state. It reloads Codex-authoritative sessions and history after reconnect,
resubscribes to the open session, and never replays prompt, steer, interrupt,
archive, or delete requests. Its committed `web/dist` output is copied into the
installed data directory so Node isn't part of the runtime.

Android and web keep a client-local registry of independently paired hosts. A
stable random local ID identifies each host in preferences, notification routes,
and browser URLs; tokens remain Keystore-encrypted on Android and browser-local
on web and never enter URLs. Switching stops subscriptions, closes the old
connection, discards transient sessions and approvals, restores the selected
host's preferences, and then authenticates and reloads. There is no coordinator,
cross-host session model, or backend persistence for this registry.

The dashboard uses the same session-summary projection rather than a parallel
session model. It subscribes only to working and waiting sessions, coalesces
high-frequency public activity updates, and keeps transcripts out of monitoring
state. One shared browser clock updates active-turn, wait, freshness, and uptime
labels. Discovered repository metadata separates Git repositories from other
session workspaces. Lifecycle events observed during the browser session feed a
coalesced 20-entry recent list; stale-turn observation and feed entries are not
persisted by the service.

### Multi-host overview connections

The unified overview is a client-side projection. No Foreman service knows
about another host, and every projected session uses the compound
`hostId + sessionId` identity. Small host snapshots (counts, health, versions,
runtime mode, timestamps, and attention metadata) are cached locally;
transcripts and tokens are not copied into the overview cache. A disconnected
snapshot is always labeled stale.

The web client permits at most four simultaneous Foreman WebSockets: the
selected host plus up to three overview sockets. With more than four saved
hosts, the three background slots rotate every 60 seconds. A host rotated out
keeps its last snapshot as stale until it is checked again. Browser suspension
may delay rotation and reconnection.

Android permits at most two simultaneous TCP connections while the activity is
foregrounded: the normal selected-host connection plus one sequential overview
health probe. Inactive hosts are checked at most once per 60-second pass and
their counts are immediately presented as stale because the probe disconnects.
The probe is cancelled in `onStop`; cached results remain available offline.
Background turn monitoring remains scoped to its explicitly monitored active
host and does not turn the overview into a persistent multi-host service. When
that monitoring service can occupy the second connection, inactive-host probes
pause and their existing stale snapshots are the fallback.

Completion and attention notifications retain their existing opt-in behavior.
They always carry both host and session IDs, but overview sockets and probes do
not generate additional notifications. Opening a notification or a combined
attention row selects the saved host before opening its session.

Pending approvals live only in the Codex adapter. Each has an opaque Foreman ID,
the exact upstream JSON-RPC ID and connection, and one small lock. The adapter
registers before broadcasting, validates one response, sends it on the original
connection, and waits for `serverRequest/resolved`. Disconnect or turn cleanup
expires the mapping without replay. There is no approval history or Foreman
permission policy.

Android uses one Compose activity, one connection/protocol file, a small image
processor, and one Keystore-backed multi-host token store. Its cache is in memory and
disposable. When the user enables active-turn notifications, an on-demand
foreground service opens its own authenticated connection and subscribes only
to turns the user opens or starts on the active host. Approval notifications carry
host and session identity, are deduplicated by opaque request ID, contain no
command or path, and are cancelled when another
client resolves the request. The service stops after every subscribed turn is
terminal; it is not an always-running poller or push client.

Only pairing keys and safe client authentication metadata (opaque IDs, token
SHA-256 digests, labels, client types, and pairing times) are persisted by Linux.
The authenticated client projection never returns a token or digest. Codex stores
transcripts; Git supplies repository state. Foreman never offers arbitrary
commands or Git writes.

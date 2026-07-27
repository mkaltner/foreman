# Architecture

Foreman has two processes:

```text
Android Foreman ── authenticated JSONL/TCP :8765 ── Linux Foreman
                                                    │
                                                    └─ JSONL/stdio ── codex app-server
```

The Linux process owns one app-server child and a small TCP server. It switches
directly on message type, normalizes only user-visible thread items, and pushes
live events to subscribed connections. On restart it asks Codex for current
threads and history again.

Linux files:

- `foreman_service.py`: listener, request switch, repository discovery;
- `codex.py`: app-server lifecycle, calls, and event normalization;
- `protocol.py`: bounded JSONL frames;
- `state.py`: one-time pairing and hashed device tokens;
- `foreman`, `install.sh`, and `foreman.service`: operation and installation.

Android uses one Compose activity, one connection/protocol file, and one
Keystore-backed token store. Its cache is in memory and disposable. When the
user enables active-turn notifications, an on-demand foreground service opens
its own authenticated connection and subscribes only to turns the user opens
or starts. The service stops after every subscribed turn is terminal or needs
attention; it is not an always-running poller or push client.

Only pairing keys, device-token SHA-256 digests, and device labels are persisted
by Linux. Codex stores transcripts; Git supplies repository state. Foreman never
offers arbitrary commands or Git writes.

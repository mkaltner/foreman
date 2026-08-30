# Architecture

Foreman has one Linux service and thin Android and browser clients:

```text
Android Foreman ── authenticated JSONL/TCP :8765 ── Linux Foreman
Browser Foreman ── static HTTP + authenticated WS :8766 ─┘
                                                    │
                                                    ├─ WebSocket/Unix socket
                                                    │          │
                                                    │  Desktop codex app-server
                                                    │
                                                    └─ bounded JSONL/stdio
                                                               │
                                                    Claude Agent SDK bridge
```

The Linux service also owns one optional Node companion for the official Claude
Agent SDK. Additive protocol-v1 provider operations expose that adapter to both
clients. Older Android builds can ignore the capabilities and provider events.
The companion uses bounded request-ID JSON over stdio, persists only
Claude `{sessionId, cwd}` mappings, and never mirrors transcripts or attaches to
external live processes. If it fails, Codex stays available, active Claude work
becomes resumable, and no query is replayed.

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

Release identity is separate from protocol compatibility. The candidate manifest
in `release.properties` aligns the Linux status version, Android version
name/code, web package version, and unchanged protocol version. A prerelease can
advance without changing protocol v1; a protocol change requires an intentional
coordinated client/service update and compatibility documentation.
Web and Android builds read `foremanVersion` from that manifest. Android embeds
the checked-out commit by default, while reproducible artifact builders can set
`FOREMAN_BUILD_COMMIT` for either client. Their About views therefore retain the
client build identity without a server connection and label the connected
server version separately. Committed web assets omit the commit unless the
builder supplies it so rebuilding those assets remains deterministic.
`releaseBuild=false` labels clients as development builds even when they share
the current published version number; a reviewed release PR is the only place
that should set it to `true`.

Linux files:

- `foreman_service.py`: listener, request switch, repository discovery;
- `codex.py`: app-server lifecycle, calls, and event normalization;
- `approvals.py`: bounded in-memory approval projection, correlation, and validation;
- `inputs.py`: verified structured-input normalization and response validation;
- `protocol.py`: bounded JSONL frames;
- `diagnostics.py`: fixed-message, 100-entry sanitized in-memory event ring;
- `claude_code.py` and `claude_bridge/bridge.mjs`: optional Linux-only Claude SDK lifecycle boundary;
- `session_identity.py`: the small explicit `provider + hostId + sessionId` identity value;
- `state.py`: one-time pairing, opaque client IDs, and hashed device tokens;
- `foreman`, `install.sh`, and `foreman.service`: operation and installation.

The React/TypeScript SPA under `web/` and the Compose app under `android/` reload
provider-authoritative sessions and history after reconnect, resubscribe to the
open compound identity, and never replay prompt, steer, interrupt, archive, or
delete requests. Normal and archived Codex discovery are separate bounded
provider scopes: clients request `thread/list` with `archived: true` only while
the Archived filter is selected, never merge its cursor stream with normal
pagination, and reconcile both lists from lifecycle events and authoritative
reloads. Archived history is projected through `thread/read` plus bounded turn
history without `thread/resume`, subscription, approvals, inputs, or other
session mutations. Explicit restore is serialized on the same compound
provider/session lock as active operations and sends one `thread/unarchive`.
Claude deletion is provider-aware and
uses the official SDK; Claude archive is absent because the SDK has no matching
operation. Durable routes, selection, drafts, subscriptions, notifications, and
relevant local UI state use `hostId + provider + sessionId`; legacy Android keys
migrate into the Codex namespace, and Claude and Codex IDs are never treated as
the same namespace. The committed `web/dist` output is copied into
the installed data directory. Node is optional at runtime and required only for
enabled Claude support.

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

The Linux service does persist a bounded temporal overlay of at most 500
session timestamp records per provider. Activity and terminal timestamps are
keyed by provider plus stable session ID, restored before provider discovery,
and removed with the matching provider session. Provider discovery may advance
known activity, an explicit active state may clear a prior terminal boundary,
and missing provider timestamps cannot substitute service startup or observation
time. Stored activity provenance distinguishes complete provider projections
from genuine live work. This lets an inactive Codex projection repair a legacy
restart-time value while preventing older reconciliation from replacing known
live activity. `observedAt` remains projection metadata rather than session
activity.

### Multi-host overview connections

The unified overview is a client-side projection. No Foreman service knows
about another host, and every provider-aware projected session uses the compound
`hostId + provider + sessionId` identity. Small host snapshots (counts, health, versions,
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
Android notification identities and routes carry host, provider, and session
IDs; overview sockets and probes do not generate additional notifications.
Each authenticated UI connection may publish one ephemeral focused
provider/session pair. The service broadcasts only the deduplicated pairs, not
client identities, and removes a pair on blur/background, navigation,
disconnect, or token revocation. Web and Android notification monitors suppress
only events matching one of those focused pairs.
Claude alerts derive only from authoritative provider-tagged lifecycle for a
monitored Foreman-managed query. Merely resumable external sessions do not
notify. Opening a notification or a combined attention row selects the saved
host and provider before opening its session.

Pending approvals and inputs live only in the Codex adapter. Each has an opaque Foreman ID,
the exact upstream JSON-RPC ID and connection, and one small lock. The adapter
registers before broadcasting, validates one response, sends it on the original
connection, and waits for `serverRequest/resolved`. Disconnect or turn cleanup
expires the mapping without replay. There is no approval/input history or Foreman
permission policy.

Android uses one Compose activity, one connection/protocol file, a small image
processor, and one Keystore-backed multi-host token store. Its cache is in memory and
disposable. When the user enables active-turn notifications, an on-demand
foreground service opens its own authenticated connection and subscribes only
to turns the user opens or starts on the active host. Provider lifecycle
notifications carry compound identity; Codex approval notifications carry host
and session identity, are deduplicated by opaque request ID, contain no
command or path, and are cancelled when another
client resolves the request. The service stops after every subscribed turn is
terminal; it is not an always-running poller or push client.

Only pairing keys and safe client authentication metadata (opaque IDs, token
SHA-256 digests, labels, client types, and pairing times) are persisted by Linux.
The authenticated client projection never returns a token or digest. Codex stores
transcripts; Git supplies repository state. Foreman never offers arbitrary
commands or Git writes.

The only remote host mutation is the optional, authenticated Foreman restart.
It is disabled by default, has no command or unit-name parameter, flushes a
scheduled acknowledgement, and enqueues a user-systemd restart of only
`foreman.service`. Client reconnect is the completion signal. Desktop Codex,
raw journals, arbitrary shell, reboot, upgrades, configuration, files, roles,
and persistent audit storage are outside this surface.

Install replacement is transactional at the application-directory boundary.
Configuration and pairing state live outside that boundary and are never part
of the rollback move; obsolete application files disappear because the staged
payload replaces the prior directory as a unit. Service activation is the
commit point, and a failed activation restores the prior payload, launcher, and
unit before restarting it.
Listener/client closure and Codex detachment run concurrently under a bounded
application shutdown deadline shorter than the systemd unit's stop timeout.
Reaching that deadline records only a fixed sanitized diagnostic and lets
systemd finish the process teardown; Foreman still never signals Desktop's
runtime.

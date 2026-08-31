# Foreman protocol v1

Protocol version 1 is independent of the `1.0.0` application version.
The release verifier checks the Linux, web, and Android protocol constants
against `release.properties`; this release does not introduce a protocol
version bump.

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
- `service.status`, `release.check`;
- `usage.status`;
- `diagnostics.list`, `service.restart`;
- `client.list`, `client.revoke`;
- `session.presence`;
- `session.list`, `session.search`, `session.read`, `session.start`, `session.resume`,
  `session.subscribe`, `session.unsubscribe`, `session.settings`, `session.archive`,
  `session.restore`, `session.delete`;
- `turn.prompt`, `turn.steer`, `turn.interrupt`;
- `approval.list`, `approval.respond`;
- `input.list`, `input.respond`.

The following authenticated, additive provider operations also use protocol
version 1:

- `provider.list`, `provider.configure`;
- `provider.session.list`, `provider.session.read`, `provider.session.start`,
  `provider.session.resume`, `provider.session.settings`, `provider.session.subscribe`,
  `provider.session.unsubscribe`, `provider.session.delete`;
- `provider.turn.prompt`, `provider.turn.interrupt`;
- `provider.model.list`, `provider.permission.list`.

Existing unprefixed operations retain their Codex request and response shapes.
Provider-aware requests always include `provider`; the service never infers a
provider from a session ID. Unsupported provider operations return the normal
error envelope with code `capabilityUnavailable`. Older protocol-v1 clients can
ignore the new catalog, operations, provider fields, and events.

`service.status.releaseUpdates` is a bounded release-discovery projection. It
contains `observedAt`, `stale`, `refreshStatus` (`idle`, `checking`, or
`unavailable`), an optional safe unavailability reason, and `server` and
`android` component entries. Each component contains only `supportedRelease`
and `newestRelease`; a release contains the normalized SemVer, tag, bounded
title and publication time, an official
`https://github.com/mkaltner/foreman/releases/tag/...` notes URL, and whether the
required uploaded artifact plus `SHA256SUMS` is available. Raw GitHub payloads,
asset URLs, tokens, arbitrary repositories, and installation operations are not
projected.

Authenticated `release.check` requests a manual refresh and returns the same
projection. The service coalesces concurrent calls and throttles repeated manual
checks. A 304 renews the observation time. Offline, timeout, malformed JSON,
oversize response, HTTP error, and rate-limit failures retain the last validated
projection as stale; clients with no cache receive an honest unavailable state.
The operation is additive within protocol v1 and does not block other requests.
`service.status.foremanReleaseBuild` lets clients distinguish an installed
source/development checkout from an official release build.

`provider.list` reports the adapters actually available on the authenticated
host. Each bounded entry contains an ID, display name, separate `enabled` and
`available` states, safe
version fields, supported capabilities, and explicit limitations. Claude Code
unavailability is non-fatal and is reduced to one safe reason such as
`cli-missing`, `node-missing`, `sdk-missing`,
`authentication-unavailable`, or `adapter-unavailable`; no paths, environment,
logs, or traces are returned. Pairing is unchanged: one host device token grants
access to every provider available on that host.

`provider.configure` accepts an exact provider ID and boolean `enabled` value.
The host persists this preference, starts or stops the corresponding adapter,
and publishes refreshed `provider.event` and `usage.event` projections. At least
one provider must remain enabled. A provider with active or waiting work cannot
be disabled. Disabled providers remain in the catalog but expose no capabilities;
their sessions and account usage are omitted without deleting provider data.

Archived Codex support is advertised explicitly and independently. A provider
catalog entry includes `session.archived.list` only when the installed schema
defines the `archived` field for `thread/list`, and includes `session.restore`
only when it advertises `thread/unarchive`. `hello.capabilities` exposes the
matching `archivedDiscovery` and `restore` compatibility flags. Provider name
alone never enables either surface; disabled or unavailable providers expose no
capability. Claude Code advertises neither capability.

Every provider-aware session projection and event contains `provider` and
`sessionId`. Client identity is `hostId + provider + sessionId`; durable web
routes therefore use `/sessions/<provider>/<sessionId>?host=<hostId>`. Tokens
never enter routes. Provider-aware events retain the existing event envelope:

```json
{"version":1,"type":"session.event","payload":{"provider":"claude-code","sessionId":"…","event":{"kind":"assistant.delta","text":"Hello"}}}
```

Session summaries keep three timestamp meanings separate. `lastActivity` is
provider/session activity, `terminalAt` is the latest terminal turn boundary,
and `observedAt` is only when Foreman produced the summary. Live events use
`activityAt` for the activity being applied and `observedAt` for receipt by
Foreman; `observedAt` is never an implicit `activityAt`. Metadata-only events,
including route, usage, goal, MCP startup, initialization, and unknown
thread-scoped notifications, cannot advance activity. The service durably
retains known activity, terminal values, and bounded provenance per
provider/session. Values merge monotonically for live work and partial provider
data. A complete inactive Codex projection may replace a legacy or
provider-derived value to repair restart-time corruption, but never replaces a
known live activity value with an older timestamp. When partial provider data
has no activity timestamp, Foreman retains a known value, otherwise falls back
only to a provider terminal or creation timestamp, and finally leaves the
activity unavailable. Web and Android sort the restored server values while
placing waiting and working sessions ahead of inactive work.

Approval and input event payloads also include explicit `activityAt` and
`observedAt` fields alongside the request object. Because requesting or
resolving attention is genuine session work, clients apply the server
`activityAt`; they never substitute a client clock or the envelope's
`observedAt`.

`provider.event` publishes a refreshed bounded provider catalog when Claude's
bridge availability changes. Claude session events cover status, assistant
deltas, conservative tool cards, permission-required/denied state, completion,
failure, and interruption. Reconnect performs list/read/subscribe
reconciliation and never replays a prompt.

Claude `provider.session.list` discovers official SDK session metadata without
loading every transcript. `provider.session.read` uses official bounded history
access and projects only visible user and assistant messages, safe tool cards,
permission markers, and terminal query state. Hidden reasoning, unrestricted SDK
objects, raw tool input/output, and credentials are excluded. Claude
`provider.session.delete` requires `confirm:true` plus the exact repository ID,
rejects an active Foreman-owned query, and uses the official SDK deletion API.
It permanently removes managed or external resumable history and its
subagent-transcript directory. The SDK has no archive/unarchive operation, so
Foreman does not present a Claude archive action. Claude session search, images,
notification events, and approval responses are not supported by this surface.

Codex `provider.session.list` accepts `scope: "normal" | "archived"`, defaulting
to `normal`. Normal discovery never returns archived threads. Archived scope is
a separate, bounded `thread/list` traversal with `archived: true`; it is not
combined with the normal cursor stream. Each archived summary has
`archived: true`, `readOnly: true`, and only the advertised `session.read` and
optional `session.restore` capabilities. `provider.session.read` with archived
scope reads the transcript without resuming or subscribing. Direct clients may
use the equivalent additive `archived: true` fields on the unprefixed
`session.list`, `session.read`, and `session.search` operations.

Claude model listing is an adapter-supported, non-dynamic list containing
`sonnet` and `haiku`. Permission listing returns the exact SDK values `default`,
`dontAsk`, `acceptEdits`, `plan`, `auto`, and `bypassPermissions`; the final mode
is marked high risk and is never selected automatically.

`session.start` accepts a discovered Git repository ID. The special ID `.` starts
the session in the configured repository root, allowing a session before any Git
repositories exist while keeping the workspace inside that configured boundary.
Optional `model`, `reasoningEffort`, and `accessLevel` fields set the new
session's route before it is returned.

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

`session.presence` publishes the single provider/session currently visible and
focused on that authenticated connection, or clears it when both fields are
omitted. Its result and `session.presence.event` expose only the deduplicated
focused provider/session pairs, never client or device identities. Presence is
ephemeral, is removed when the connection closes or its token is revoked, and
is intended only to suppress redundant notifications while another Foreman
surface is already displaying the matching session.

`diagnostics.list` returns at most 100 newest-first in-memory operational events.
Every event uses a fixed safe message and one allowed category for service,
runtime, authenticated-client, pairing, token, request-category, protocol, or
listener lifecycle. It may include only an ISO timestamp, severity, category,
fixed message, fixed request category, and a generated client ID. It never
contains prompts, assistant text, commands, file content, approvals, tokens,
hashes, pairing codes, source addresses, unrestricted paths, traces, logs, or
raw JSON-RPC. The ring is discarded whenever Foreman stops.

`service.restart` is authenticated and available only when
`FOREMAN_REMOTE_RESTART=1`. Its result is `{scheduled:true,timeoutSeconds:45}`;
that envelope is flushed before Foreman invokes exactly
`systemctl --user restart --no-block foreman.service`. The scheduled result is
not a success claim. Clients report completion only after the connection drops,
Foreman returns, and authentication succeeds again. The operation never targets
Desktop Codex or any other service. Foreman rejects restart while a session is
active or waiting, or while an approval or input request is pending, so volatile
request state is not deliberately discarded.

Session summaries include authoritative active-turn start, terminal time,
duration, safe failure, and wait metadata when Codex supplies it.
Conversation reads use the installed `thread/turns/list` contract with bounded
pagination and `itemsView:"full"`, preserving historical command and tool cards
after refresh; older Codex versions fall back to `thread/read(includeTurns:true)`.
`session.event` carries normalized status, lifecycle, assistant delta, public
activity, command/tool item events, and bounded `thread/tokenUsage/updated`
snapshots. Token usage exposes only numeric `total`, `last`, and
`modelContextWindow` fields. Clients calculate current context occupancy from
`last.totalTokens`, never the cumulative `total.totalTokens`; usage events do
not change session recency. Foreman retains at most 500 last-known numeric
session usage snapshots in its mode-0600 state file so context meters survive a
service restart; archive/delete removes the associated snapshot. No prompt,
message, or transcript content is stored with it.
Reconnect is intentionally a fresh list/read/subscribe sequence; there are no
cursors, replay logs, or persistent dashboard history.

Authenticated clients use `usage.status` for the current bounded account
rate-limit snapshots of enabled providers, and receive
`usage.event` updates. Provider usage is separate from per-session context: it
exposes only quota percentages, window durations, reset timestamps, and bounded
limit labels. Account identity, token activity history, credits, and raw
provider payloads are not projected. Codex sparse rolling updates merge into
the last complete snapshot without clearing an unmentioned quota window.
Claude account limits come from an explicitly experimental Agent SDK method
available only during a Foreman-managed Claude query, so the projection is
labeled experimental and last-observed. The last bounded Claude percentages
and reset times survive a Foreman restart; an unavailable reason is returned
until the first usable snapshot exists.

Conversation items may include bounded `compaction` entries. Codex identifies
that compaction occurred; Claude may additionally provide automatic/manual
trigger, before/after token counts, and duration. Clients can count these items
without reading or projecting the generated summary.

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

Structured input is separate from approvals. Authenticated clients use
`input.list`, `input.respond`, and the `input.requested`, `input.updated`, and
`input.resolved` events. Input IDs are opaque `inp_…` Foreman IDs. Projections
contain only bounded labels/descriptions, verified normalized fields, options,
validation bounds, correlation, source/server display data, support status, and
valid decline/cancel actions. Accept uses
`{action:"accept",values:{...}}`; MCP-only decline/cancel omits values.

The service revalidates required fields, option membership, unique selections,
selection counts, primitive types, and string lengths before serializing the
installed Codex response. Stale, duplicate, malformed, or unsupported accepts
fail. Pending input is connection-bound memory, never persisted or replayed.
An installed-contract MCP form with an empty `properties` object is treated as
a zero-field confirmation: Allow sends `action: "accept"` with empty content,
while Decline and Cancel retain their distinct contract actions.

`session.search` is authenticated and accepts `query`, optional `archived`, one canonical
`repository`/workspace path or `null`, a `statuses` array, `dateFrom`, `dateTo`,
and a requested `limit`. Search is case-insensitive plain substring matching.
It matches compact titles, canonical workspace paths, and normalized visible
user, assistant, command, or tool text. Reasoning, raw events, raw command
output, images, tokens, and internal errors are not searched. Results contain
summary projections plus at most three 200-character snippets; they never
contain full histories or image data. The server caps results at 100 and lazy
transcript reads at 100 candidates, cancels an older in-flight search from the
same client, and maintains no persistent index or transcript mirror.
When `archived: true`, its candidates come only from the separately bounded
archived Codex list and transcript reads use the same non-resuming history path,
normalization, candidate/read/result/snippet limits, and privacy exclusions.

`session.archive` moves an inactive Codex thread out of the active list.
`session.restore` is an explicit authenticated operation that sends exactly one
Codex `thread/unarchive`, retains provider/session identity, and returns the
restored normal summary. `thread/archived` and `thread/unarchived` notifications
produce `session.event` lifecycle actions `archived` and `restored`; clients
refetch the applicable provider scope, so external changes, reconnects, and
service restarts converge on Codex's authoritative lists without replay.
`session.delete` requires
`confirm: true` and permanently deletes the inactive thread plus its spawned
descendants. Foreman rejects both operations while a session is working or
waiting for input. Per-session locks serialize this check and mutation with
prompt, steer, interrupt, archive, restore, and delete requests from other
connected Foreman clients. Archive, client-local Hide, host-state Forget, and
permanent Delete remain distinct operations.

`model.list` returns only picker fields from Codex's installed catalog.
`access.list` returns the access levels allowed by Codex's installed permission
profiles. `session.settings` accepts `sessionId` plus at least one of
`accessLevel`, `model`, and `reasoningEffort`, validates the selection against
the installed catalogs, and updates Codex's existing thread defaults for
subsequent turns. Foreman accepts the mutation only while the session is idle,
serializes the check with prompt/steer/interrupt operations, durably records the
acknowledged session values, and returns the updated session projection with a
monotonic `settingsRevision`. Working, stopping, approval-waiting, and
structured-input-waiting turns keep their route immutable until they finish.
`provider.session.settings` provides the equivalent Foreman-owned session
defaults for the Claude model and permission mode without claiming an
unsupported Claude SDK thread-settings mutation. Route events and refreshes
carry the revision so a stale read cannot replace a newer acknowledged setting.
For pre-migration Codex sessions whose app-server projection omits access,
Foreman may recover the last verified permission profile from the bounded tail
of that session's persisted Codex turn context, then stores the recovered value
in the normal server-authoritative session record.
A `turn.prompt` may include `accessLevel`, `model`, and `reasoningEffort` for
older clients, but known durable session values remain authoritative and those
fields only bootstrap values Foreman does not yet know. `turn.steer` keeps the
active turn's route and rejects replacement route fields. Both accept up to
four images:

```json
{"text":"Inspect this","images":[{"mimeType":"image/jpeg","data":"<base64>"}]}
```

JPEG, PNG, and WebP payloads are accepted, with an 8 MiB combined encoded
limit. Foreman converts them to Codex inline data-URL image items and does not
persist or log image bytes.

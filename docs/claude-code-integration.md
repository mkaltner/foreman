# Claude Code Linux adapter

Foreman uses the official TypeScript Claude Agent SDK through one optional,
long-lived Node companion process. The production pin is
`@anthropic-ai/claude-agent-sdk` `0.3.220`; the verified native Linux Claude Code
version and the SDK-declared runtime version are both `2.1.220`. No terminal,
PTY, transcript parser, Remote Control endpoint, or process scraper is used.

The production adapter supports installation/status detection, official session
discovery and history, start, exact-ID resume, partial assistant text, bounded tool activity,
permission callbacks and denials, model selection, the SDK permission modes, and
`Query.interrupt()` for an active Foreman-owned query. Supported SDK modes are
`default`, `dontAsk`, `acceptEdits`, `plan`, `auto`, and
`bypassPermissions`. `default` may pause for a callback; `dontAsk` denies an
action that is not already allowed; `bypassPermissions` is high risk and is
enabled only when explicitly selected. Modes are passed through without
upgrades or Foreman-specific semantics.

Claude owns transcripts and session persistence. Foreman stores only
`{sessionId, cwd}` in `claude-code-sessions.json`. A new adapter process can
resume the same session ID in its original directory. Sessions started or
resumed through Foreman are `managed`; discovered CLI sessions are `resumable`
until Foreman starts a new query for their exact ID. Recency is never projected
as live work. Pending approvals exist only in bridge memory and are denied and
cleared on completion, interruption, crash, or shutdown.

The production bridge uses bounded JSON messages over stdio with request IDs.
It runs once per Foreman service, restarts with bounded backoff, never replays a
query after failure, and shuts down its active SDK queries before exit. Safe
events include assistant deltas/completion, tool start/bounded result status,
permission request/denial, and query start/completion/failure/interruption. Raw
SDK objects, hidden reasoning, unrestricted tool input/output, credentials, and
complete Bash output are not projected or persisted. Official history access is
normalized only when a client opens one session; list discovery never fetches
every transcript.

Claude support is optional on the Linux host and exposed through the Foreman web
client. It requires an authenticated native `claude` executable, Node.js 20 or
newer, and the pinned SDK dependency. Tagged Foreman Linux archives contain the
production dependency and notices. The installer never runs npm; a source
checkout intended to expose Claude must be prepared with
`npm ci --omit=dev --ignore-scripts` in `linux/claude_bridge` before running
`./install.sh`. A missing or failed Claude adapter leaves Codex and Foreman
startup intact. Local status is available with `foreman claude-status`.

The authenticated protocol-v1 provider catalog reports availability, CLI/SDK
versions, capabilities, and explicit limitations. Web sessions use the compound
`hostId + provider + sessionId` identity and durable provider routes. The web
client can list/read/start/resume/delete sessions, stream text and safe
Read/Bash/edit/search/other tool cards, interrupt a still-active Foreman-owned
query, and choose `sonnet`, `haiku`, or one exact SDK permission mode. Deletion
uses the official SDK, validates the exact workspace, requires explicit
confirmation, and is unavailable while a Foreman-owned query is active. Claude
Code has no SDK archive/unarchive operation, so the web UI does not show a fake
Claude archive action. Claude model aliases are an explicit adapter-supported
list, not dynamic enumeration.

The user-visible states are `working`, `completed`, `failed`, `interrupted`, and
`resumable`; unavailable adapter entries use `unavailable`. `managed` means
Foreman owns or has resumed the current query. An externally created session is
`external` and `resumable` until a web user explicitly resumes it. Recency is
never evidence of live work, so Foreman does not emit `external-active` without
official proof. Interrupt and other live controls are rejected for external or
terminal sessions. An interrupted session keeps its exact ID and remains
resumable.

Claude permission callbacks remain inside the adapter. Web does not silently
approve or reuse Codex approval actions. A blocked query is shown as
“Permission required in Claude session. Foreman web approval support is not yet
available.” A `dontAsk` denial appears as a bounded denied tool card. Claude web
approvals, transcript search, images, background/browser notifications, Android
support, and cross-provider search are deferred.

From a source checkout, maintainers can repeat the authenticated disposable
proof explicitly:

```sh
python3 scripts/claude_adapter_proof.py --acknowledge-live-costs
python3 scripts/claude_web_proof.py --acknowledge-live-costs
```

The first command validates the production adapter boundary. The second starts
an ephemeral authenticated Foreman WebSocket service and validates provider
catalog, host pairing, list/read/start/resume, streaming, safe Read/Bash cards,
interrupt, Sonnet/Haiku, `dontAsk`, and external-session limitations through the
same transport used by the web client. Both commands create disposable Git
repositories and invoke authenticated Claude requests that may incur usage.

The authenticated adapter and WebSocket production proofs on 2026-07-31 passed
provider discovery, host pairing, list/read, start, resume, partial
streaming, safe Read and Bash visibility, `dontAsk` denial with filesystem
non-creation, interrupt, restart/resume, and discovery/resume of a CLI-created
session. `sonnet` resolved to `claude-sonnet-5`; `haiku` resolved to
`claude-haiku-4-5-20251001`. The authoritative
limitation is unchanged: Foreman cannot subscribe to an already-running external
Claude CLI process, stream it, answer its approval, interrupt it, or attach to
Claude Remote Control. It can only discover the saved session and later resume
it under a new Foreman-managed query.

Decision: `CLAUDE_ADAPTER_FEASIBLE_WITH_LIMITATIONS`

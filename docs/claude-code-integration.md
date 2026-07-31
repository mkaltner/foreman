# Claude Code Linux adapter

Foreman uses the official TypeScript Claude Agent SDK through one optional,
long-lived Node companion process. The production pin is
`@anthropic-ai/claude-agent-sdk` `0.3.220`; the verified native Linux Claude Code
version and the SDK-declared runtime version are both `2.1.220`. No terminal,
PTY, transcript parser, Remote Control endpoint, or process scraper is used.

The production adapter supports installation/status detection, official session
discovery, start, exact-ID resume, partial assistant text, bounded tool activity,
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
SDK objects, hidden reasoning, prompts, transcripts, and unrestricted tool
output are not projected or persisted.

Claude support is Linux-only and optional. It requires the native `claude`
executable, Node.js 20 or newer, and the pinned SDK dependency. A tagged Foreman
Linux archive contains the installed dependency; a source checkout can install
it reproducibly during `./install.sh` when Claude, Node, npm, and network access
are available. Failure leaves Codex and Foreman startup intact. Local status is
available with `foreman claude-status`; no Android, web, or protocol-v1 Claude
operation is exposed.

Maintainers can repeat the authenticated disposable proof explicitly:

```sh
python3 scripts/claude_adapter_proof.py --acknowledge-live-costs
```

The authenticated production proof on 2026-07-31 passed start, resume, partial
streaming, safe Read and Bash visibility, `dontAsk` denial with filesystem
non-creation, interrupt, restart/resume, and discovery/resume of a CLI-created
session. `sonnet` resolved to `claude-sonnet-5`; `haiku` resolved to
`claude-haiku-4-5-20251001`. The authoritative
limitation is unchanged: Foreman cannot subscribe to an already-running external
Claude CLI process, stream it, answer its approval, interrupt it, or attach to
Claude Remote Control. It can only discover the saved session and later resume
it under a new Foreman-managed query.

Decision: `CLAUDE_ADAPTER_FEASIBLE_WITH_LIMITATIONS`

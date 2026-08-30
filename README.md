# Foreman

<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/foreman_logo.png" alt="Foreman logo" width="128">
  <br>
  <strong>A fast, self-hosted control plane for Codex and Claude Code.</strong>
</p>

Foreman provides self-hosted Android and web control for Codex and
Foreman-managed Claude Code sessions running on Linux hosts. It connects
directly to the local Codex app-server and uses the official Claude Agent SDK
through a bounded host-side bridge. Foreman is designed for fast, LAN-first
operation.

See the [product roadmap](ROADMAP.md) for current priorities and longer-term
direction.

> Claude Code support requires an authenticated local Claude Code CLI, Node.js
> 20 or newer, and the packaged pinned Agent SDK. External Claude sessions are
> discoverable and resumable, but Foreman cannot live-attach to their running
> CLI process or use Claude Remote Control.

## Why Foreman?

Foreman is for people who want a dedicated coding-agent monitoring interface,
fast local interaction, and direct control of an always-on Linux host. It
provides self-hosted Android and browser access, visibility across active and
recent Codex and Claude Code sessions, with provider-appropriate controls for
prompts, interrupts, models, reasoning, access, and permission modes.

## Foreman and Codex Remote

OpenAI's [Codex Remote](https://learn.chatgpt.com/docs/remote-connections) is the
first-party option for controlling Codex from the ChatGPT mobile app. It is a
good fit for people who want remote control integrated with the broader ChatGPT
experience.

Foreman is aimed at users who prefer a dedicated, self-hosted, LAN-first
interface and want direct access to their own Linux Foreman host. It is designed
for low-latency local use and does not attempt to replace every first-party
Remote capability.

## Install

```sh
git clone https://github.com/mkaltner/foreman.git
cd foreman
./install.sh
```

Then verify the user service, create a short-lived pairing code, and print the
browser URL:

```sh
foreman status
foreman pair
foreman web
```

The Android transport and web client are served by the same
`foreman.service`; there is no separate web process to manage. Use:

```sh
foreman start       # start Android :8765 and web :8766
foreman stop        # stop both listeners
foreman restart     # restart after configuration changes
foreman status      # show service status and recent errors
foreman logs        # follow the user-service journal
```

To update:

```sh
cd foreman
git pull
./install.sh
```

The installer stages and validates a complete replacement, preserves the
existing configuration and paired-client state, removes obsolete installed
files, and restores the prior payload if service activation fails. For tagged
upgrade and Linux rollback commands—and Android's no-in-place-downgrade
constraint—see the [installation guide](docs/install.md).

Foreman requires Linux with user systemd, Python 3.10+, Git, and an authenticated
`codex` CLI. Its pinned Python dependency and prebuilt web assets are included,
so installation does not require pip, a Python virtual environment, Node, Java,
root access, or network access. Tagged Linux archives and signed Android APKs
are available from [GitHub releases](https://github.com/mkaltner/foreman/releases)
as an alternative.

Claude Code is optional. Enabling it requires Node.js 20+, local Claude CLI
authentication, and the packaged pinned Agent SDK. A missing or failed Claude
bridge leaves Codex and Foreman startup unaffected. Pairing remains host-level:
one paired client can use every provider available on that host.

## Features

- Pair clients with a short-lived, six-digit, one-time code.
- Reconnect with persistent token authentication and fresh authoritative state.
- Inspect paired browser and Android clients and revoke individual tokens.
- Supervise active, waiting, failed, stale, and recently terminal work from a
  live dashboard with an attention queue, oldest-turn callout, and activity feed.
- See a client-side unified overview of every saved host, with aggregate counts,
  runtime and version health, stale offline snapshots, and compound
  host/session attention navigation. Web uses at most four live sockets;
  Android uses one foreground-only sequential probe alongside the active host.
- Discover provider availability and browse active, recent, or resumable Codex
  and Claude Code sessions with visible provider identity.
- Enable or disable installed providers while enforcing that every host keeps at
  least one provider available.
- Inspect provider account usage and each session's context-window consumption,
  remaining tokens, model, access, turn count, and compaction history.
- Search Codex session titles and bounded, normalized visible transcript text;
  Claude transcript search is not currently available.
- Filter by repository/workspace, status, local date range, pins, and hidden sessions.
- Select an explicitly capability-backed Archived filter for Codex, inspect its
  safely normalized transcript read-only, and restore it with a deliberate action.
- Organize sessions into collapsible repository and workspace groups whose
  expanded state is retained per host across browser and Android relaunches.
- Pin important sessions or non-destructively hide noisy sessions per client.
- List, read, start, resume, and delete sessions. Supported Codex versions also
  expose archive discovery and restore; Claude Code does not expose an archive lifecycle.
- Prompt and interrupt managed work; Codex additionally supports steering.
- Stream live status, assistant deltas, tool activity, and progress updates.
- Review command, file-change, and permission approvals inline, including
  schema-advertised session and policy-amendment choices.
- Resolve an approval from Android, web, or Codex Desktop and clear every
  Foreman client live when Codex confirms the result.
- Answer bounded Codex choice/text questions and supported MCP choice, text,
  boolean, or confirmation requests inline on web and Android.
- Show safe plans, commands, tool names, and other meaningful live activity
  without exposing hidden reasoning or unrestricted tool output.
- Choose **Focused** activity detail to group routine successful commands and
  tools from finished turns while keeping current-turn work visible, or
  **Full** to show every activity item. This is presentation-only.
- Select installed Codex models, reasoning levels, and access profiles, or
  Claude Sonnet/Haiku and exact Claude permission modes.
- Keep existing-session model, reasoning, access, and permission configuration
  server-authoritative and durable; configuration remains a new-session choice
  while active or waiting work is immutable.
- Attach or paste up to four JPEG, PNG, or WebP images into Codex prompts or
  steers.
- Notify Android devices for monitored Codex or managed Claude lifecycle, and
  supported background browser tabs for Codex turns.
- Suppress redundant alerts while another paired client visibly focuses the
  exact session, and consolidate Android monitoring, attention, and foreground
  outcomes into one OS notification.
- Choose System, Light, or Dark color mode independently from curated Foreman, Harbor, Grove, Ember, Dune, Slate, and High Contrast themes.
- Use dedicated Android and responsive web clients.
- Install as a rootless user-level systemd service without pip or a Python venv.

## Android

Download and sideload the signed APK from the matching tagged
[GitHub release](https://github.com/mkaltner/foreman/releases). Run `foreman pair`
on the Linux host, then enter the host name or IP address, pairing code, and a
device name in the app. Port `8765` is used when no port is given. Run
`foreman pair` again for each additional device or saved host. The compact host
selector can add, rename, forget, and switch among independently paired hosts.
Home opens the unified saved-host overview. **View dashboard** opens a live,
host-scoped operations view with connection/runtime health, active and waiting
counts, the oldest turn, concrete attention items, active work, and recent
terminal turns. Dashboard cards open the existing conversation and focus a
pending approval or input when present. Android Back from the dashboard returns
to the unified saved-host overview; **Home** and **Sessions** provide the same
explicit in-app navigation.

Android loads the host provider catalog after ordinary authentication. The new
session dialog selects an enabled Codex or Claude Code provider, then shows only
the applicable workspace, model, reasoning/access, or Claude permission
controls. Claude
supports Sonnet, Haiku, and `default`, `dontAsk`, `acceptEdits`, `plan`, `auto`,
or `bypassPermissions`; bypass mode is prominently marked high risk and never
selected silently. External Claude sessions appear as **Resumable · Not
live-attached** and can be resumed under the same session ID with **Resume in
Foreman**.

For development builds, open [`android`](android) in a current Android Studio.
Android protects the persistent device token with Android Keystore.
The Sessions screen provides expandable search and a compact filter dialog for
normal or archived provider scope, repository/workspace, provider, status,
Today/7/30-day or custom date ranges, pinned-only,
and Hidden management. Repository and non-Git workspace groups are collapsible,
bubble active work upward, and preserve their state across app relaunches.
Search choices, group state, pins, and hidden IDs use per-host local preferences;
transcripts and image data are never stored there.
Archived scope appears only when an enabled provider explicitly advertises it.
Archived Codex cards are visually distinct, open read-only without resuming the
thread, and replace active controls with Restore. Claude sessions never present
archive or restore controls.
Activity detail defaults to **Focused** on Android and web. It groups consecutive
routine, successfully completed command/tool cards from finished turns behind
an expandable summary. Current-turn, running, failed, denied, or unknown work,
approvals, structured input, and search targets remain visible; **Full** restores
the ungrouped transcript.
Approval cards stay inside the existing conversation. Permission cards grant
only selected requested access, and reconnect reloads only currently pending
requests. Android reuses one foreground notification for monitoring, attention,
and monitored outcomes instead of stacking a second Foreman entry. Attention
content remains generic and opens the exact host and session/card without
exposing commands or paths on the lock screen. Background monitoring is
intentionally limited to the active host. Provider-aware Claude completion,
failure, interruption, and attention alerts use the same privacy-safe Android
notification preferences and open the exact host/provider/session. Merely
resumable external sessions never notify.
When a paired web or Android client is visibly focused on that exact session,
other connected clients suppress its redundant event alerts. Android keeps the
single quiet foreground-service entry because the operating system requires it
while global monitoring is enabled.

## Web

The web client is bundled with the Linux installation; it does not require a
separate web-server package, Node, or another systemd unit. Install Foreman,
confirm the unified service is running, print the URL, and create a pairing
code:

```sh
git clone https://github.com/mkaltner/foreman.git
cd foreman
./install.sh
foreman status
foreman web
foreman pair
```

Open the printed URL—normally `http://HOST:8766`—from a current Chrome, Firefox,
or Edge release on a trusted network. Enter the host and six-digit pairing code.
The initial web port defaults to the port that served the page and remains
editable when adding another host. Run `foreman pair` on each host, then use the
saved-host selector to add, rename, forget, or switch hosts. Cross-origin hosts
must allow the page origin through `FOREMAN_WEB_ORIGINS`.

`foreman web` only prints the configured URL; `foreman start`, `stop`, and
`restart` control both the web listener on `8766` and Android protocol listener
on `8765`. If a firewall is enabled, expose only the listener needed by each
trusted client or secure overlay.

The responsive dashboard provides:

- host, runtime, connection, and event freshness;
- connected-client counts and paired-client management;
- bounded sanitized diagnostics with manual refresh and copy;
- compact active-session cards with scan-friendly titles and the oldest active turn;
- a calm attention queue for waiting, failed, disconnected, or stale work;
- repository and non-Git workspace groups;
- a bounded, coalesced recent-activity feed.

The full web client also supports:

- the session list, conversation history, and live assistant/tool activity;
- workspace-scoped file links with Markdown preview and optional line-number highlighting;
- provider-aware Codex and Claude session start, resume, prompts, and interrupts;
- Codex model/reasoning/access and Claude model/permission selection;
- Codex file-picker and clipboard image attachments;
- inline Codex command, file-change, and permission approval cards;
- provider-appropriate archive and delete actions;
- opt-in Codex browser notifications for background tabs;
- bounded reconnect without request replay;
- confirmed, gated restart of Foreman with reconnect progress;
- durable host/session URLs with Back, Forward, and refresh restoration;
- bookmarkable search/filter URLs, keyboard search navigation, local pins, and
  a restorable Hidden view;
- responsive System/Light/Dark color modes with coherent curated themes.

Dashboard behavior and limits:

- Data remains live while the browser is connected.
- **No recent activity** is a conservative ten-minute browser heuristic. It
  never interrupts a turn or changes Codex state.
- Recent completion and feed data are not a permanent audit history.
- **Other workspaces** contains session roots that do not map to a discovered
  Git repository.
- Concrete approvals and structured-input requests appear once in **Needs
  attention** and open the associated inline card. Unsupported schemas are
  labeled honestly and never rendered as arbitrary JSON Schema.
- Search uses case-insensitive plain substrings. Criteria are ANDed, selected
  statuses are ORed, and hidden sessions stay excluded unless **Hidden
  sessions** is selected. Pins affect ordering, not matching.
- Transcript search reads at most 100 candidate histories per request, returns
  at most 100 sessions and three 200-character safe snippets per session, and
  creates no index or transcript cache. It is not fuzzy or semantic search.
- Pins and hidden-session choices are browser-local. They do not change Codex
  state or sync to Android, and they are isolated by local host ID.
- Host Status can show connected and offline paired clients. Revoking one removes
  only that authentication token, disconnecting its live connections without
  affecting sessions or repositories.

Browser notifications require HTTPS or localhost and work while Foreman remains
open in a background tab for the active host only. Notification data includes
the local host ID so a tap selects the right host before opening the session.
Foreman reports only the focused provider/session pair over its authenticated
connection. If that session is already visible on web or Android, both clients
suppress its completion and attention alerts; viewing another session does not
suppress them.
For another LAN device, put the web listener behind a
trusted same-origin HTTPS reverse proxy. For example, Caddy can terminate TLS
and proxy both HTTP and WebSocket traffic:

```caddyfile
foreman.example.com {
    reverse_proxy 127.0.0.1:8766
}
```

Open `https://foreman.example.com`, pair that browser, then enable alerts under
**Settings → Notifications**. The hostname must use a certificate trusted by
the client device; Caddy's automatic public certificates or a client-trusted
internal CA both satisfy the browser secure-context requirement. Browser
storage does not provide Android Keystore protection; use **Disconnect and
forget host** before leaving a shared browser.

The installed SPA is prebuilt, so normal users do not need Node. Maintainers can
rebuild the committed assets with the pinned Node version as documented in the
[installation guide](docs/install.md).

## Screenshots

### Android

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/pairing.png" alt="Pair Foreman with a Linux host" width="260"></td>
    <td align="center"><img src="docs/screenshots/session-list.png" alt="Browse active and recent coding-agent sessions" width="260"></td>
    <td align="center"><img src="docs/screenshots/live-session.png" alt="Monitor work in progress" width="260"></td>
  </tr>
  <tr>
    <td align="center">Pair with a Linux host</td>
    <td align="center">Browse live sessions</td>
    <td align="center">Monitor work in progress</td>
  </tr>
</table>

## Architecture

```text
Android ── authenticated JSONL/TCP :8765 ─┐
                                          ├─ Foreman service ─┬─ Codex app-server
Browser ── HTTP + authenticated WS :8766 ─┘                   └─ Claude Agent SDK bridge
```

- Android and browser clients use the same protocol-v1 messages.
- TCP uses newline-delimited JSON; WebSocket uses one JSON text message per frame.
- HTTP serves static assets and operational health only; there is no application REST API.
- Foreman connects to Codex over a Unix-socket WebSocket and to its optional
  Claude companion over bounded request-ID JSONL on stdio.

## Security

> [!CAUTION]
> Foreman transport is authenticated but not necessarily encrypted. Use a
> trusted LAN, Tailscale, WireGuard, or a trusted reverse proxy that provides
> encryption. Do not expose Foreman ports directly to the public internet.

Pairing codes expire after ten minutes and are valid for one use. Failed guesses
are throttled per source address. The service stores only device-token hashes;
Android protects its token with Android Keystore, while browser tokens remain in
`localStorage` without equivalent Keystore protection. Tokens are not placed in
URLs, cookies, logs, or analytics.

Foreman does not terminate TLS. When a trusted reverse proxy uses a different
origin, add its exact HTTPS origin to `FOREMAN_WEB_ORIGINS`; permissive wildcard
CORS is not enabled.

A paired client can control every provider available on its host, so treat its
token and network access as sensitive. Foreman does not expose a
standalone shell or Git-write endpoint. Its authenticated approval and bounded
input endpoints only return validated responses to pending Codex requests;
they never execute command text themselves. Codex can still run tools and
modify files according to its selected access profile.
Claude can run tools according to its selected native permission mode. Foreman
never maps Claude permission callbacks to Codex approvals or silently approves
them; unsupported Claude permission requests must be resolved in Claude Code.
Any authenticated Foreman client may revoke another paired token. Client lists
contain only the saved label, client type, pairing time, and live connection
state—never token values, token hashes, pairing codes, or source addresses.

## Codex Desktop runtime

Codex Desktop's default control socket is attach-only. Foreman never removes or
replaces that socket and never launches a process on it. When shared attachment
is unavailable, Foreman uses its own fallback socket and reports
`SHARED_DESKTOP_LIVE_STATUS_UNAVAILABLE`.

Fallback mode does not provide live co-presence with Codex Desktop. Stopping
Foreman closes its attachment or stops only the fallback process it owns; it
does not stop Desktop's Codex runtime.

## Known limitations

- Arbitrary JSON Schema, OpenAI extended forms, URL elicitation, and nested or
  dynamic forms are not supported.
- Direct TCP and HTTP transport is not encrypted.
- Desktop live co-presence requires successful shared-socket attachment.
- Android distribution is currently sideloaded.
- The multi-host overview is bounded, client-local, and can be stale; search,
  transcripts, active monitoring, and notifications remain scoped to one host.
- Recent dashboard activity is not a persistent audit history.
- Dashboard stale activity is an observation, not a failure or automatic action.
- Claude support requires Node.js 20+, an authenticated local Claude CLI, and
  the packaged pinned Agent SDK.
- External Claude sessions are resumable but not live-attachable. Foreman cannot
  stream or interrupt their current external process or answer its approvals.
- Claude Remote Control, Claude images, Claude transcript search, and Claude
  approval responses from Foreman clients are not supported.
- Chrome and Firefox are the supported 1.0 browsers; Edge is best-effort.

## Stable status

Foreman 1.0 is the first stable release. Protocol v1 and the documented
state-preserving upgrade behavior are the compatibility contract for the 1.x
series; incompatible wire or configuration changes will not be made silently.
Current main adds compatible multi-provider functionality intended for v1.1.0;
it does not change protocol version 1. Android remains distributed by
sideloading. The
[v1.0 acceptance record](docs/acceptance-v1.0.0.md) tracks release evidence.
Issues and feedback are welcome in the
[GitHub issue tracker](https://github.com/mkaltner/foreman/issues).

## Documentation

- [Product roadmap](ROADMAP.md)
- [Installation guide](docs/install.md)
- [Architecture](docs/architecture.md)
- [Protocol](docs/protocol.md)
- [Claude Code integration](docs/claude-code-integration.md)
- [Codex integration](docs/codex-integration.md)
- [Compatibility policy](docs/compatibility.md)
- [Release guide](docs/releasing.md)
- [v1.0 acceptance record](docs/acceptance-v1.0.0.md)
- [License](LICENSE)
- [Third-party notice inventory](THIRD_PARTY_NOTICES.md)

# Foreman user guide

Foreman presents the same host and session behavior through responsive web and
native Android clients. Layout differs by platform, but provider rules,
session state, approvals, input, and update semantics stay aligned.

## Hosts and pairing

Run `foreman pair` on a Linux host to create a six-digit, single-use code that
expires after ten minutes. Enter the host name or IP address, code, and a device
label in Android or the web pairing screen. Run the command again for every
additional client or host.

Successful pairing creates a persistent device token. Hosts can be added,
renamed, switched, and forgotten independently. Forgetting a host removes its
local token and host-scoped preferences; it does not delete server sessions.
Settings shows paired clients and can revoke an individual token without
affecting repositories or coding-agent state.

## Dashboard and sessions

The unified overview summarizes every saved host using bounded client-local
snapshots. Opening a host shows live connection and runtime health, active and
waiting counts, the oldest turn, attention items, current work, and recent
terminal activity.

The Sessions view can:

- browse active, recent, resumable, failed, and provider-supported archived
  sessions;
- group sessions into collapsible Git repositories and non-Git workspaces;
- bubble active work to the top of each group;
- search titles and bounded safe Codex transcript text;
- filter by repository, provider, status, date, pins, hidden state, or archive;
- retain group state, pins, hidden IDs, and search choices per host.

Archived Codex sessions open read-only and can be deliberately restored.
Providers that do not advertise an archive capability never show archive or
restore controls. Local pins and hidden sessions organize the clients without
changing provider state.

## Starting and controlling work

The new-session flow shows only enabled, available providers. When more than
one provider is usable, choose Codex or Claude Code and then select the
provider-appropriate configuration:

- Codex model, reasoning level, and access profile;
- Claude model and native permission mode.

At least one usable provider must remain enabled. Existing-session
configuration is server-authoritative and durable; model, reasoning, access,
and permission choices are made for new work rather than silently changed
during an active turn.

Both clients can start or resume managed sessions, send prompts, and interrupt
work. Codex also supports steering an active turn. Codex prompts and steers can
include up to four JPEG, PNG, or WebP images. Claude image input is not yet
supported.

External Claude sessions are discoverable and resumable, but Foreman cannot
live-attach to the already-running external CLI process. Resuming brings the
session under Foreman management with the same session identity.

## Live activity, approvals, and input

Conversations stream assistant text, safe plans, commands, file changes, tool
activity, and progress without exposing hidden reasoning or unrestricted tool
output. **Focused** activity detail groups routine successful commands and
tools from completed turns; **Full** shows every activity item. Running,
failed, denied, interrupted, unknown, approval, and input items remain visible
in both modes.

Codex approvals appear inline and expose only choices advertised by the active
runtime. Foreman supports command, file-change, permission, and policy choices,
plus bounded choice, text, boolean, confirmation, and supported MCP input. A
resolution from Foreman or Codex Desktop clears the pending request across
connected clients when Codex confirms it.

Claude uses its native permission modes. Foreman does not translate Claude
permission callbacks into Codex approvals or silently approve unsupported
requests.

## Usage, context, and appearance

Provider account-usage panels show the limits exposed by the installed CLI.
Session context panels show model, access, turn and compaction counts, token
consumption, and remaining context when the provider supplies those values.

Web and Android support System, Light, and Dark color modes with curated
Foreman, Harbor, Grove, Ember, Dune, Slate, and High Contrast themes. Appearance
and organizer preferences are local to each client and scoped where necessary
by host.

## Notifications and presence

Android can monitor Codex and managed Claude lifecycle events in the
background. Supported browsers can notify for Codex events while Foreman stays
open in a background tab. Notification preferences cover approvals/input,
failures, completions, interruptions, long-running work, quiet hours, and
repository overrides.

Foreman reports the visibly focused provider/session pair over its authenticated
connection. Other paired clients suppress only redundant alerts for that exact
session; viewing a different session does not suppress delivery. Android uses
one foreground-service notification for monitoring and folds attention and
outcomes into that entry rather than stacking duplicate Foreman notifications.
Background Android monitoring is intentionally limited to the active host.

## Android

Download and sideload the signed APK from the matching
[GitHub release](https://github.com/mkaltner/foreman/releases). Android uses port
`8765` when no port is provided and protects the persistent token with Android
Keystore.

Home opens the unified saved-host overview. **View dashboard** opens a live
host-scoped operations view, while **Sessions** opens discovery and search.
Dashboard and session cards navigate directly to pending approvals or input
when available. Android Back, Home, and Sessions provide explicit routes back
from a conversation.

**Settings → About** compares the installed APK with complete official stable
releases. Foreman downloads the exact APK, verifies the signed checksum and
package signer, and then hands it to Android's system installer. Android always
requires explicit installation confirmation. See the
[Android APK update model](android-apk-updates.md).

For development builds, open the [`android`](../android) directory in a current
Android Studio. Routine repository verification does not require assembling an
APK.

## Web

The web client is bundled with the Linux installation. `foreman web` prints its
configured URL, normally `http://HOST:8766`; it does not start another process.
The same `foreman start`, `stop`, and `restart` commands control both web and
Android listeners.

The web client supports durable host/session URLs, browser Back and Forward,
refresh restoration, keyboard search navigation, Markdown file previews,
optional line highlighting, file selection, clipboard images, and responsive
layouts. Browser tokens live in local storage without Android Keystore-equivalent
protection, so forget the host before leaving a shared browser.

Browser notifications require HTTPS or localhost. For another LAN device,
place the web listener behind a trusted same-origin HTTPS reverse proxy. A
minimal Caddy configuration is:

```caddyfile
foreman.example.com {
    reverse_proxy 127.0.0.1:8766
}
```

Add the proxy's exact HTTPS origin to `FOREMAN_WEB_ORIGINS` when it differs from
the listener origin. The client certificate must be trusted by the browser.

## Service and updates

The common service commands are:

```sh
foreman start
foreman stop
foreman restart
foreman status
foreman logs
```

On a headless host, enable systemd user lingering as described in the
[installation guide](install.md) so Foreman survives the final logout.

Check and install stable server updates with:

```sh
foreman update --check
foreman update
foreman update --status
```

Web and Android expose the same server-update review in **Settings → About**.
Activation requires full access, refuses to interrupt active or waiting work
and pending approval/input, verifies official signed artifacts, restarts only
`foreman.service`, health-checks the replacement, and restores the previous
payload when activation fails. See [recoverable server updates](server-updates.md).

## Known limitations

- Direct TCP and HTTP transports are authenticated but not encrypted.
- Android distribution currently requires sideloading.
- Browser notifications require Foreman to remain open in a tab.
- Desktop live co-presence depends on shared Codex socket attachment; fallback
  mode remains fully usable but is not live-attached to Codex Desktop.
- External Claude sessions are resumable but not live-attachable or
  interruptible until Foreman resumes them.
- Claude Remote Control, Claude images, Claude transcript search, and Claude
  approval responses from Foreman clients are not supported.
- Arbitrary JSON Schema, nested dynamic forms, URL elicitation, and OpenAI
  extended forms are not supported.
- Multi-host snapshots and recent dashboard activity are bounded client-local
  observations, not a persistent audit history.
- Search uses plain case-insensitive substrings rather than fuzzy or semantic
  indexing and intentionally bounds transcript reads and returned snippets.

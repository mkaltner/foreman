# Install and run

## Linux host

Install Codex, authenticate it, and confirm `codex --version` works. Foreman
needs Python 3.10+, Git, and a user systemd session. Its pinned `websockets`
dependency is included in the install payload, so installation doesn't require
pip, venv support, root, or network access.

```sh
git clone https://github.com/mkaltner/foreman.git
cd foreman
./install.sh
foreman status
foreman pair
foreman web
```

Update by pulling the checkout and rerunning the same transactional installer:

```sh
cd foreman
git pull
./install.sh
```

For a reproducible release upgrade, verify the tag and check it out explicitly
before reinstalling. Do not run an unreviewed branch checkout on a host that
controls sensitive Codex sessions:

```sh
git fetch origin tag v1.0.0
git checkout --detach v1.0.0
python3 scripts/verify_release.py --tag v1.0.0
./install.sh
```

The tagged Linux archive from a GitHub release contains the same install
payload and is an alternative to cloning the repository.

Optional Claude Code web and Android support requires an authenticated native Claude Code
CLI, Node.js 20 or newer, and the exact SDK lock under `linux/claude_bridge`.
Tagged Linux archives include the production SDK dependency and notices, so
installation remains offline and never runs npm. When installing from a source
checkout, prepare that optional payload first:

```sh
cd linux/claude_bridge
npm ci --omit=dev --ignore-scripts
cd ../..
./install.sh
```

Without that prepared payload Claude is reported unavailable and Codex continues
normally. Node is therefore an optional host dependency required only when
Claude support is enabled. Authenticate Claude locally with its native CLI;
Foreman pairing does not authenticate either provider. Check adapter detection
with `foreman claude-status`.

The rootless installer copies Foreman to `~/.local/share/foreman`, installs the
CLI in `~/.local/bin`, installs and starts `foreman.service`, and creates:

```text
~/.config/foreman/foreman.env
~/.local/state/foreman
```

Reinstalling validates a staged copy before replacing the running installation.
An older `~/.local/share/foreman/venv` is retained through service activation
and removed only after the system-Python service starts successfully.
The installer never replaces `~/.config/foreman/foreman.env` or
`~/.local/state/foreman`, so configuration and paired-client tokens survive a
successful reinstall and an activation rollback. The installed application
directory is replaced as a unit, so files left by an older alpha are removed.
If activation fails, the previous application directory, launcher, and systemd
unit are restored before the previous service is restarted.

To roll Linux back, verify and check out (or unpack) the last known-good release
and rerun that release's `install.sh`. Confirm `foreman status`, `foreman web`,
and existing client authentication afterward. Android cannot be downgraded in
place to an APK with a lower version code; uninstalling it removes app-local
encrypted host tokens unless they are restored from a device backup.

Configuration defaults:

```sh
FOREMAN_HOST=0.0.0.0
FOREMAN_PORT=8765
FOREMAN_WEB_HOST=0.0.0.0
FOREMAN_WEB_PORT=8766
FOREMAN_REMOTE_RESTART=0
FOREMAN_REPOSITORY_ROOT=/home/you/projects
FOREMAN_CODEX_EXECUTABLE=/absolute/path/to/codex
# Optional:
FOREMAN_CODEX_SOCKET=/absolute/path/to/app-server-control.sock
FOREMAN_CODEX_FALLBACK_SOCKET=/absolute/path/to/foreman-codex-app-server.sock
# Comma-separated origins only when a reverse proxy uses a different origin:
FOREMAN_WEB_ORIGINS=https://foreman.example.internal
```

`FOREMAN_CODEX_SOCKET` is attach-only. Foreman never starts or replaces a
listener there. The fallback defaults to
`~/.local/state/foreman/codex-app-server.sock` and cannot provide Desktop live
co-presence.

Leave `FOREMAN_REMOTE_RESTART=0` unless authenticated clients should be able to
restart Foreman. Setting it to `1` enables only `foreman.service` restart through
the user systemd manager; it does not grant shell access and never restarts or
stops Desktop Codex. Restarting clients show progress until they reconnect or a
45-second timeout expires.

After edits, use `foreman restart`. `foreman logs` follows the user journal.
`foreman web` prints the configured local web URL; replace `localhost` with the
host's trusted-LAN address when connecting remotely. The web client asks for
the same one-time `foreman pair` code and stores its persistent device token in
browser `localStorage`. The one-time code isn't retained. Browser storage is
less protected than Android Keystore, so use the **Disconnect and forget host**
action on shared browsers.

One paired host token authorizes every provider that host reports available.
There are no provider-specific pairing commands or tokens. Claude authentication
and Codex authentication remain local to the host.

Android and web clients may save multiple independent Foreman installations.
Each saved host keeps its own token and client-local preferences. Select one
active host at a time; switching closes the old connection before authenticating
the new one. Browser URLs contain only a stable local host ID, never a token.

The CLI controls one unified service:

| Command | Effect |
| --- | --- |
| `foreman start` | Start the Android TCP and browser HTTP/WebSocket listeners. |
| `foreman stop` | Stop both listeners. |
| `foreman restart` | Restart both listeners after an update or configuration change. |
| `foreman update --check` | Check the official stable channel without changing the installation. |
| `foreman update` | Review and start a signed, session-safe, recoverable server update. |
| `foreman update --status` | Show the latest durable update operation after reconnect or restart. |
| `foreman update --recover` | Retry restoration of the latest retained backup. |
| `foreman status` | Show the systemd user-service status. |
| `foreman logs` | Follow the service journal. |
| `foreman web` | Print the browser URL; it does not start a second web process. |
| `foreman pair` | Create a single-use pairing code valid for ten minutes. |

`foreman update --check` exits `0` when current, `10` when an update is
available, and `20` when active work blocks activation. `foreman update` shows
the installed/target versions, official source, release-notes link, blockers,
restart scope, and rollback expectation before prompting. Use `--yes` only for
local automation that already supplied equivalent confirmation. Other update
exit codes are `21` verification failure, `22` concurrent update, `23`
activation failure, `24` update failure with successful rollback, `25` recovery
required, and `69` service unavailable. Progress and results remain available
through `foreman update --status` after the expected disconnect.

Automatic updates require OpenSSL and the user systemd manager. They never run
the downloaded `install.sh`; the installed external helper owns bounded
activation, restart, health checking, and rollback. See
[`server-updates.md`](server-updates.md) for the trust model and manual recovery
procedure.

To install specifically for browser use, no additional build or package is
needed. Run `./install.sh`, verify `foreman status`, open the URL from
`foreman web`, and enter a fresh code from `foreman pair`. The installed,
prebuilt SPA defaults to the page's own port for its authenticated WebSocket
connection. The add-host form allows a different web port. When connecting from
one host's page to another host, add the page's exact origin to the target's
`FOREMAN_WEB_ORIGINS` setting.
After authentication it opens on the live dashboard. **Sessions** retains the
conversation list and full interaction view; **Settings** contains appearance,
notification, and connection preferences.

The web and Android new-session dialogs include a provider selector. Claude selection shows
workspace, adapter-supported Sonnet/Haiku, and the six exact Claude permission
modes; bypass permissions is explicitly high risk. External Claude sessions are
listed as resumable and not live-attached, with live controls disabled until a
new Foreman-managed query resumes the exact session. Claude has no client
approval responses, transcript search, or images in this version. Android shows
a bounded permission-required state that must be resolved in Claude Code.
Android lifecycle alerts cover only monitored Foreman-managed Claude queries;
merely resumable external sessions never notify.

The dashboard shows current session summaries, repository and non-Git workspace
groups, attention states, oldest active work, event freshness, connected-client
counts, a disclosure for paired-client token revocation, and a bounded recent
feed while the browser is connected. Revocation disconnects that client but
does not delete its sessions. “No recent
activity” is a ten-minute browser heuristic and never changes a turn. Command,
file-change, permission approval, and supported bounded input cards open inline
from **Needs attention**. Arbitrary MCP forms and unsupported schemas require
another compatible client unless a valid decline or cancel action is shown.
Recent activity is intentionally not a persistent audit history.
Host Status also includes a compact sanitized Diagnostics disclosure with manual
refresh, copy, and—when enabled—a confirmed Foreman restart. Android exposes the
same bounded view from Settings. Neither client streams logs.
Both Settings surfaces include About information with the client build and
commit embedded at build time. The connected server version is labeled
separately and can be unavailable while offline; client build information does
not depend on a live connection.

Browser turn notifications can be enabled under **Settings → Notifications**.
Browsers require HTTPS or localhost for system notifications; plain LAN HTTP
addresses cannot request notification permission. Notifications cover turns
that finish, fail, are interrupted, or need attention while Foreman remains
open in a background tab. A trusted same-origin HTTPS reverse proxy enables the
feature for remote browsers.
Notification taps include host identity, and background monitoring remains
limited to the currently active host.
Connected web and Android clients coordinate only the provider/session currently
visible and focused. Matching alerts are suppressed across both surfaces; an
open dashboard, background tab, or different session does not suppress them.

Allow TCP port `8765` and web port `8766` only on a trusted LAN or secure
overlay. Foreman authenticates both transports but doesn't terminate TLS. Do
not expose either port directly to the public Internet. Prefer Tailscale or
WireGuard; for browser TLS, place Foreman behind a trusted same-origin reverse
proxy and add its exact HTTPS origin to `FOREMAN_WEB_ORIGINS`. Tokens must never
be placed in proxy URLs or query strings.

## Rebuild the web client

Normal installation uses the committed `web/dist` assets and doesn't require
Node. Maintainers updating `web/src` must use the pinned Node version and
refresh those assets before committing:

```sh
cd web
npm ci
npm test
npm run typecheck
npm run build
git status --short dist
```

CI rebuilds the SPA and fails if the committed assets are stale.

## Test the optional Claude bridge

Maintainers use Node 20 or newer and the committed lockfile:

```sh
cd linux/claude_bridge
npm ci --ignore-scripts
npm run build
npm test
```

Release and CI packaging repeat a production-only install and exclude bridge
test fixtures from the archive. The production entry point remains
`bridge.mjs`; the installer never downloads dependencies at runtime.

After ordinary automated tests, maintainers with authenticated Claude access can
run the explicit disposable adapter and WebSocket proofs documented in
[`claude-code-integration.md`](claude-code-integration.md). These are opt-in
because they invoke live models and may incur usage.

## Refresh the vendored Python dependency

Maintainers can refresh the committed dependency payload from the exact pin in
`requirements.txt` with:

```sh
PYTHON=/path/to/python-with-pip scripts/vendor_python_dependencies.sh
```

This preparation command may access the package index. `./install.sh` never
invokes it and remains offline.

## Android phone

Download the signed release APK from the matching tagged GitHub release,
sideload it, and run `foreman pair` immediately before setup. For development,
open `android/` in current Android Studio (compile SDK 37) and build the debug
app. Enter:

- Host: for example `192.168.1.59` or `codex.local:8765`;
- Pairing key: the six-digit code;
- Device name: any short phone label.

The device token is encrypted by an Android Keystore key. Closing the app or
restarting Foreman reconnects with that token and reloads authoritative provider
state without replaying prompts or controls. One paired host token exposes Codex
and every available Claude Code capability on that host.

The Android new-session provider selector reports Codex and Claude Code
availability separately and shows a safe reason when a provider is unavailable.
New Claude sessions select a workspace, `sonnet` or `haiku`, and one exact permission mode:
`default`, `dontAsk`, `acceptEdits`, `plan`, `auto`, or
`bypassPermissions`. Bypass permissions is never selected by default and
requires a high-risk confirmation. External Claude CLI sessions are shown as
**Resumable · Not live-attached**. **Resume in Foreman** starts a new managed
query on the same Claude session ID; Foreman cannot view current external events,
interrupt that external process, answer its approval, or use Claude Remote
Control. Claude images and transcript search are unavailable, while existing
Codex images, search, approvals, and structured input remain unchanged.

To receive completion and attention alerts for active turns, enable **Notify
for active turns** in Foreman's settings and grant Android's notification
permission. A foreground-service notification remains visible only while turns
on the active host are being monitored. Provider-aware lifecycle alerts use
generic privacy-safe text and open the exact host/provider/session. Codex
approval notifications likewise reveal no request content; tapping one reopens
its exact pending card, and resolution elsewhere removes it.
When the same session is visibly focused in web or Android, its event alert,
sound, and badge are suppressed on both clients. Android still shows its quiet
foreground-service card while global monitoring is enabled.

Linux installation never runs Gradle or requires Java.

## Uninstall

Stop and remove the installed service and launcher without touching the cloned
repository:

```sh
systemctl --user disable --now foreman.service
rm ~/.config/systemd/user/foreman.service
systemctl --user daemon-reload
rm ~/.local/bin/foreman
rm -r ~/.local/share/foreman
```

The commands above preserve device tokens and configuration. After confirming
they are no longer needed, remove `~/.local/state/foreman` and
`~/.config/foreman` separately.

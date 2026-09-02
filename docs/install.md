# Install and run

## Linux host

Install and authenticate at least one supported provider CLI: Codex or Claude
Code. Confirm `codex --version` or `claude --version` works; installing both is
also supported. Foreman never installs or authenticates either CLI. It needs
Python 3.10+, OpenSSL, `curl`, Bash, and a user systemd session. Its pinned
`websockets` dependency is included in the install payload, so installation
doesn't require pip, venv support, or root access.

The recommended installation resolves and verifies the newest complete stable
release before running its versioned installer:

```sh
curl -fsSL https://raw.githubusercontent.com/mkaltner/foreman/main/scripts/install-foreman.sh | sh
foreman status
foreman pair
foreman web
```

The default command always selects the latest complete stable release.

The bootstrapper downloads into a mode-`0700` temporary directory, cleans it on
success, failure, and signals, and makes no installation change during release
selection or verification. It prints the selected release before delegating to
the archive's `install.sh`. A same-version run is a normal transactional
reinstall: the bundled installer stages and validates the complete payload,
preserves configuration and state, and rolls back an activation failure.

### Download and inspect first

Users who avoid `curl | sh` can keep the bootstrap step visible:

```sh
install_tmp="$(mktemp -d)"
chmod 700 "$install_tmp"
curl -fsSL https://raw.githubusercontent.com/mkaltner/foreman/main/scripts/install-foreman.sh -o "$install_tmp/install-foreman.sh"
less "$install_tmp/install-foreman.sh"
sh "$install_tmp/install-foreman.sh"
rm -rf -- "$install_tmp"
```

If inspection is interrupted, remove the private directory when finished. The
bootstrapper's own temporary release directory is still cleaned automatically.

### Manual signed release

The [GitHub release page](https://github.com/mkaltner/foreman/releases) exposes
the versioned Linux archive plus `SHA256SUMS`, `SHA256SUMS.sig`, and
`foreman-release-cert.pem`. A manual installation must download the exact five
custom assets into an empty private directory, verify that the certificate DER
SHA-256 is
`80d479d1a8f9f038c6977a1cfb68a2b45c3117492c364620e48babebf1810ad3`,
verify the detached signature over the unmodified checksum manifest, verify the
archive checksum named by that signed manifest, and reject unsafe archive
entries before extraction. Then run only the verified archive's `install.sh`.
The bootstrapper is the maintained implementation of those checks; its source
is designed to be downloaded and inspected for a manual workflow. Never use
GitHub's mutable branch archive or automatic source archive as a release
payload.

### Source checkout

The source-checkout installer remains available for development and recovery:

```sh
git clone https://github.com/mkaltner/foreman.git
cd foreman
./install.sh
```

Update a source checkout by pulling it and rerunning the transactional
installer. For a reproducible release, verify and detach the exact tag first.
Do not run an unreviewed branch checkout on a host that controls sensitive
provider sessions:

```sh
release_tag="$(curl -fsSL https://api.github.com/repos/mkaltner/foreman/releases/latest | python3 -c 'import json, sys; print(json.load(sys.stdin)["tag_name"])')"
git fetch origin tag "$release_tag"
git checkout --detach "$release_tag"
python3 scripts/verify_release.py --tag "$release_tag"
./install.sh
```

See [`bootstrap-installer.md`](bootstrap-installer.md) for release selection,
trust boundaries, failure behavior, and key rotation.

Claude Code web and Android support requires an authenticated native Claude Code
CLI, Node.js 20 or newer, and the exact SDK lock under `linux/claude_bridge`.
Tagged Linux archives include the production SDK dependency and notices, so
installation remains offline and never runs npm. From a source checkout,
`install.sh` prepares the lockfile-exact production dependency inside its
disposable staging directory when needed. That step requires npm and may access
the package registry. To prepare the checkout ahead of time instead, run:

```sh
cd linux/claude_bridge
npm ci --omit=dev --ignore-scripts
cd ../..
./install.sh
```

If Claude is the only installed provider, a missing Node runtime or failed SDK
preparation aborts before Foreman replaces an installation. When Codex is also
usable, Claude can remain unavailable without blocking Codex. Node is therefore
required only for Claude. Authenticate each provider locally with its native
CLI; Foreman pairing does not authenticate either provider. Check Claude adapter
detection with `foreman claude-status`.

The rootless installer copies Foreman to `~/.local/share/foreman`, installs the
CLI in `~/.local/bin`, installs and starts `foreman.service`, enables the
boot-time `foreman-update-recovery.service` oneshot, and creates:

```text
~/.config/foreman/foreman.env
~/.local/state/foreman
```

Both units run under the systemd user manager. On a headless or SSH-managed
host, enabling a unit does not by itself keep that user manager alive after the
last login session ends. Enable lingering once so Foreman starts at boot and
continues running after logout:

```sh
sudo loginctl enable-linger "$USER"
loginctl show-user "$USER" -p Linger
```

The verification command should report `Linger=yes`. This host-level setting
may require administrator privileges; Foreman's installer remains rootless and
does not invoke `sudo`.

Reinstalling validates a staged copy before replacing the running installation.
An older `~/.local/share/foreman/venv` is retained through service activation
and removed only after the system-Python service starts successfully.
The installer never replaces `~/.config/foreman/foreman.env` or
`~/.local/state/foreman`, so configuration and paired-client tokens survive a
successful reinstall and an activation rollback. The installed application
directory is replaced as a unit, so files left by an older alpha are removed.
If activation fails, the previous application directory, launcher, systemd
units, and updater helper are restored before the previous service is restarted.

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
# Present only when Codex was detected:
FOREMAN_CODEX_EXECUTABLE=/absolute/path/to/codex
# Optional:
FOREMAN_CLAUDE_EXECUTABLE=/absolute/path/to/claude
FOREMAN_NODE_EXECUTABLE=/absolute/path/to/node
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

The sideloaded Android app can download and verify its own newer stable APK from
**Settings → About**. This is separate from **Review server update**. Foreman
uses the official signed release contract, verifies the current APK signing
identity and strictly newer version code, explains Android's per-app **Install
unknown apps** permission only when needed, and then opens Android's system
installer for explicit confirmation. Canceling installation retains the
verified APK for retry; interrupted downloads resume from app-private bounded
storage. See [`android-apk-updates.md`](android-apk-updates.md).

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
`bridge.mjs`; release-archive installation never downloads dependencies. A
source checkout can let `install.sh` perform the same production-only `npm ci`
inside its staging payload.

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
systemctl --user disable --now foreman.service foreman-update-recovery.service
rm ~/.config/systemd/user/foreman.service ~/.config/systemd/user/foreman-update-recovery.service
systemctl --user daemon-reload
rm ~/.local/bin/foreman ~/.local/libexec/foreman-updater
rm -r ~/.local/share/foreman
```

The commands above preserve device tokens and configuration. After confirming
they are no longer needed, remove `~/.local/state/foreman` and
`~/.config/foreman` separately.

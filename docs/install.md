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

The tagged Linux archive from a GitHub release contains the same install
payload and is an alternative to cloning the repository.

The rootless installer copies Foreman to `~/.local/share/foreman`, installs the
CLI in `~/.local/bin`, installs and starts `foreman.service`, and creates:

```text
~/.config/foreman/foreman.env
~/.local/state/foreman
```

Reinstalling validates a staged copy before replacing the running installation.
An older `~/.local/share/foreman/venv` is retained through service activation
and removed only after the system-Python service starts successfully.

Configuration defaults:

```sh
FOREMAN_HOST=0.0.0.0
FOREMAN_PORT=8765
FOREMAN_WEB_HOST=0.0.0.0
FOREMAN_WEB_PORT=8766
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

After edits, use `foreman restart`. `foreman logs` follows the user journal.
`foreman web` prints the configured local web URL; replace `localhost` with the
host's trusted-LAN address when connecting remotely. The web client asks for
the same one-time `foreman pair` code and stores its persistent device token in
browser `localStorage`. The one-time code isn't retained. Browser storage is
less protected than Android Keystore, so use the **Disconnect and forget host**
action on shared browsers.

The CLI controls one unified service:

| Command | Effect |
| --- | --- |
| `foreman start` | Start the Android TCP and browser HTTP/WebSocket listeners. |
| `foreman stop` | Stop both listeners. |
| `foreman restart` | Restart both listeners after an update or configuration change. |
| `foreman status` | Show the systemd user-service status. |
| `foreman logs` | Follow the service journal. |
| `foreman web` | Print the browser URL; it does not start a second web process. |
| `foreman pair` | Create a single-use pairing code valid for ten minutes. |

To install specifically for browser use, no additional build or package is
needed. Run `./install.sh`, verify `foreman status`, open the URL from
`foreman web`, and enter a fresh code from `foreman pair`. The installed,
prebuilt SPA uses the page's own port for its authenticated WebSocket connection.
After authentication it opens on the live dashboard. **Sessions** retains the
conversation list and full interaction view; **Settings** contains appearance,
notification, and connection preferences.

The dashboard shows current session summaries, repository and non-Git workspace
groups, attention states, oldest active work, event freshness, connected-client
counts, a disclosure for paired-client token revocation, and a bounded recent
feed while the browser is connected. Revocation disconnects that client but
does not delete its sessions. “No recent
activity” is a ten-minute browser heuristic and never changes a turn. Approval
and structured-input waits must be resolved in another compatible Codex client.
Recent activity is intentionally not a persistent audit history.

Browser turn notifications can be enabled under **Settings → Notifications**.
Browsers require HTTPS or localhost for system notifications; plain LAN HTTP
addresses cannot request notification permission. Notifications cover turns
that finish, fail, are interrupted, or need attention while Foreman remains
open in a background tab. A trusted same-origin HTTPS reverse proxy enables the
feature for remote browsers.

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
restarting Foreman reconnects with that token and reloads current Codex state.

To receive completion and attention alerts for active turns, enable **Notify
for active turns** in Foreman's settings and grant Android's notification
permission. A foreground-service notification remains visible only while turns
are being monitored. Tapping a result notification reopens its session.

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

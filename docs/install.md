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
FOREMAN_REPOSITORY_ROOT=/home/you/projects
FOREMAN_CODEX_EXECUTABLE=/absolute/path/to/codex
# Optional:
FOREMAN_CODEX_SOCKET=/absolute/path/to/app-server-control.sock
FOREMAN_CODEX_FALLBACK_SOCKET=/absolute/path/to/foreman-codex-app-server.sock
```

`FOREMAN_CODEX_SOCKET` is attach-only. Foreman never starts or replaces a
listener there. The fallback defaults to
`~/.local/state/foreman/codex-app-server.sock` and cannot provide Desktop live
co-presence.

After edits, use `foreman restart`. `foreman logs` follows the user journal.
Allow TCP port 8765 only on the trusted LAN; the prototype authenticates but
does not encrypt transport.

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

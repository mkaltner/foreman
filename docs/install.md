# Install and run

## Linux host

Install Codex, authenticate it, and confirm `codex --version` works. Foreman
needs Python 3.10+, Git, and a user systemd session.

```sh
./install.sh
foreman status
foreman pair
```

The rootless installer copies Foreman to `~/.local/share/foreman`, installs the
CLI in `~/.local/bin`, installs and starts `foreman.service`, and creates:

```text
~/.config/foreman/foreman.env
~/.local/state/foreman
```

Configuration defaults:

```sh
FOREMAN_HOST=0.0.0.0
FOREMAN_PORT=8765
FOREMAN_REPOSITORY_ROOT=/home/you/projects
FOREMAN_CODEX_EXECUTABLE=/absolute/path/to/codex
```

After edits, use `foreman restart`. `foreman logs` follows the user journal.
Allow TCP port 8765 only on the trusted LAN; the prototype authenticates but
does not encrypt transport.

## Android phone

Open `android/` in current Android Studio (compile SDK 37), build/install the
debug app, and run `foreman pair` immediately before setup. Enter:

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

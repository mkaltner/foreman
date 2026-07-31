#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
config_dir="$HOME/.config/foreman"
state_dir="$HOME/.local/state/foreman"
install_dir="$HOME/.local/share/foreman"
install_parent="$HOME/.local/share"
bin_dir="$HOME/.local/bin"
unit_dir="$HOME/.config/systemd/user"
config_file="$config_dir/foreman.env"
launcher_file="$bin_dir/foreman"
unit_file="$unit_dir/foreman.service"
pinned_version="$(sed -n 's/^websockets==//p' "$project_dir/requirements.txt")"
staging_dir=""
backup_dir=""
rollback_required=0
had_launcher=0
had_unit=0

cleanup() {
  status=$?
  set +e
  if [[ "$rollback_required" == 1 && -n "$backup_dir" && -d "$backup_dir/install" ]]; then
    systemctl --user stop foreman.service >/dev/null 2>&1
    rm -rf -- "$install_dir"
    mv -- "$backup_dir/install" "$install_dir"
    if [[ "$had_launcher" == 1 ]]; then
      install -m 755 "$backup_dir/foreman" "$launcher_file"
    else
      rm -f -- "$launcher_file"
    fi
    if [[ "$had_unit" == 1 ]]; then
      install -m 644 "$backup_dir/foreman.service" "$unit_file"
    else
      rm -f -- "$unit_file"
    fi
    systemctl --user daemon-reload >/dev/null 2>&1
    systemctl --user restart foreman.service >/dev/null 2>&1
  fi
  if [[ -n "$staging_dir" && -d "$staging_dir" ]]; then
    rm -rf -- "$staging_dir"
  fi
  if [[ -n "$backup_dir" && -d "$backup_dir" ]]; then
    rm -rf -- "$backup_dir"
  fi
  exit "$status"
}
trap cleanup EXIT

command -v python3 >/dev/null || {
  echo "python3 is required" >&2
  exit 1
}
python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 10))' || {
  echo "Python 3.10 or newer is required" >&2
  exit 1
}
[[ -d "$project_dir/linux/vendor/websockets" ]] || {
  echo "Vendored Python dependencies are missing from the install payload" >&2
  exit 1
}
[[ -f "$project_dir/web/dist/index.html" ]] || {
  echo "Prebuilt web assets are missing; maintainers must run npm ci && npm run build in web/" >&2
  exit 1
}
compgen -G "$project_dir/web/dist/assets/*" >/dev/null || {
  echo "Prebuilt web assets are incomplete" >&2
  exit 1
}
[[ -n "$pinned_version" ]] || {
  echo "requirements.txt must pin websockets with ==" >&2
  exit 1
}
codex_executable="$(command -v codex || true)"
if [[ -z "$codex_executable" ]]; then
  echo "codex is required and must be on PATH" >&2
  exit 1
fi
codex_executable="$(readlink -f "$codex_executable")"
claude_executable="$(command -v claude || true)"

install -d -m 700 "$config_dir" "$state_dir"
install -d -m 755 "$install_parent" "$bin_dir" "$unit_dir"

staging_dir="$(mktemp -d "$install_parent/.foreman-install.XXXXXX")"
install -m 755 "$project_dir/linux/foreman_service.py" "$staging_dir/foreman_service.py"
install -m 644 "$project_dir/linux/codex.py" "$staging_dir/codex.py"
install -m 644 "$project_dir/linux/approvals.py" "$staging_dir/approvals.py"
install -m 644 "$project_dir/linux/inputs.py" "$staging_dir/inputs.py"
install -m 644 "$project_dir/linux/protocol.py" "$staging_dir/protocol.py"
install -m 644 "$project_dir/linux/state.py" "$staging_dir/state.py"
install -m 644 "$project_dir/linux/diagnostics.py" "$staging_dir/diagnostics.py"
install -m 644 "$project_dir/linux/claude_code.py" "$staging_dir/claude_code.py"
install -m 644 "$project_dir/linux/session_identity.py" "$staging_dir/session_identity.py"
cp -a "$project_dir/linux/claude_bridge" "$staging_dir/claude_bridge"
if [[ -L "$staging_dir/claude_bridge/node_modules" ]]; then
  rm -f -- "$staging_dir/claude_bridge/node_modules"
fi
if [[ -n "$claude_executable" ]] \
  && [[ ! -f "$staging_dir/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json" ]]; then
  echo "Claude Code support is unavailable: the install payload does not include the pinned Agent SDK" >&2
fi
install -m 644 "$project_dir/release.properties" "$staging_dir/release.properties"
cp -a "$project_dir/linux/vendor" "$staging_dir/vendor"
cp -a "$project_dir/web/dist" "$staging_dir/web"
if [[ -d "$install_dir/venv" ]]; then
  cp -a "$install_dir/venv" "$staging_dir/venv"
fi

python3 -m compileall -q \
  "$staging_dir/foreman_service.py" \
  "$staging_dir/codex.py" \
  "$staging_dir/approvals.py" \
  "$staging_dir/inputs.py" \
  "$staging_dir/protocol.py" \
  "$staging_dir/state.py" \
  "$staging_dir/diagnostics.py" \
  "$staging_dir/claude_code.py" \
  "$staging_dir/session_identity.py" \
  "$staging_dir/vendor"
FOREMAN_STAGING_DIR="$staging_dir" \
FOREMAN_WEBSOCKETS_VERSION="$pinned_version" \
python3 -c '
import os, pathlib, sys
root = pathlib.Path(os.environ["FOREMAN_STAGING_DIR"])
sys.path.insert(0, str(root))
import codex
import websockets
assert websockets.__version__ == os.environ["FOREMAN_WEBSOCKETS_VERSION"]
assert pathlib.Path(websockets.__file__).is_relative_to(root / "vendor")
from websockets.asyncio.client import unix_connect
from websockets.asyncio.server import unix_serve
'
python3 "$staging_dir/foreman_service.py" --help >/dev/null

if [[ ! -e "$config_file" ]]; then
  {
    printf 'FOREMAN_HOST=0.0.0.0\n'
    printf 'FOREMAN_PORT=8765\n'
    printf 'FOREMAN_WEB_HOST=0.0.0.0\n'
    printf 'FOREMAN_WEB_PORT=8766\n'
    printf 'FOREMAN_REMOTE_RESTART=0\n'
    printf 'FOREMAN_REPOSITORY_ROOT=%s\n' "$HOME/projects"
    printf 'FOREMAN_CODEX_EXECUTABLE=%s\n' "$codex_executable"
    if [[ -n "$claude_executable" ]]; then
      printf 'FOREMAN_CLAUDE_EXECUTABLE=%s\n' "$(readlink -f "$claude_executable")"
    fi
  } >"$config_file"
  chmod 600 "$config_file"
fi

backup_dir="$(mktemp -d "$install_parent/.foreman-backup.XXXXXX")"
if [[ -d "$install_dir" ]]; then
  mv -- "$install_dir" "$backup_dir/install"
else
  mkdir "$backup_dir/install"
fi
if [[ -e "$launcher_file" ]]; then
  cp -a "$launcher_file" "$backup_dir/foreman"
  had_launcher=1
fi
if [[ -e "$unit_file" ]]; then
  cp -a "$unit_file" "$backup_dir/foreman.service"
  had_unit=1
fi
mv -- "$staging_dir" "$install_dir"
staging_dir=""
rollback_required=1
install -m 755 "$project_dir/linux/foreman" "$launcher_file"
install -m 644 "$project_dir/linux/foreman.service" "$unit_file"

python3 -m compileall -q \
  "$install_dir/foreman_service.py" \
  "$install_dir/codex.py" \
  "$install_dir/approvals.py" \
  "$install_dir/inputs.py" \
  "$install_dir/protocol.py" \
  "$install_dir/state.py" \
  "$install_dir/claude_code.py" \
  "$install_dir/session_identity.py" \
  "$install_dir/vendor"
python3 "$install_dir/foreman_service.py" --help >/dev/null
systemctl --user daemon-reload
systemctl --user enable foreman.service
systemctl --user restart foreman.service
sleep 2
systemctl --user is-active --quiet foreman.service
rollback_required=0

rm -rf -- "$install_dir/venv"
rm -rf -- "$backup_dir"
backup_dir=""

echo
echo "Foreman is installed and running."
if [[ ":$PATH:" != *":$bin_dir:"* ]]; then
  echo "Add $bin_dir to PATH, then run:"
fi
echo "foreman pair"

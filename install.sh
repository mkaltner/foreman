#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
config_dir="$HOME/.config/foreman"
state_dir="$HOME/.local/state/foreman"
install_dir="$HOME/.local/share/foreman"
bin_dir="$HOME/.local/bin"
unit_dir="$HOME/.config/systemd/user"
config_file="$config_dir/foreman.env"

command -v python3 >/dev/null || {
  echo "python3 is required" >&2
  exit 1
}
codex_executable="$(command -v codex || true)"
if [[ -z "$codex_executable" ]]; then
  echo "codex is required and must be on PATH" >&2
  exit 1
fi
codex_executable="$(readlink -f "$codex_executable")"

install -d -m 700 "$config_dir" "$state_dir"
install -d -m 755 "$install_dir" "$bin_dir" "$unit_dir"
install -m 755 "$project_dir/linux/foreman_service.py" "$install_dir/foreman_service.py"
install -m 644 "$project_dir/linux/codex.py" "$install_dir/codex.py"
install -m 644 "$project_dir/linux/protocol.py" "$install_dir/protocol.py"
install -m 644 "$project_dir/linux/state.py" "$install_dir/state.py"
install -m 755 "$project_dir/linux/foreman" "$bin_dir/foreman"
install -m 644 "$project_dir/linux/foreman.service" "$unit_dir/foreman.service"

if [[ ! -e "$config_file" ]]; then
  {
    printf 'FOREMAN_HOST=0.0.0.0\n'
    printf 'FOREMAN_PORT=8765\n'
    printf 'FOREMAN_REPOSITORY_ROOT=%s\n' "$HOME/projects"
    printf 'FOREMAN_CODEX_EXECUTABLE=%s\n' "$codex_executable"
  } >"$config_file"
  chmod 600 "$config_file"
fi

python3 -m compileall -q "$install_dir"
systemctl --user daemon-reload
systemctl --user enable foreman.service
systemctl --user restart foreman.service

echo
echo "Foreman is installed and running."
if [[ ":$PATH:" != *":$bin_dir:"* ]]; then
  echo "Add $bin_dir to PATH, then run:"
fi
echo "foreman pair"

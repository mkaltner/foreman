#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python="${PYTHON:-python3}"
requirements="$project_dir/requirements.txt"
vendor_dir="$project_dir/linux/vendor"
staging_dir="$(mktemp -d "$project_dir/linux/.vendor.XXXXXX")"
pinned_version="$(sed -n 's/^websockets==//p' "$requirements")"

[[ -n "$pinned_version" ]] || {
  echo "requirements.txt must pin websockets with ==" >&2
  exit 1
}

cleanup() {
  rm -rf -- "$staging_dir"
}
trap cleanup EXIT

"$python" -m pip --version >/dev/null
"$python" -m pip install \
  --disable-pip-version-check \
  --no-compile \
  --no-deps \
  --only-binary=:all: \
  --platform any \
  --implementation py \
  --abi none \
  --target "$staging_dir/vendor" \
  --requirement "$requirements"

rm -rf -- "$staging_dir/vendor/bin"
sed -i '\#^../../bin/websockets,#d' \
  "$staging_dir/vendor/websockets-$pinned_version.dist-info/RECORD"
find "$staging_dir/vendor" -type d -name __pycache__ -prune -exec rm -rf -- {} +
find "$staging_dir/vendor" -type f -name '*.pyc' -delete

FOREMAN_WEBSOCKETS_VERSION="$pinned_version" \
PYTHONDONTWRITEBYTECODE=1 \
PYTHONPATH="$staging_dir/vendor" \
"$python" -c '
import importlib.metadata
import os
import pathlib
import websockets
assert websockets.__version__ == os.environ["FOREMAN_WEBSOCKETS_VERSION"]
metadata = pathlib.Path(importlib.metadata.distribution("websockets")._path)
assert (metadata / "licenses" / "LICENSE").is_file()
'

rm -rf -- "$vendor_dir"
mv -- "$staging_dir/vendor" "$vendor_dir"
echo "Vendored websockets $pinned_version in $vendor_dir"

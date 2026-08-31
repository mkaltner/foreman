#!/bin/sh
set -eu

# This bootstrapper intentionally contains only release acquisition and
# verification. The signed release's install.sh remains the installer.

RELEASES_API="https://api.github.com/repos/mkaltner/foreman/releases"
DOWNLOAD_PREFIX="https://github.com/mkaltner/foreman/releases/download"
SOURCE="Official Foreman GitHub releases"
TRUSTED_CERT_SHA256="80d479d1a8f9f038c6977a1cfb68a2b45c3117492c364620e48babebf1810ad3"
MAX_API_BYTES=524288
MAX_ARCHIVE_BYTES=536870912
MAX_SMALL_ASSET_BYTES=65536

temporary=""

cleanup() {
  status=$?
  trap - 0 HUP INT TERM
  if [ -n "$temporary" ] && [ -d "$temporary" ]; then
    rm -rf -- "$temporary"
  fi
  exit "$status"
}

fail() {
  echo "install-foreman: $*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: install-foreman.sh [--version vMAJOR.MINOR.PATCH]

Without --version, installs the newest complete stable Foreman release.
EOF
}

requested_version=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || fail "--version requires a v-prefixed stable version"
      [ -z "$requested_version" ] || fail "--version may be specified only once"
      requested_version=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[ "$(uname -s 2>/dev/null || true)" = "Linux" ] || fail "Linux is required"
for command_name in curl python3 openssl bash; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done
python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 10))' \
  || fail "Python 3.10 or newer is required"

if [ -n "$requested_version" ]; then
  python3 - "$requested_version" <<'PY' \
    || fail "--version must be a v-prefixed stable version such as v1.1.0"
import re
import sys
if re.fullmatch(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$", sys.argv[1]) is None:
    raise SystemExit(1)
PY
fi

umask 077
temporary="$(mktemp -d "${TMPDIR:-/tmp}/foreman-bootstrap.XXXXXX")" \
  || fail "could not create a private temporary directory"
chmod 700 "$temporary" || fail "could not secure the temporary directory"
trap cleanup 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fetch() {
  fetch_url=$1
  fetch_destination=$2
  fetch_limit=$3
  fetch_kind=$4
  fetch_result="$temporary/curl-result"

  if ! curl --disable --silent --show-error --location \
    --proto '=https' --proto-redir '=https' --tlsv1.2 \
    --connect-timeout 10 --max-time 180 --max-filesize "$fetch_limit" \
    --header 'Accept: application/vnd.github+json' \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    --header 'User-Agent: Foreman-bootstrap-installer' \
    --output "$fetch_destination" \
    --write-out '%{http_code}\n%{url_effective}\n' \
    "$fetch_url" >"$fetch_result"; then
    fail "download failed for $fetch_kind"
  fi

  fetch_status="$(sed -n '1p' "$fetch_result")"
  fetch_effective="$(sed -n '2p' "$fetch_result")"
  case "$fetch_status" in
    200) ;;
    403|429) fail "GitHub API rate limit exceeded while downloading $fetch_kind" ;;
    *) fail "GitHub returned HTTP $fetch_status while downloading $fetch_kind" ;;
  esac

  python3 - "$fetch_effective" "$fetch_kind" <<'PY' \
    || fail "download for $fetch_kind ended at an untrusted URL"
import sys
from urllib.parse import urlsplit

url, kind = sys.argv[1:]
parsed = urlsplit(url)
allowed = {"api.github.com"} if kind == "release metadata" else {
    "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"
}
if parsed.scheme != "https" or parsed.hostname not in allowed or parsed.username or parsed.password:
    raise SystemExit(1)
PY

  fetch_size="$(wc -c <"$fetch_destination" | tr -d '[:space:]')"
  [ "$fetch_size" -gt 0 ] 2>/dev/null || fail "$fetch_kind is empty"
  [ "$fetch_size" -le "$fetch_limit" ] 2>/dev/null || fail "$fetch_kind exceeds its size limit"
}

metadata="$temporary/release.json"
if [ -n "$requested_version" ]; then
  metadata_url="$RELEASES_API/tags/$requested_version"
else
  metadata_url="$RELEASES_API?per_page=20"
fi
fetch "$metadata_url" "$metadata" "$MAX_API_BYTES" "release metadata"

tag_file="$temporary/tag"
python3 - "$metadata" "$requested_version" "$DOWNLOAD_PREFIX" >"$tag_file" <<'PY' \
  || fail "GitHub release metadata has no complete stable Foreman release"
import json
import re
import sys
from urllib.parse import quote

metadata_path, requested, download_prefix = sys.argv[1:]
tag_pattern = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")

try:
    raw = open(metadata_path, "rb").read()
    decoded = json.loads(raw)
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("malformed GitHub API response") from error

def complete_stable(release):
    if not isinstance(release, dict):
        return None
    tag = release.get("tag_name")
    match = tag_pattern.fullmatch(tag) if isinstance(tag, str) else None
    if match is None or release.get("draft") is not False or release.get("prerelease") is not False:
        return None
    expected = {
        f"foreman-{tag}.apk",
        f"foreman-linux-{tag}.tar.gz",
        "SHA256SUMS",
        "SHA256SUMS.sig",
        "foreman-release-cert.pem",
    }
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) > 50:
        return None
    found = {}
    for asset in assets:
        if not isinstance(asset, dict):
            return None
        name = asset.get("name")
        size = asset.get("size")
        url = asset.get("browser_download_url")
        if name not in expected or name in found or not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            return None
        canonical = f"{download_prefix}/{quote(tag, safe='')}/{quote(name, safe='')}"
        if url != canonical:
            return None
        found[name] = size
    if set(found) != expected:
        return None
    if found[f"foreman-linux-{tag}.tar.gz"] > 512 * 1024 * 1024:
        return None
    if any(found[name] > 64 * 1024 for name in ("SHA256SUMS", "SHA256SUMS.sig", "foreman-release-cert.pem")):
        return None
    return tuple(int(part) for part in match.groups()), tag

if requested:
    candidate = complete_stable(decoded)
    if candidate is None or candidate[1] != requested:
        raise SystemExit("the requested release is not a complete stable release")
else:
    if not isinstance(decoded, list):
        raise SystemExit("malformed GitHub API response")
    candidates = [candidate for release in decoded[:20] if (candidate := complete_stable(release))]
    if not candidates:
        raise SystemExit("no complete stable release found")
    candidate = max(candidates)
print(candidate[1])
PY

tag="$(cat "$tag_file")"
case "$tag" in
  v[0-9]*.[0-9]*.[0-9]*) ;;
  *) fail "resolved release tag is invalid" ;;
esac

archive_name="foreman-linux-$tag.tar.gz"
archive="$temporary/$archive_name"
manifest="$temporary/SHA256SUMS"
signature="$temporary/SHA256SUMS.sig"
certificate="$temporary/foreman-release-cert.pem"
release_base="$DOWNLOAD_PREFIX/$tag"

fetch "$release_base/SHA256SUMS" "$manifest" "$MAX_SMALL_ASSET_BYTES" "SHA256SUMS"
fetch "$release_base/SHA256SUMS.sig" "$signature" "$MAX_SMALL_ASSET_BYTES" "SHA256SUMS signature"
fetch "$release_base/foreman-release-cert.pem" "$certificate" "$MAX_SMALL_ASSET_BYTES" "release certificate"
fetch "$release_base/$archive_name" "$archive" "$MAX_ARCHIVE_BYTES" "Linux release archive"

certificate_der="$temporary/release-cert.der"
public_key="$temporary/release-public-key.pem"
openssl x509 -in "$certificate" -outform DER -out "$certificate_der" 2>/dev/null \
  || fail "release signing certificate is invalid"
actual_cert_sha256="$(python3 - "$certificate_der" <<'PY'
import hashlib
import sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
)"
[ "$actual_cert_sha256" = "$TRUSTED_CERT_SHA256" ] \
  || fail "release signing certificate does not match Foreman's pinned identity"
openssl x509 -in "$certificate" -pubkey -noout >"$public_key" 2>/dev/null \
  || fail "release signing certificate has no valid public key"
openssl dgst -sha256 -verify "$public_key" -signature "$signature" "$manifest" >/dev/null 2>&1 \
  || fail "SHA256SUMS signature verification failed"

python3 - "$manifest" "$archive" "$tag" <<'PY' \
  || fail "signed checksum verification failed"
import hashlib
import re
import sys

manifest_path, archive_path, tag = sys.argv[1:]
pattern = re.compile(r"^([0-9A-Fa-f]{64}) [ *]([^/]+)$")
expected = {f"foreman-{tag}.apk", f"foreman-linux-{tag}.tar.gz"}
checksums = {}
try:
    lines = open(manifest_path, "r", encoding="ascii").read().splitlines()
except (OSError, UnicodeError) as error:
    raise SystemExit(1) from error
for line in lines:
    match = pattern.fullmatch(line)
    if match is None or match.group(2) in checksums:
        raise SystemExit(1)
    checksums[match.group(2)] = match.group(1).lower()
if set(checksums) != expected:
    raise SystemExit(1)
digest = hashlib.sha256()
with open(archive_path, "rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)
if digest.hexdigest() != checksums[f"foreman-linux-{tag}.tar.gz"]:
    raise SystemExit(1)
PY

payload="$temporary/payload"
python3 - "$archive" "$payload" "$tag" "$TRUSTED_CERT_SHA256" <<'PY' \
  || fail "Linux release archive is unsafe or incomplete"
from pathlib import Path, PurePosixPath
import sys
import tarfile

archive_path, destination_text, tag, trust = sys.argv[1:]
destination = Path(destination_text)
required = {
    "install.sh", "release.properties", "requirements.txt",
    "linux/foreman", "linux/foreman.service", "linux/foreman-update-recovery.service",
    "linux/foreman_service.py", "linux/codex.py", "linux/approvals.py",
    "linux/inputs.py", "linux/protocol.py", "linux/state.py", "linux/diagnostics.py",
    "linux/claude_code.py", "linux/session_identity.py", "linux/release_updates.py",
    "linux/server_update.py", "linux/update_cli.py", "linux/foreman_updater.py",
    "linux/claude_bridge/bridge.mjs", "linux/claude_bridge/package.json",
    "linux/claude_bridge/package-lock.json",
    "linux/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json",
    "web/dist/index.html",
}
names = set()
total = 0
destination.mkdir(mode=0o700)
try:
    with tarfile.open(archive_path, "r:gz") as bundle:
        members = bundle.getmembers()
        if len(members) > 20_000:
            raise ValueError("too many entries")
        for member in members:
            pure = PurePosixPath(member.name)
            canonical = pure.as_posix()
            canonical_entry = member.name in {canonical, canonical + "/"} if member.isdir() else member.name == canonical
            if (
                not member.name or pure.is_absolute() or ".." in pure.parts or not canonical_entry
                or canonical in names or member.issym() or member.islnk() or member.isdev()
                or not (member.isfile() or member.isdir())
            ):
                raise ValueError("unsafe entry")
            names.add(canonical)
            if member.isfile():
                total += member.size
                if member.size < 0 or total > 1024 * 1024 * 1024:
                    raise ValueError("extraction limit")
        if not required.issubset(names):
            raise ValueError("missing required files")
        bundle.extractall(destination, members=members)
except (OSError, tarfile.TarError, ValueError) as error:
    raise SystemExit(1) from error

properties = {}
try:
    for raw_line in (destination / "release.properties").read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith("#"):
            key, separator, value = line.partition("=")
            if not separator or not key.strip() or not value.strip() or key.strip() in properties:
                raise ValueError("invalid release properties")
            properties[key.strip()] = value.strip()
except (OSError, UnicodeError, ValueError) as error:
    raise SystemExit(1) from error
if (
    properties.get("foremanVersion") != tag[1:]
    or properties.get("releaseBuild") != "true"
    or properties.get("androidSigningCertificateSha256") != trust
):
    raise SystemExit(1)
if not (destination / "install.sh").is_file():
    raise SystemExit(1)
PY

echo "Resolved Foreman $tag from $SOURCE."
echo "Running the verified bundled installer..."
bash "$payload/install.sh"

echo
echo "Foreman $tag was installed from $SOURCE."
echo "Next steps:"
echo "  foreman status"
echo "  foreman pair"
echo "  foreman web"

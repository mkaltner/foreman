#!/usr/bin/env bash
set -euo pipefail

release_tag="${1:?usage: verify_release_payload.sh RELEASE_TAG DIRECTORY}"
asset_directory="${2:?usage: verify_release_payload.sh RELEASE_TAG DIRECTORY}"

fail() {
  echo "::error::$1" >&2
  exit 1
}

require_archive_entry() {
  grep -Fxq "$1" <<<"$archive_listing" || fail "Linux archive is missing $1"
}

python3 scripts/verify_release_assets.py --tag "$release_tag" --directory "$asset_directory"

apk="$asset_directory/foreman-${release_tag}.apk"
archive="$asset_directory/foreman-linux-${release_tag}.tar.gz"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" ]] || fail "Android SDK root is unavailable"
apksigner_path="$(find "$sdk_root/build-tools" -type f -name apksigner | sort -V | tail -1)"
aapt2_path="$(find "$sdk_root/build-tools" -type f -name aapt2 | sort -V | tail -1)"
[[ -x "$apksigner_path" ]] || fail "Android apksigner was not found"
[[ -x "$aapt2_path" ]] || fail "Android aapt2 was not found"

expected_version="$(sed -n 's/^foremanVersion=//p' release.properties)"
expected_code="$(sed -n 's/^androidVersionCode=//p' release.properties)"
expected_cert="$(sed -n 's/^androidSigningCertificateSha256=//p' release.properties)"
badging="$("$aapt2_path" dump badging "$apk" | sed -n '1p')"
grep -Fq "versionName='$expected_version'" <<<"$badging" || fail "APK version name does not match release.properties"
grep -Fq "versionCode='$expected_code'" <<<"$badging" || fail "APK version code does not match release.properties"
signing="$("$apksigner_path" verify --verbose --print-certs "$apk")"
grep -Fq 'Verifies' <<<"$signing" || fail "APK signature verification failed"
python3 scripts/verify_apk_certificate.py --expected "$expected_cert" <<<"$signing"

archive_listing="$(tar -tzf "$archive")"
require_archive_entry 'linux/vendor/websockets-16.1.1.dist-info/licenses/LICENSE'
require_archive_entry 'web/dist/index.html'
require_archive_entry 'release.properties'
require_archive_entry 'LICENSE'
require_archive_entry 'THIRD_PARTY_NOTICES.md'
require_archive_entry 'linux/claude_code.py'
require_archive_entry 'linux/release_updates.py'
require_archive_entry 'linux/claude_bridge/bridge.mjs'
require_archive_entry 'linux/claude_bridge/package-lock.json'
require_archive_entry 'linux/claude_bridge/node_modules/@anthropic-ai/claude-agent-sdk/package.json'
if grep -Eq 'linux/claude_bridge/(bridge\.test|test_fake_sdk)\.mjs' <<<"$archive_listing"; then
  fail "Linux archive contains Claude bridge test fixtures"
fi
if grep -Eq '(^|/)(__pycache__/|[^/]+\.py[co]$)' <<<"$archive_listing"; then
  fail "Linux archive contains Python cache files"
fi

echo "release payload verified for $release_tag"

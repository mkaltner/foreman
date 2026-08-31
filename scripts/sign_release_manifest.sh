#!/usr/bin/env bash
set -euo pipefail

manifest="${1:?usage: sign_release_manifest.sh MANIFEST OUTPUT_DIRECTORY}"
output_directory="${2:?usage: sign_release_manifest.sh MANIFEST OUTPUT_DIRECTORY}"
: "${FOREMAN_ANDROID_KEYSTORE:?FOREMAN_ANDROID_KEYSTORE is required}"
: "${FOREMAN_ANDROID_KEYSTORE_PASSWORD:?FOREMAN_ANDROID_KEYSTORE_PASSWORD is required}"
: "${FOREMAN_ANDROID_KEY_ALIAS:?FOREMAN_ANDROID_KEY_ALIAS is required}"
: "${FOREMAN_ANDROID_KEY_PASSWORD:?FOREMAN_ANDROID_KEY_PASSWORD is required}"
export FOREMAN_ANDROID_KEYSTORE_PASSWORD FOREMAN_ANDROID_KEY_PASSWORD

temporary="$(mktemp -d)"
cleanup() { rm -rf -- "$temporary"; }
trap cleanup EXIT
chmod 700 "$temporary"

export FOREMAN_RELEASE_P12_PASSWORD
FOREMAN_RELEASE_P12_PASSWORD="$(openssl rand -hex 32)"
keytool -importkeystore -noprompt \
  -srckeystore "$FOREMAN_ANDROID_KEYSTORE" \
  -srcstorepass:env FOREMAN_ANDROID_KEYSTORE_PASSWORD \
  -srcalias "$FOREMAN_ANDROID_KEY_ALIAS" \
  -srckeypass:env FOREMAN_ANDROID_KEY_PASSWORD \
  -destkeystore "$temporary/release.p12" \
  -deststoretype PKCS12 \
  -deststorepass:env FOREMAN_RELEASE_P12_PASSWORD \
  -destkeypass:env FOREMAN_RELEASE_P12_PASSWORD
keytool -exportcert -rfc \
  -keystore "$FOREMAN_ANDROID_KEYSTORE" \
  -storepass:env FOREMAN_ANDROID_KEYSTORE_PASSWORD \
  -alias "$FOREMAN_ANDROID_KEY_ALIAS" \
  > "$output_directory/foreman-release-cert.pem"
openssl pkcs12 -in "$temporary/release.p12" -nocerts -nodes \
  -passin env:FOREMAN_RELEASE_P12_PASSWORD -out "$temporary/release-key.pem"
chmod 600 "$temporary/release-key.pem"
openssl dgst -sha256 -sign "$temporary/release-key.pem" \
  -out "$output_directory/SHA256SUMS.sig" "$manifest"

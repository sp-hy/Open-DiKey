#!/usr/bin/env bash
# Creates a release keystore and prints GitHub Actions secret values.
# Run once, store the .jks + passwords somewhere safe, then add the secrets to the repo.
set -euo pipefail

OUT_FILE="${1:-open-dikey-release.jks}"
ALIAS="${2:-open-dikey}"
VALIDITY_DAYS="${3:-10000}"

if [[ -e "$OUT_FILE" ]]; then
  echo "Refusing to overwrite existing $OUT_FILE" >&2
  exit 1
fi

read -r -s -p "Keystore password: " STORE_PASSWORD
echo
read -r -s -p "Confirm keystore password: " STORE_PASSWORD_CONFIRM
echo
read -r -s -p "Key password (often same as keystore): " KEY_PASSWORD
echo

if [[ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]]; then
  echo "Keystore passwords do not match" >&2
  exit 1
fi

echo "Generating $OUT_FILE ..."
keytool -genkeypair \
  -keystore "$OUT_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=Open DiKey, OU=Release, O=sp-hy, L=Unknown, ST=Unknown, C=AU"

BASE64="$(base64 -w0 "$OUT_FILE" 2>/dev/null || base64 "$OUT_FILE" | tr -d '\n')"

cat <<EOF

Add these GitHub repo secrets (Settings → Secrets and variables → Actions):
  SIGNING_KEYSTORE_BASE64  = <paste below>
  SIGNING_STORE_PASSWORD   = (the keystore password you entered)
  SIGNING_KEY_ALIAS        = $ALIAS
  SIGNING_KEY_PASSWORD     = (the key password you entered)

SIGNING_KEYSTORE_BASE64 value:
$BASE64

Keep $OUT_FILE and the passwords offline. Do not commit them.
EOF

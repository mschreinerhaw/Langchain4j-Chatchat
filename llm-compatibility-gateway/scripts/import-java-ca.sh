#!/bin/sh
set -eu
if [ "$#" -ne 2 ] || [ -z "${JAVA_TRUSTSTORE_PASSWORD:-}" ]; then
  echo 'Usage: set JAVA_TRUSTSTORE_PASSWORD and pass the CA bundle and new PKCS12 path' >&2
  exit 2
fi
if [ -e "$2" ]; then
  echo 'Use a new TrustStore path to avoid replacing an existing store' >&2
  exit 2
fi
umask 077
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
awk -v dir="$work" '/^-----BEGIN CERTIFICATE-----/{n++; file=dir "/cert-" n ".pem"} file{print > file} /^-----END CERTIFICATE-----/{close(file);file=""}' "$1"
for cert in "$work"/cert-*.pem; do
  [ -f "$cert" ] || { echo 'No certificate in bundle' >&2; exit 1; }
  alias=$(basename "$cert" .pem)
  keytool -importcert -noprompt -alias "gateway-$alias" -file "$cert" -storetype PKCS12 \
    -keystore "$work/trust.p12" -storepass:env JAVA_TRUSTSTORE_PASSWORD
done
cp -- "$work/trust.p12" "$2"

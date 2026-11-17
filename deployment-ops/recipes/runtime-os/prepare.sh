#!/bin/bash
set -euo pipefail
umask 077
mkdir -p /shared /certs /node-data
javac -d /tmp /opt/ops-config/Bootstrap.java
java -cp /tmp Bootstrap
if [ ! -f /certs/complete ]; then
 cd /certs
 openssl req -x509 -newkey rsa:2048 -nodes -keyout ca-key.pem -out root-ca.pem -days 3650 -subj /CN=runtime-os-local-ca >/dev/null 2>&1
 openssl req -new -newkey rsa:2048 -nodes -keyout node-key.pem -out node.csr -subj /CN=chatchat-opensearch >/dev/null 2>&1
 printf 'subjectAltName=DNS:opensearch,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n' > node.ext
 openssl x509 -req -in node.csr -CA root-ca.pem -CAkey ca-key.pem -CAcreateserial -out node.pem -days 3650 -extfile node.ext >/dev/null 2>&1
 openssl req -new -newkey rsa:2048 -nodes -keyout admin-key.pem -out admin.csr -subj /CN=chatchat-opensearch-admin >/dev/null 2>&1
 openssl x509 -req -in admin.csr -CA root-ca.pem -CAkey ca-key.pem -CAcreateserial -out admin.pem -days 3650 >/dev/null 2>&1
 # A stopped, incomplete bootstrap may already contain this private truststore.
 rm -f /shared/opensearch-truststore.p12
 keytool -importcert -noprompt -alias runtime-ca -file root-ca.pem -keystore /shared/opensearch-truststore.p12 -storetype PKCS12 -storepass changeit >/dev/null 2>&1
 chmod 644 root-ca.pem node.pem admin.pem /shared/opensearch-truststore.p12
 chmod 600 node-key.pem admin-key.pem ca-key.pem
 chown 1000:1000 node-key.pem admin-key.pem node.pem admin.pem root-ca.pem
 touch complete
fi
printf 'Private CA, credentials and truststore are ready.\n'

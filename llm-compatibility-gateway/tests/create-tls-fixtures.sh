#!/bin/sh
set -eu
umask 077
cd /out
mkdir -p bootstrap
openssl req -x509 -newkey rsa:2048 -noenc -days 365 -subj '/CN=Gateway Fixture Root' \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:1' -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -keyout root.key -out bootstrap/ca.crt 2>/dev/null
openssl req -new -newkey rsa:2048 -noenc -subj '/CN=Gateway Fixture Intermediate' -keyout intermediate.key -out intermediate.csr 2>/dev/null
cat >intermediate.ext <<'EOF'
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF
openssl x509 -req -in intermediate.csr -CA bootstrap/ca.crt -CAkey root.key -set_serial 2 -days 300 -extfile intermediate.ext \
  -out intermediate.crt 2>/dev/null
cat >openssl-ca.cnf <<'EOF'
[ca]
default_ca=fixture
[fixture]
database=index.txt
serial=serial
new_certs_dir=.
certificate=intermediate.crt
private_key=intermediate.key
default_md=sha256
policy=dn
[dn]
commonName=supplied
EOF
: >index.txt
printf 'unique_subject = no\n' >index.txt.attr
printf '10\n' >serial
issue() {
  name=$1
  subject=$2
  san=$3
  shift 3
  cat >"$name.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$san
EOF
  openssl req -new -newkey rsa:2048 -noenc -subj "/CN=$subject" -keyout "$name.key" -out "$name.csr" 2>/dev/null
  openssl ca -batch -notext -config openssl-ca.cnf -extfile "$name.ext" "$@" -in "$name.csr" -out "$name.leaf" 2>/dev/null
  cat "$name.leaf" intermediate.crt >"$name.crt"
}
sans=DNS:localhost,IP:127.0.0.1,DNS:llm-gateway
issue initial localhost "$sans" -days 90
issue rotation localhost "$sans" -days 20
issue expired localhost "$sans" -startdate 200101000000Z -enddate 210101000000Z
issue wrong wrong.invalid DNS:wrong.invalid -days 60
issue upstream mock-tls DNS:mock-tls -days 60
cp initial.crt bootstrap/server.crt
cp initial.key bootstrap/server.key
openssl req -x509 -newkey rsa:2048 -noenc -days 90 -subj '/CN=Untrusted Fixture Root' \
  -addext 'basicConstraints=critical,CA:TRUE' -keyout wrong-root.key -out wrong-root.crt 2>/dev/null
openssl pkcs12 -export -in rotation.leaf -inkey rotation.key -certfile intermediate.crt -passout pass:fixture-password -out rotation.p12
openssl x509 -in initial.leaf -outform DER -out initial.der
openssl req -x509 -newkey rsa:2048 -noenc -days 90 -subj '/CN=mock-tls' \
  -addext 'subjectAltName=DNS:mock-tls' -addext 'basicConstraints=critical,CA:FALSE' \
  -addext 'extendedKeyUsage=serverAuth' -keyout self-upstream.key -out self-upstream.crt 2>/dev/null
chmod 755 bootstrap
chmod 644 bootstrap/ca.crt bootstrap/server.crt

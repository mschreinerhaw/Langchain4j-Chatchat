# OpenSearch TLS certificates

Production startup requires the following PEM files in this directory:

- `root-ca.pem`
- `node.pem`
- `node-key.pem`
- `admin.pem`
- `admin-key.pem`

The node certificate subject must be `CN=chatchat-opensearch` and its SAN must contain
`DNS:opensearch`, `DNS:localhost`, and `IP:127.0.0.1`. The administrator certificate
subject must be `CN=chatchat-opensearch-admin`. Both certificates must be signed by
`root-ca.pem`. Private keys must be unencrypted PKCS#8 PEM files readable only by the
deployment account and container UID `1000` (for example, owner/group read only; never
world-readable).

Generate and rotate these certificates through the organization's PKI. Do not commit
certificates, CA keys, node keys, or administrator keys to Git. After the one-shot
`opensearch-security-init` container succeeds, restrict access to `admin-key.pem`; it is
needed again only for an intentional security configuration update.

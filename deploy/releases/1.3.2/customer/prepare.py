#!/usr/bin/env python3
"""Initialize the installation's own credentials and directory layout."""
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile

ROOT = Path("/opt/runtime-os-docker-stage/config")
TEMPLATES = Path(__file__).resolve().parent / "config"
API = "chatchat-api:1.3.2"
BROKER = "cline-agent-server:1.3.2"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def create(path, content, mode=0o600, owner=0):
    if path.exists():
        return
    with path.open("x", encoding="utf-8") as file:
        file.write(content)
        os.fchmod(file.fileno(), mode)
        os.fchown(file.fileno(), owner, owner)


def seed_env(path, template):
    if not path.exists():
        create(path, template)
        return
    if path.is_symlink() or not path.is_file():
        raise RuntimeError(f"Unexpected environment file type: {path}")
    original = path.read_text()
    def name(line):
        match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*[:=]", line)
        return match.group(1) if match else None
    existing = {name(line) for line in original.splitlines()} - {None}
    missing = [line for line in template.splitlines() if name(line) and name(line) not in existing]
    if not missing:
        return
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as file:
        file.write(original.rstrip("\n") + "\n\n" + "\n".join(missing) + "\n")
        pending = Path(file.name)
    pending.replace(path)


def directory(path, owner=0, mode=0o750):
    if not path.exists():
        path.mkdir(parents=True, mode=mode)
        os.chown(path, owner, owner)
    if not path.is_dir() or path.is_symlink():
        raise RuntimeError(f"Unexpected directory type: {path}")


def certificates(certs):
    names = ["root-ca.pem", "node.pem", "node-key.pem", "admin.pem", "admin-key.pem"]
    trust = ROOT / "search-truststore.p12"
    if all((certs / name).is_file() for name in names) and trust.is_file():
        return
    if any(certs.iterdir()) or trust.exists():
        raise RuntimeError("Complete the existing private OpenSearch PKI before retrying")
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        run("openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "825",
            "-subj", "/CN=Runtime OS local OpenSearch CA", "-keyout", str(folder / "ca-key.pem"),
            "-out", str(folder / "root-ca.pem"))
        for name, cn in [("node", "chatchat-opensearch"), ("admin", "chatchat-opensearch-admin")]:
            extensions = folder / "extensions"
            extensions.write_text(
                "basicConstraints=CA:FALSE\nextendedKeyUsage=clientAuth"
                + (",serverAuth\nsubjectAltName=DNS:opensearch,DNS:localhost,IP:127.0.0.1" if name == "node" else "") + "\n"
            )
            run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN={cn}",
                "-keyout", str(folder / f"{name}-key.pem"), "-out", str(folder / f"{name}.csr"))
            run("openssl", "x509", "-req", "-in", str(folder / f"{name}.csr"),
                "-CA", str(folder / "root-ca.pem"), "-CAkey", str(folder / "ca-key.pem"),
                "-CAcreateserial", "-days", "365", "-extfile", str(extensions), "-out", str(folder / f"{name}.pem"))
        run("docker", "run", "--rm", "--pull", "never", "--network", "none", "--user", "0:0",
            "--entrypoint", "keytool", "-v", f"{folder}:/pki", API, "-importcert", "-noprompt",
            "-alias", "opensearch", "-file", "/pki/root-ca.pem", "-storetype", "PKCS12",
            "-keystore", "/pki/search-truststore.p12", "-storepass", "changeit")
        for name in names:
            path = certs / name
            shutil.copyfile(folder / name, path)
            os.chown(path, 1000, 1000)
            path.chmod(0o600 if name.endswith("-key.pem") else 0o644)
        shutil.copyfile(folder / trust.name, trust)
        trust.chmod(0o644)


def main():
    if os.geteuid() != 0:
        raise RuntimeError("Run as root on the target Linux host")
    if Path("/opt/runtime-os/production-current/compose.production.json").exists():
        raise RuntimeError("This host already has an active production deployment")
    os.umask(0o077)
    directory(ROOT, mode=0o700)
    directory(ROOT / "secrets", mode=0o700)
    for label, owner in {
        "/opt/chatchat-1.0.0-SNAPSHOT/data": 10001,
        "/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/data": 10001,
        "/opt/chatchat-runtime-news-1.0.0-SNAPSHOT/data": 10001,
        "/opt/runtime-os-docker-stage/silo/data": 0,
        "/opt/runtime-os-docker-stage/mcp/data/python-user-data": 10001,
        "/opt/runtime-os-docker-stage/mcp/data/python-assets": 10001,
        "/data/postgresql/18/docker": 0,
        "/data/opensearch/data": 1000,
        "/data/opensearch/logs": 1000,
    }.items():
        directory(Path(label), owner)
    for role in ("api", "mcp", "news"):
        directory(ROOT / role, 10001)
        directory(ROOT.parent / role / "logs", 10001)
    for name in ("api_bootstrap_admin_password", "postgresql_admin_password", "postgresql_api_password",
                 "postgresql_mcp_password", "postgresql_news_password", "temporal_database_password",
                 "opensearch_admin_password", "redis_password", "silo_root_user", "silo_root_password",
                 "asset-access-key", "asset-secret-key"):
        create(ROOT / "secrets" / name, "Aa1_" + secrets.token_urlsafe(32), 0o444)
    seed_env(ROOT / "release.env", (TEMPLATES / "release.env").read_text().replace(
        "REPLACE_WITH_CUSTOMER_TOKEN", secrets.token_urlsafe(36)))
    internal_key = ROOT / "secrets" / "customer-internal-key"
    internal_proof = ROOT / "secrets" / "customer-internal-secret"
    create(internal_key, secrets.token_hex(32))
    create(internal_proof, secrets.token_hex(32))
    cipher_code = """const f=require('node:fs'),c=require('node:crypto');
const [key,secret]=JSON.parse(f.readFileSync(0,'utf8'));
const iv=c.randomBytes(12), enc=c.createCipheriv('aes-256-gcm',c.createHash('sha256').update(key).digest(),iv);
process.stdout.write('ENC('+iv.toString('base64')+':'+Buffer.concat([enc.update(secret),enc.final(),enc.getAuthTag()]).toString('base64')+')');"""
    envelope = run("docker", "run", "--rm", "-i", "--pull", "never", "--network", "none",
                   "--entrypoint", "node", BROKER, "-e", cipher_code, text=True, capture_output=True,
                   input=json.dumps([internal_key.read_text(), internal_proof.read_text()])).stdout
    if not re.fullmatch(r"ENC\([A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+\)", envelope):
        raise RuntimeError("Failed to initialize the internal credential envelope")
    for role in ("api", "mcp", "news"):
        create(ROOT / role / "internal-credential.key", internal_key.read_text(), 0o400, 10001)
        base = (TEMPLATES / (role + ".env")).read_text()
        seed_env(ROOT / (role + ".env"), base + "\nCHATCHAT_INTERNAL_CREDENTIAL_ENCRYPTED_SECRET="
               + envelope + "\nCHATCHAT_INTERNAL_CREDENTIAL_CRYPTO_KEY_FILE=/srv/app/config/internal-credential.key\n")
    create(ROOT / "cline-server.json", "{}\n")
    directory(ROOT / "opensearch-certs", 1000)
    certificates(ROOT / "opensearch-certs")
    print(f"Private customer configuration prepared under {ROOT}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build the full portable reinstall package from the locally built 1.3.3 application images."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
BASE = REPO / "runtime-os-1.3.2"
INSTALLER_BASE = "runtime-os-local-installer:1.3.2-supply-dd634c509"
STANDALONE = ROOT / "deploy/standalone"
run = runpy.run_path(str(ROOT / "scripts/build-runtime-os-incremental.py"))["run"]
sha256 = runpy.run_path(str(ROOT / "scripts/build-docker-release.py"))["sha256"]


def write(path, text):
    path.write_text(text, encoding="utf-8", newline="\n")


def json_write(path, value):
    write(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def host_id(reference):
    value = run("docker", "image", "inspect", reference, "--format", "{{.Os}}/{{.Architecture}} {{.Id}}", capture=True).split()
    if value[0] != "linux/amd64":
        raise RuntimeError("Unsupported image platform: " + reference)
    return value[1]


def export(destination, references):
    refs = list(dict.fromkeys(references))
    host_ids = {reference: host_id(reference) for reference in refs}
    print("Exporting", destination.name, flush=True)
    run("docker", "image", "save", "-o", destination, *refs)
    configs = {}
    with tarfile.open(destination, "r:") as archive:
        for manifest in json.load(archive.extractfile("manifest.json")):
            config_id = "sha256:" + hashlib.sha256(archive.extractfile(manifest["Config"]).read()).hexdigest()
            for reference in manifest["RepoTags"]:
                configs[reference] = config_id
    if set(configs) != set(refs):
        raise RuntimeError("Docker archive image list differs from requested tags")
    return {ref: {"reference": ref, "imageId": configs[ref],
                  "acceptedImageIds": [] if host_ids[ref] == configs[ref] else [host_ids[ref]]} for ref in refs}


def assemble(output, work):
    revision = run("git", "rev-parse", "HEAD", capture=True).strip()
    tag = "1.3.3-" + revision[:9]
    source_manifest = REPO / "deployment-artifacts" / ("runtime-os-1.3.3-update-" + revision[:9]) / "offline-images/BUILD.json"
    release = json.loads(source_manifest.read_text(encoding="utf-8"))
    if release["sourceCommit"] != revision:
        raise RuntimeError("Compiled application image manifest belongs to a different source revision")
    for entry in release["images"].values():
        if host_id(entry["reference"]) != entry["id"]:
            raise RuntimeError("Compiled application image ID mismatch " + entry["reference"])
    references = json.loads(run("docker", "run", "--rm", "--pull=never", "--network", "none", "--entrypoint", "cat",
                                INSTALLER_BASE, "/opt/product/images.json", capture=True))
    offline = output / "offline-images"
    config = output / "install-config"
    offline.mkdir(parents=True, exist_ok=True)
    config.mkdir(parents=True, exist_ok=True)
    native = work / "native"
    native.mkdir(parents=True, exist_ok=True)
    native_sources = ("governance-server.mjs", "runtime-model.mjs")
    for name in native_sources:
        shutil.copyfile(ROOT / "cline-agent-server/src" / name, native / name)
    shutil.copyfile(ROOT / "cline-agent-server/scripts/configure-worker-shell.sh", native / "configure-worker-shell.sh")
    native_ref = "runtime-os-reinstall-native:" + tag
    write(native / "Dockerfile", f"""FROM {release['images']['native']['reference']}
COPY governance-server.mjs runtime-model.mjs /opt/runtime-os/coding-node/service/src/
COPY configure-worker-shell.sh /opt/runtime-os/coding-node/service/scripts/
USER root
RUN /bin/sh /opt/runtime-os/coding-node/service/scripts/configure-worker-shell.sh
USER 10001:10001
RUN node --check /opt/runtime-os/coding-node/service/src/governance-server.mjs \\
    && node --check /opt/runtime-os/coding-node/service/src/runtime-model.mjs
""")
    run("docker", "build", "--pull=false", "--network=none", "--platform=linux/amd64", "-t", native_ref, native)
    roles = {role: entry["reference"] for role, entry in references.items()}
    for role in ("api", "mcp", "news"):
        roles[role] = release["images"][role]["reference"]
    for role in ("node", "executor", "recovery", "studio", "task"):
        roles[role] = native_ref
    main_archive = offline / "runtime-os-local-images.tar"
    images = export(main_archive, roles.values())
    manifest = {role: images[ref] for role, ref in roles.items()}
    installer = work / "installer"
    installer.mkdir(parents=True, exist_ok=True)
    json_write(installer / "images.json", manifest)
    installer_ref = "runtime-os-reinstall-installer:" + tag
    shutil.copyfile(STANDALONE / "installer.py", installer / "installer.py")
    migration = ROOT / "database/migration/postgresql/V20261123_01__optional_template_supply.sql"
    text = "\n".join(line for line in migration.read_text(encoding="utf-8").splitlines()
                     if line.strip() not in ("BEGIN;", "COMMIT;"))
    write(installer / "mcp-fresh.sql", "\n" + text + "\n")
    write(installer / "Dockerfile", f"""FROM {INSTALLER_BASE}
COPY installer.py images.json /opt/product/
COPY mcp-fresh.sql /tmp/mcp-fresh.sql
RUN cat /tmp/mcp-fresh.sql >> /opt/ops-config/chatchat-mcp-extra-schema.sql \\
    && rm /tmp/mcp-fresh.sql \\
    && python3 -m py_compile /opt/product/installer.py /opt/ops-config/verify.py
""")
    run("docker", "build", "--pull=false", "--network=none", "--platform=linux/amd64", "-t", installer_ref, installer)
    installer_archive = offline / "runtime-os-local-installer.tar"
    images.update(export(installer_archive, [installer_ref]))
    write(offline / "images.tsv", "".join(ref + "\t" + " ".join([info["imageId"], *info["acceptedImageIds"]]) + "\n"
                                           for ref, info in images.items()))
    hashes = {path.name: sha256(path) for path in (main_archive, installer_archive)}
    write(offline / "SHA256SUMS", "".join(digest + "  " + name + "\n" for name, digest in hashes.items()))
    json_write(offline / "BUILD.json", {"version": tag, "sourceCommit": revision, "platform": "linux/amd64",
                                        "roleImages": manifest, "archiveSha256": hashes,
                                        "nativeSourceSha256": {name: sha256(native / name) for name in native_sources},
                                        "jarSha256": release["jarSha256"]})
    for filename in ("install.sh", "OPERATIONS.md"):
        write(config / filename, (BASE / "install-config" / filename).read_text(encoding="utf-8"))
    plugin = config / "docker-config/cli-plugins/docker-compose"
    plugin.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(BASE / "install-config/docker-config/cli-plugins/docker-compose", plugin)
    plugin.chmod(0o755)
    write(config / "manual-install.env", f"""ROOT=/opt/ros133
PROJECT=runtime-reinstall-133
HTTP_PORT=18110
MCP_PORT=18111
INSTALLER_IMAGE={installer_ref}
# DOCKER_BIN=/opt/docker/bin/docker
""")
    print(output, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    assemble(args.output.resolve(), args.work.resolve())

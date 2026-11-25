#!/usr/bin/env python3
"""Package precompiled Runtime OS JARs and current Native source in an offline increment."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
UPDATE = ROOT / "deploy/releases/1.3.3/incremental"
MIGRATION = ROOT / "database/migration/postgresql/V20261123_01__optional_template_supply.sql"
MODULES = {"api": "chatchat-api", "mcp": "chatchat-mcp-server", "news": "chatchat-runtime-news"}
PREFIXES = {"api": "BOOT-INF/classes/static/", "mcp": "BOOT-INF/classes/static/admin/"}


def run(*args, capture=False):
    return subprocess.run(tuple(map(str, args)), cwd=ROOT, check=True,
                          stdout=subprocess.PIPE if capture else None, text=True, encoding="utf-8").stdout


def inspect(reference):
    result = json.loads(run("docker", "image", "inspect", reference, capture=True))[0]
    if (result["Os"], result["Architecture"]) != ("linux", "amd64"):
        raise ValueError("镜像须为 Linux amd64 " + reference)
    return result


def build(args):
    release = args.output.resolve()
    work = args.work.resolve()
    revision = run("git", "rev-parse", "HEAD", capture=True).strip()
    summary = run("git", "status", "--porcelain", "--untracked-files=no", "--",
                  *MODULES.values(), "cline-agent-server", "coding-agent-node", "database", capture=True)
    if summary.strip():
        raise ValueError("本次增量包需要可定位的已提交应用源码")
    version = "1.3.3-" + revision[:9]
    role_bases = {role: "runtime-os-local-" + role + ":1.3.2" for role in MODULES}
    role_bases["native"] = args.native_base
    bases = {role: inspect(ref) for role, ref in role_bases.items()}
    pin = json.loads((ROOT / "cline-agent-server/upstream-pin.json").read_text(encoding="utf-8"))
    labels = bases["native"]["Config"]["Labels"]
    if (labels.get("runtime-os.cline.source"), labels.get("runtime-os.cline.version")) != (pin["commit"], pin["tag"][1:]):
        raise ValueError("预置 Native ABI 与当前源码的 Cline SDK 锁定版本不同")
    helpers = runpy.run_path(str(ROOT / "scripts/build-docker-release.py"))
    sha256, verify_jar = helpers["sha256"], helpers["verify_jar"]
    images, jars = {}, {}
    for role, base in role_bases.items():
        stage = work / role
        stage.mkdir(parents=True, exist_ok=True)
        if role in MODULES:
            module = MODULES[role]
            jar = ROOT / module / "target" / (module + "-1.0.0-SNAPSHOT.jar")
            verify_jar(jar, PREFIXES.get(role))
            jars[role] = sha256(jar)
            shutil.copyfile(jar, stage / "app.jar")
            dockerfile = f"FROM {base}\nCOPY --chown=10001:10001 app.jar /srv/app/app.jar\n"
        else:
            for source, target in (
                ("cline-agent-server/src", "cline-src"), ("cline-agent-server/scripts", "cline-scripts"),
                ("coding-agent-node/src", "coding-src"), ("coding-agent-node/skills", "skills"),
            ):
                shutil.copytree(ROOT / source, stage / target, dirs_exist_ok=True,
                                ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            dockerfile = f"""FROM {base}
COPY cline-src/ /opt/runtime-os/coding-node/service/src/
COPY cline-scripts/ /opt/runtime-os/coding-node/service/scripts/
COPY coding-src/ /opt/runtime-os/coding-node/coding-agent-node/src/
COPY skills/ /opt/runtime-os/coding-node/coding-agent-node/skills/
RUN find /opt/runtime-os/coding-node/service/src -name '*.mjs' -print0 | xargs -0 -r -n 1 node --check \\
    && node /opt/runtime-os/coding-node/service/scripts/verify-runtime.mjs /opt/runtime-os/cline/runtime \\
    && node /opt/runtime-os/coding-node/service/docker/verify-native-modules.mjs /opt/runtime-os/cline/runtime
"""
        (stage / "Dockerfile").write_text(dockerfile, encoding="utf-8", newline="\n")
        ref = "runtime-os-update-" + role + ":" + version
        run("docker", "build", "--pull=false", "--network=none", "--platform=linux/amd64",
            "--label", "org.opencontainers.image.revision=" + revision,
            "--label", "org.opencontainers.image.version=" + version, "-t", ref, stage)
        images[role] = {"reference": ref, "id": inspect(ref)["Id"], "base": base, "baseId": bases[role]["Id"]}
    offline = release / "offline-images"
    config = release / "update-config"
    offline.mkdir(parents=True, exist_ok=True)
    config.mkdir(parents=True, exist_ok=True)
    archive = offline / "runtime-os-update.tar.gz"
    with gzip.open(archive, "wb", compresslevel=3) as compressed:
        with subprocess.Popen(["docker", "image", "save", *(image["reference"] for image in images.values())],
                              stdout=subprocess.PIPE) as exporter:
            shutil.copyfileobj(exporter.stdout, compressed, 4 * 1024 * 1024)
            if exporter.wait() != 0:
                raise RuntimeError("Docker 镜像导出失败")
    archive_hash = sha256(archive)
    migration = config / "migrate-postgresql.sql"
    migration.write_text(MIGRATION.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    for name in ("update.py", "update.sh"):
        (config / name).write_text((UPDATE / name).read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    cli = run("docker", "create", "--pull=never", "docker:27.5.1-cli", capture=True).strip()
    try:
        run("docker", "cp", cli + ":/usr/local/libexec/docker/cli-plugins/docker-compose", config / "docker-compose")
    finally:
        run("docker", "rm", cli)
    (config / "docker-compose").chmod(0o755)
    shutil.copyfile(UPDATE / "README.md", release / "README.md")
    manifest = {"version": version, "sourceCommit": revision, "platform": "linux/amd64",
                "cline": {"version": pin["tag"], "commit": pin["commit"]}, "images": images,
                "jarSha256": jars, "archiveSha256": archive_hash, "migrationSha256": sha256(migration)}
    (offline / "BUILD.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (offline / "SHA256SUMS").write_text(archive_hash + "  " + archive.name + "\n", encoding="ascii", newline="\n")
    (offline / "images.tsv").write_text("".join(item["reference"] + "\t" + item["id"] + "\n"
                                                for item in images.values()), encoding="ascii", newline="\n")
    print(release, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--native-base", required=True)
    build(parser.parse_args())

#!/usr/bin/env python3
"""Install the offline Native model and startup recovery fix."""
import argparse
import fcntl
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile

from model_fix import DOCKER, GOVERNANCE, compose_command, replace, run
from executor_fix import ensure_worker_images

HERE = Path(__file__).resolve().parent


def make_api_image(base, target):
    with tempfile.TemporaryDirectory(prefix="native-recovery-api-") as staging:
        staging = Path(staging)
        cid = run(DOCKER, "create", "--pull=never", base, capture_output=True, text=True).stdout.strip()
        try:
            run(DOCKER, "cp", cid + ":/srv/app/app.jar", staging / "base.jar")
        finally:
            run(DOCKER, "rm", cid)
        run(sys.executable, HERE / "jar-delta.py", "apply", staging / "base.jar",
            HERE / "api.patch.zip", staging / "app.jar")
        # The base archive is needed for verification only.
        (staging / "base.jar").unlink()
        shutil.copyfile(HERE / "api.Dockerfile", staging / "Dockerfile")
        run(DOCKER, "build", "--pull=false", "--network=none", "--build-arg", "BASE_IMAGE=" + base,
            "-t", target, staging)


def apply(root, check, worker_source=None, worker_archive=None):
    path = root / "compose.json"
    if path.is_symlink() or not path.is_file():
        raise RuntimeError("安装目录需要有 compose.json")
    original = path.read_bytes()
    config = json.loads(original)
    services = config["services"]
    project = config.get("name", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", project) or "api" not in services:
        raise RuntimeError("Compose 项目或 API 服务无效")
    prefix = (*compose_command(), "-p", project, "--project-directory", root)
    attached = [name for name, service in services.items() if service.get("network_mode") == "service:api"]
    nodes = [name for name, service in services.items() if GOVERNANCE in (service.get("entrypoint") or [])]
    if not nodes:
        raise RuntimeError("此 Compose 项目没有 Native 模型控制服务")
    base = services["api"]["image"]
    workers = [name for name in attached if services[name].get("image") == base and name.startswith("worker")]
    run(*prefix, "-f", path, "config", "--quiet")
    executors = ensure_worker_images(root, services, check, worker_source, worker_archive)
    with ZipFile(HERE / "api.patch.zip") as patch:
        target = "runtime-os-offline-recovery-api:" + json.loads(patch.read("recipe.json"))["targetSha256"][:16]
    api_roles = ["api", *workers] if base != target else []
    gateway = ["gateway"] if api_roles and "gateway" in services else []
    dependents = list(dict.fromkeys(attached + nodes + executors if api_roles else executors + nodes))
    print("计划重建 " + ", ".join(api_roles + [name for name in dependents if name not in api_roles] + gateway), flush=True)
    if check:
        return
    if base != target:
        make_api_image(base, target)
    for name in api_roles:
        services[name]["image"] = target
    native_images = {}
    payload_id = sha256(b"".join((HERE / name).read_bytes() for name in (
        "Dockerfile", "governance-server.mjs", "runtime-model.mjs", "worker-executor.mjs", "worker-executor-client.mjs"
    ))).hexdigest()[:12]
    for name in nodes + executors:
        ref = services[name]["image"]
        if ref.startswith("runtime-os-executor-allocation-fix:") and ref.endswith("-" + payload_id):
            continue
        if ref not in native_images:
            image_id = run(DOCKER, "image", "inspect", "--format", "{{.Id}}", ref,
                           capture_output=True, text=True).stdout.strip()
            new = "runtime-os-executor-allocation-fix:" + image_id.split(":")[-1][:12] + "-" + payload_id
            run(DOCKER, "build", "--pull=false", "--network=none", "--build-arg", "BASE_IMAGE=" + ref, "-t", new, HERE)
            native_images[ref] = new
        services[name]["image"] = native_images[ref]
    if config == json.loads(original):
        print("离线执行器补丁已经应用，固定 Worker 镜像已校验。")
        return
    candidate = (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode()
    with tempfile.NamedTemporaryFile(prefix=".offline-recovery-", suffix=".json", dir=root) as draft:
        draft.write(candidate)
        draft.flush()
        run(*prefix, "-f", draft.name, "config", "--quiet")
    if path.read_bytes() != original:
        raise RuntimeError("Compose 配置发生变化，请重新执行。")
    fd, backup = tempfile.mkstemp(prefix="compose.before-offline-recovery-", suffix=".json", dir=root)
    with os.fdopen(fd, "wb") as stream:
        stream.write(original)

    def stop():
        run(*prefix, "-f", path, "stop", "-t", "30", *gateway, *dependents)

    def start(*names):
        if names:
            run(*prefix, "-f", path, "up", "-d", "--no-deps", "--no-build", "--pull", "never",
                "--force-recreate", "--wait", "--wait-timeout", "180", *names)

    try:
        stop()
        replace(path, candidate)
        if api_roles:
            start("api")
        start(*dependents)
        start(*gateway)
    except (Exception, KeyboardInterrupt):
        replace(path, original)
        stop()
        if api_roles:
            start("api")
        start(*dependents)
        start(*gateway)
        raise
    print("离线修复已应用。原配置备份 " + backup)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--worker-source", help="local tag of the original Worker image")
    parser.add_argument("--worker-archive", type=Path, help="Docker archive containing only the original Worker image")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        root = args.root.resolve(strict=True)
        with (root / ".runtime-native-model-fix.lock").open("a") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            apply(root, args.check, args.worker_source, args.worker_archive)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None

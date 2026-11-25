#!/usr/bin/env python3
"""Install the offline Native model binding fix in an existing Runtime OS Compose project."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
DOCKER = os.environ.get("DOCKER_BIN") or "docker"
GOVERNANCE = "/opt/runtime-os/coding-node/service/src/governance-server.mjs"


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def compose_command():
    bundled = HERE / "docker-compose"
    for command in ((DOCKER, "compose"), ("docker-compose",), (bundled,)):
        try:
            result = run(*command, "version", "--short", capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.SubprocessError):
            continue
        version = re.match(r"v?(\d+)\.", result.stdout.strip())
        if version and int(version[1]) >= 2:
            return command
    raise RuntimeError("找不到 Docker Compose v2，请检查包内 docker-compose 是否可执行")


def replace(path, content):
    fd, draft = tempfile.mkstemp(prefix=".compose-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(draft, path)
    finally:
        if os.path.exists(draft):
            os.unlink(draft)


def apply(root, check_only):
    path = root / "compose.json"
    if not path.is_file() or path.is_symlink():
        raise RuntimeError("请选择 Runtime OS 安装目录，其中应有 compose.json")
    original = path.read_bytes()
    config = json.loads(original)
    project = config.get("name", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", project):
        raise RuntimeError("Compose 项目名无效")
    prefix = (*compose_command(), "-p", project, "--project-directory", root)
    services = {
        name: service for name, service in config["services"].items()
        if GOVERNANCE in (service.get("entrypoint") or [])
    }
    if not services:
        raise RuntimeError("未找到由此 Compose 管理的 Native 模型控制服务")
    run(*prefix, "-f", path, "config", "--quiet")
    changed = [name for name, service in services.items()
               if not service["image"].startswith("runtime-os-native-model-fix:")]
    if not changed:
        print("Native 模型绑定补丁已经应用。")
        return
    print("Native 控制服务 " + ", ".join(changed), flush=True)
    if check_only:
        return
    refs = {}
    patch = hashlib.sha256(b"".join((HERE / name).read_bytes() for name in
                                   ("Dockerfile", "governance-server.mjs", "runtime-model.mjs"))).hexdigest()[:12]
    for name in changed:
        base = services[name]["image"]
        if base not in refs:
            image = run(DOCKER, "image", "inspect", "--format", "{{.Id}}", base,
                        capture_output=True, text=True).stdout.strip()
            target = "runtime-os-native-model-fix:" + image.rsplit(":", 1)[-1][:12] + "-" + patch
            run(DOCKER, "build", "--pull=false", "--network=none", "--build-arg", "BASE_IMAGE=" + base,
                "--tag", target, HERE)
            refs[base] = target
        services[name]["image"] = refs[base]
    candidate = (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode()
    with tempfile.NamedTemporaryFile(prefix=".native-model-candidate-", suffix=".json", dir=root) as draft:
        draft.write(candidate)
        draft.flush()
        run(*prefix, "-f", draft.name, "config", "--quiet")
    if path.read_bytes() != original:
        raise RuntimeError("Compose 配置刚刚被其他程序修改，请重新执行。")
    fd, backup = tempfile.mkstemp(prefix="compose.before-native-model-fix-", suffix=".json", dir=root)
    with os.fdopen(fd, "wb") as stream:
        stream.write(original)

    def restart():
        run(*prefix, "-f", path, "up", "-d", "--no-deps", "--no-build", "--pull", "never",
            "--wait", "--wait-timeout", "90", *changed)

    replace(path, candidate)
    try:
        restart()
    except (Exception, KeyboardInterrupt):
        replace(path, original)
        restart()
        raise
    print("模型绑定补丁已应用。原配置备份 " + backup)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        root = args.root.resolve(strict=True)
        with (root / ".runtime-native-model-fix.lock").open("a") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            apply(root, args.check)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None

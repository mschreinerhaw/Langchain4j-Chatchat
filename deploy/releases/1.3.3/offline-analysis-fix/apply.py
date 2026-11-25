#!/usr/bin/env python3
"""Apply the API, MCP and isolated Native Worker patches to a supported installation."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time

from model_fix import DOCKER, compose_command, replace, run

HERE = Path(__file__).resolve().parent
NODE_SCRIPTS = ("governance-server.mjs", "recovery-server.mjs", "worker-executor.mjs")
NATIVE_CONFIG_KEYS = ("CLINE_EXECUTOR_CONFIG", "CLINE_GOVERNANCE_CONFIG", "CLINE_BUSINESS_CONFIG")
PROBE = (
    'test "$(id -u):$(id -g)" = 10001:10001 && true '
    '&& exec /usr/local/bin/python -c "import openpyxl"'
)
ORIGINAL_WORKER_ID = "sha256:5d6e19b5d1b70a328f95fc65769176b30eb45283f7842b6fce43ee0a7e4e7ef3"
ORIGINAL_WORKER_CONFIG_ID = "sha256:14fd1c48edaf477985ac63aaddc292c28efe99469c2bf97fd71f14b19c4c718b"


def output(*args):
    return run(*args, capture_output=True, text=True).stdout.strip()


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def stage_app(role, base, recipes, check):
    with tempfile.TemporaryDirectory(prefix="runtime-analysis-") as folder:
        folder = Path(folder)
        container = output(DOCKER, "create", "--pull=never", base)
        try:
            run(DOCKER, "cp", container + ":/srv/app/app.jar", folder / "base.jar")
        finally:
            run(DOCKER, "rm", container)
        baseline = digest(folder / "base.jar")
        if any(recipe["targetSha256"] == baseline for recipe in recipes):
            print(role + " 已更新", flush=True)
            return base
        recipe = next((item for item in recipes if item["baseSha256"] == baseline), None)
        if recipe is None:
            raise RuntimeError(role + " 基础 JAR 校验值不在本包支持范围内: " + baseline)
        target = "runtime-os-offline-analysis-" + role + ":" + recipe["targetSha256"][:16]
        print(role + " 更新为 " + target, flush=True)
        if not check:
            run(sys.executable, HERE / "jar-delta.py", "apply", folder / "base.jar",
                HERE / recipe["file"], folder / "app.jar")
            (folder / "base.jar").unlink()
            shutil.copyfile(HERE / "app.Dockerfile", folder / "Dockerfile")
            run(DOCKER, "build", "--pull=false", "--network=none", "--build-arg",
                "BASE_IMAGE=" + base, "-t", target, folder)
        return target


def worker_probe(docker, image):
    return subprocess.run([*docker, "run", "--rm", "--pull=never", "--network=none",
                           "--user", "0:0", "--entrypoint", "/usr/bin/su", image,
                           "--command", PROBE, "runner"], timeout=60).returncode == 0


def stage_worker(docker, image, check):
    if worker_probe(docker, image):
        print("Worker Shell / XLSX 依赖检查通过: " + image, flush=True)
        return image
    print("需要修复 Worker Shell: " + image, flush=True)
    if check:
        return image
    # The local tag resolves the exact original image on this dedicated daemon.
    base = "runtime-os-worker-shell-base:" + image.split(":")[-1]
    target = "runtime-os-worker-shell-fixed:" + image.split(":")[-1]
    run(*docker, "image", "tag", image, base)
    run(*docker, "build", "--pull=false", "--network=none", "--build-arg",
        "BASE_IMAGE=" + base, "-f", HERE / "Dockerfile.native-worker-shell", "-t", target, HERE)
    new = output(*docker, "image", "inspect", "--format", "{{.Id}}", target)
    if not worker_probe(docker, new):
        raise RuntimeError("新 Worker 未通过实际 runner Shell 探针")
    return new


def resolve_worker(docker, image):
    # Same published OCI/config identity pair accepted by the offline r2 Executor.
    identities = (image, ORIGINAL_WORKER_CONFIG_ID) if image == ORIGINAL_WORKER_ID else (image,)
    for candidate in identities:
        result = subprocess.run([*docker, "image", "inspect", "--format", "{{.Id}}", candidate],
                                capture_output=True, text=True)
        if result.returncode == 0 and result.stdout.strip() in identities:
            return result.stdout.strip()
    raise RuntimeError("专用 Docker 中找不到固定 Worker 镜像: " + image)


def require_idle(docker):
    if output(*docker, "ps", "--filter", "label=cline.executor", "--format", "{{.ID}}"):
        raise RuntimeError("仍有 Native Worker 在执行，请结束在途任务后重试")


def worker_updates(root, services, check):
    anchor = root / "d/strict"
    sock = anchor / "run/docker.sock"
    if not sock.is_socket() or sock.resolve() != sock:
        raise RuntimeError("找不到独立执行 Docker socket: " + str(sock))
    for path in (anchor, anchor / "run", anchor / "data", sock):
        identity = path.lstat()
        if stat.S_ISLNK(identity.st_mode) or identity.st_uid != 0 or identity.st_mode & 0o077:
            raise RuntimeError("专用执行 Docker 路径权限异常: " + str(path))
    docker = (DOCKER, "--host", "unix://" + str(sock))
    daemon = json.loads(output(*docker, "info", "--format", "{{json .}}"))
    if not daemon.get("ID") or daemon["ID"] == output(DOCKER, "info", "--format", "{{.ID}}") or (
            daemon.get("DockerRootDir") != str(anchor / "data")):
        raise RuntimeError("专用执行 Docker 身份与标准安装目录不一致")
    configs = {}
    executors = {}
    for name, service in services.items():
        env = service.get("environment", {})
        for key in NATIVE_CONFIG_KEYS:
            if key not in env:
                continue
            path = Path(env[key])
            if path.resolve(strict=True) != path or root / "n" not in path.parents:
                raise RuntimeError("Native 配置不在预期目录: " + name)
            original = path.read_bytes()
            data = json.loads(original)
            image = data.get("workerImage")
            if not re.fullmatch(r"sha256:[a-f0-9]{64}", image or ""):
                raise RuntimeError("Native Worker 必须以镜像 ID 固定: " + name)
            configs[path] = (original, data, image)
            if key == "CLINE_EXECUTOR_CONFIG":
                if (data.get("expectedDaemonId") != daemon.get("ID")
                        or data.get("dockerSocket") != str(sock)
                        or data.get("dedicatedDaemonRoot") != str(anchor)):
                    raise RuntimeError("执行器的 Docker 绑定与配置不一致: " + name)
                executors[name] = image
    if not executors:
        raise RuntimeError("Compose 中未找到标准 Native 执行器")
    require_idle(docker)
    fixed = {}
    for image in dict.fromkeys(executors.values()):
        actual = resolve_worker(docker, image)
        candidate = stage_worker(docker, actual, check)
        fixed[image] = image if candidate == actual else candidate
    updates = {}
    for path, (original, data, image) in configs.items():
        if image in fixed and image != fixed[image]:
            data["workerImage"] = fixed[image]
            updates[path] = (original, encoded(data))
    return updates, docker


def apply(root, check, worker_only):
    path = root / "compose.json"
    if path.resolve(strict=True) != path or not path.is_file():
        raise RuntimeError("请选择包含 compose.json 的安装目录")
    original = path.read_bytes()
    config = json.loads(original)
    services = config["services"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", config.get("name", "")) or not {"api", "mcp"} <= services.keys():
        raise RuntimeError("Compose 项目或 API/MCP 服务无效")
    prefix = (*compose_command(), "-p", config["name"], "--project-directory", root)
    compose = (*prefix, "-f", path)
    run(*compose, "config", "--quiet")
    recipes = json.loads((HERE / "BUILD.json").read_text(encoding="utf-8"))["jars"]
    targets = {role: services[role]["image"] for role in ("api", "mcp")}
    if not worker_only:
        targets = {role: stage_app(role, base, recipes[role], check) for role, base in targets.items()}
    auxiliary_api = [name for name, service in services.items()
                     if name.startswith("worker") and service.get("network_mode") == "service:api"
                     and service.get("image") == services["api"]["image"]]
    worker_changes, worker_docker = worker_updates(root, services, check)
    attached = [name for name, service in services.items() if service.get("network_mode") == "service:api"]
    native = [name for name, service in services.items()
              if any(Path(arg).name in NODE_SCRIPTS for arg in service.get("entrypoint") or [])]
    changed_configs = {str(file) for file in worker_changes}
    native.extend(name for name, service in services.items()
                  if any(service.get("environment", {}).get(key) in changed_configs
                         for key in NATIVE_CONFIG_KEYS))
    primary = [role for role in ("mcp", "api") if targets[role] != services[role]["image"]]
    secondary = list(dict.fromkeys((attached if "api" in primary else []) + (native if worker_changes else [])))
    gateway = ["gateway"] if primary and "gateway" in services else []
    print("API/MCP 及关联服务: " + ", ".join(primary + secondary + gateway), flush=True)
    if check or not (primary or worker_changes):
        return
    for role in primary:
        services[role]["image"] = targets[role]
    if "api" in primary:
        for role in auxiliary_api:
            services[role]["image"] = targets["api"]
    changes = {path: (original, encoded(config)), **worker_changes}
    with tempfile.NamedTemporaryFile(prefix=".analysis-fix-", suffix=".json", dir=root) as draft:
        draft.write(changes[path][1])
        draft.flush()
        run(*prefix, "-f", draft.name, "config", "--quiet")
    backup = Path(tempfile.mkdtemp(prefix=".before-offline-analysis-", dir=root))
    for file, (prior, _) in changes.items():
        if file.read_bytes() != prior:
            raise RuntimeError("配置在预检后发生变化: " + str(file))
        dest = backup / file.relative_to(root)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(prior)
    print("配置备份: " + str(backup), flush=True)

    def start(names):
        if names:
            run(*compose, "up", "-d", "--no-deps", "--no-build", "--pull", "never",
                "--force-recreate", "--wait", "--wait-timeout", "240", *names)

    def stop():
        run(*compose, "stop", "-t", "30", *gateway, *secondary, *reversed(primary))

    def start_all():
        for role in primary:
            start([role])
        start(secondary)
        start(gateway)

    def running_set():
        result = {}
        for name in primary + secondary + gateway:
            cid = output(*compose, "ps", "-q", name)
            if not cid:
                raise RuntimeError("服务未运行: " + name)
            state = json.loads(output(DOCKER, "inspect", cid))[0]
            health = state["State"].get("Health", {}).get("Status")
            if state["RestartCount"] or not state["State"]["Running"] or health not in (None, "healthy"):
                raise RuntimeError("服务没有稳定启动: " + name)
            result[name] = (cid, state["State"]["StartedAt"])
        return result

    try:
        stop()
        require_idle(worker_docker)
        for file, (_, candidate) in changes.items():
            replace(file, candidate)
        start_all()
        running = running_set()
        time.sleep(30)
        if running != running_set():
            raise RuntimeError("服务在就绪观察期间重启")
    except (Exception, KeyboardInterrupt):
        for file, (prior, _) in changes.items():
            replace(file, prior)
        stop()
        start_all()
        raise
    print("离线补丁已应用并通过服务就绪校验。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--worker-only", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        root = args.root.resolve(strict=True)
        with (root / ".runtime-native-model-fix.lock").open("a") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            apply(root, args.check, args.worker_only)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None

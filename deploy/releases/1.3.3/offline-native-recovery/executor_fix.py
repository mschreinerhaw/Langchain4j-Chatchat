"""Restore only the installed, digest-pinned Worker image into its private daemon."""
from hashlib import sha256
import json
from pathlib import Path
import re
import stat
import subprocess
import tarfile
import tempfile

from model_fix import DOCKER, run


EXECUTOR = "/opt/runtime-os/coding-node/service/src/worker-executor.mjs"
RELEASE_CONFIG_IDS = {
    "sha256:5d6e19b5d1b70a328f95fc65769176b30eb45283f7842b6fce43ee0a7e4e7ef3":
    "sha256:14fd1c48edaf477985ac63aaddc292c28efe99469c2bf97fd71f14b19c4c718b",
}


def archive_image_ids(archive):
    with tarfile.open(fileobj=archive, mode="r:") as saved:
        manifest = json.load(saved.extractfile("manifest.json"))
        return {"sha256:" + sha256(saved.extractfile(entry["Config"]).read()).hexdigest() for entry in manifest}


def image_id(reference, command=(DOCKER,)):
    try:
        return run(*command, "image", "inspect", "--format", "{{.Id}}", reference,
                   capture_output=True, text=True).stdout.strip()
    except subprocess.CalledProcessError:
        return None


def ensure_worker_images(root, services, check, worker_source=None, worker_archive=None):
    executors = [name for name, service in services.items() if EXECUTOR in (service.get("entrypoint") or [])]
    if not executors:
        raise RuntimeError("未找到 Native 执行器服务")
    anchor = root / "d/strict"
    socket = anchor / "run/docker.sock"
    if not socket.is_socket() or socket.resolve() != socket:
        raise RuntimeError("专用 Docker socket 不可用，请查看 executor-daemon 状态")
    for path in (anchor, anchor / "run", anchor / "data", socket):
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
            raise RuntimeError("专用 Docker 路径必须由 root 持有且权限私有: " + str(path))
    images = set()
    private = (DOCKER, "--host", "unix://" + str(socket))
    daemon = json.loads(run(*private, "info", "--format", "{{json .}}", capture_output=True, text=True).stdout)
    outer_id = run(DOCKER, "info", "--format", "{{.ID}}", capture_output=True, text=True).stdout.strip()
    if not daemon.get("ID") or daemon["ID"] == outer_id or daemon.get("DockerRootDir") != str(anchor / "data"):
        raise RuntimeError("专用 Docker 身份或数据目录与安装配置不符")
    for name in executors:
        env = services[name].get("environment", {})
        config_path = Path(env.get("CLINE_EXECUTOR_CONFIG", ""))
        if not config_path.is_absolute() or root / "n" not in config_path.resolve().parents:
            raise RuntimeError("执行器配置文件不在安装目录内: " + name)
        config = json.loads(config_path.read_text(encoding="utf-8"))
        worker = config.get("workerImage", "")
        if (config.get("expectedDaemonId") != daemon["ID"] or config.get("dockerSocket") != str(socket)
                or config.get("dedicatedDaemonRoot") != str(anchor) or not re.fullmatch(r"sha256:[a-f0-9]{64}", worker)):
            raise RuntimeError("执行器的固定 Docker 身份或 Worker 镜像配置不符: " + name)
        images.add(worker)

    for worker in sorted(images):
        config_id = RELEASE_CONFIG_IDS.get(worker, worker)
        identities = (worker,) if config_id == worker else (worker, config_id)
        if any(image_id(identity, private) in identities for identity in identities):
            print("专用 Docker 已有固定 Worker 镜像 " + worker, flush=True)
            continue
        print("专用 Docker 缺少固定 Worker 镜像 " + worker, flush=True)
        references = [worker]
        if worker_source:
            references.append(worker_source)
        references.extend(services[name]["image"] for name in executors)
        references.extend(("runtime-os-update-native:1.3.3-f7c093a1c", "runtime-os-reinstall-native:1.3.3-f7c093a1c",
                           "runtime-os-local-native:1.3.2"))
        candidates = list(dict.fromkeys(filter(None, map(image_id, dict.fromkeys(references)))))
        def restore(saved):
            if archive_image_ids(saved) != {config_id}:
                return False
            if not check:
                print("离线导入固定 Worker 镜像", flush=True)
                saved.seek(0)
                run(*private, "image", "load", stdin=saved)
            return True

        restored = False
        if worker_archive:
            with worker_archive.open("rb") as saved:
                restored = restore(saved)
        for candidate in candidates:
            if restored:
                break
            with tempfile.TemporaryFile() as saved:
                run(DOCKER, "image", "save", candidate, stdout=saved)
                saved.seek(0)
                restored = restore(saved)
        if not restored:
            raise RuntimeError("宿主 Docker 未找到与固定摘要一致的镜像，请从原离线安装介质导入镜像后重跑。")
        if check:
            print("已找到可离线导入的原 Worker 镜像", flush=True)
            continue
        if not any(image_id(identity, private) in identities for identity in identities):
            raise RuntimeError("导入后的 Worker 镜像标识不符")
        print("固定 Worker 镜像已同步并通过校验", flush=True)
    return executors

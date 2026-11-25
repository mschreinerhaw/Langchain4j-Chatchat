#!/usr/bin/env python3
"""Apply the offline Runtime OS PostgreSQL update to a supported Compose installation."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

PACKAGE = Path(__file__).resolve().parents[1]
PORTABLE = {
    "api": ("api", "worker1", "worker2"), "mcp": ("mcp",), "news": ("news",),
    "native": ("node1", "node2", "recovery1", "recovery2", "executor1", "executor2", "studio", "studio-executor"),
}
SITE = {
    "api": ("api",), "mcp": ("mcp",), "news": ("news",),
    "native": ("native-node", "native-recovery", "native-executor"),
}
SITE_BASE = {
    "api": ("chatchat-api:acceptance-5d96d85e41e1",),
    "mcp": ("chatchat-mcp-server:acceptance-5d96d85e41e1",),
    "news": ("chatchat-runtime-news:news-temporal-schedule-197695e345e1",),
    "native": ("runtime-os-native-worker:report-skills-f7c093a1c3b0", "runtime-os-native-worker:1.3.2-source-20261120-r1"),
}
PROBES = {
    "api": (8080, "/api/v1/enterprise/auth/me", (401, 403)),
    "mcp": (8090, "/api/v1/admin/auth/me", (401, 403)),
    "news": (8091, "/internal/v1/news/health", (200, 401, 403)),
}


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def run_command(*args, capture=False, check=True, **kwargs):
    return subprocess.run(list(map(str, args)), check=check,
                          stdout=subprocess.PIPE if capture else kwargs.pop("stdout", None), **kwargs)


def docker(*args, **kwargs):
    return run_command(os.environ.get("DOCKER_BIN") or "docker", *args, **kwargs)


def compose_command():
    bundled = Path(__file__).with_name("docker-compose")
    for command in ((os.environ.get("DOCKER_BIN") or "docker", "compose"), ("docker-compose",), (bundled,)):
        try:
            if command == (bundled,) and bundled.is_file() and not os.access(bundled, os.X_OK):
                bundled.chmod(bundled.stat().st_mode | 0o100)
            result = run_command(*command, "version", "--short", capture=True, check=False,
                                 stderr=subprocess.DEVNULL, timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            continue
        version = re.match(rb"v?(\d+)\.", result.stdout.strip())
        if result.returncode == 0 and version and int(version[1]) >= 2:
            print("使用 Compose", " ".join(map(str, command)), flush=True)
            return command
    raise RuntimeError("未找到 Docker Compose v2 或更新版本，请使用内置 Compose 的更新包")


def output(*args):
    return docker(*args, capture=True).stdout.decode("utf-8").strip()


def image_id(reference):
    result = docker("image", "inspect", "--format", "{{.Id}}", reference, capture=True,
                    check=False, stderr=subprocess.DEVNULL)
    return result.stdout.decode("utf-8").strip() if result.returncode == 0 else None


def atomic_write(path, content, permissions):
    fd, draft = tempfile.mkstemp(prefix="." + path.name + "-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            if os.geteuid() == 0:
                os.fchown(stream.fileno(), permissions.st_uid, permissions.st_gid)
            os.fchmod(stream.fileno(), permissions.st_mode & 0o777)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(draft, path)
    finally:
        if os.path.exists(draft):
            os.unlink(draft)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def wait_for(check, name, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, subprocess.SubprocessError):
            pass
        time.sleep(2)
    raise RuntimeError("就绪检查失败 " + name)


class Update:
    def __init__(self, args):
        self.root = args.root.resolve()
        self.file = self.root / "compose.json"
        if self.file.is_symlink():
            raise RuntimeError("安装目录中的 compose.json 必须是普通文件")
        self.original = self.file.read_bytes()
        self.config = json.loads(self.original)
        self.permissions = self.file.stat()
        self.project = self.config.get("name", "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", self.project):
            raise RuntimeError("compose.json 缺少合法的项目名")
        self.services = self.config["services"]
        self.manifest = json.loads((PACKAGE / "offline-images/BUILD.json").read_text(encoding="utf-8"))
        self.images = self.manifest["images"]
        self.layout = PORTABLE if "postgresql" in self.services else SITE
        if self.layout is SITE and not args.postgres_container:
            raise RuntimeError("此实例使用外部 PostgreSQL，请明确传入 --postgres-container")
        required = {"gateway", *(service for group in self.layout.values() for service in group)}
        if not required.issubset(self.services):
            raise RuntimeError("实例缺少支持拓扑中的服务 " + ", ".join(sorted(required - self.services.keys())))
        self.pg = args.postgres_container
        self.native_and_workers = (*self.layout["native"], *self.layout["api"][1:])
        self.changed = [service for group in self.layout.values() for service in group]
        self.backups = self.root / ".runtime-updates"
        self.compose_command = compose_command()

    def compose(self, *args, file=None, **kwargs):
        return run_command(*self.compose_command, "-p", self.project, "--project-directory", self.root,
                           "-f", file or self.file, *args, **kwargs)

    def container(self, service):
        cid = self.compose("ps", "--all", "-q", service, capture=True).stdout.decode("utf-8").splitlines()
        if len(cid) != 1:
            raise RuntimeError("需要一个已安装的 Compose 容器 " + service)
        return cid[0]

    def database(self, command, **kwargs):
        cid = self.pg or self.container("postgresql")
        # Use the database container's provisioned local credentials without copying them to the host.
        shell = 'export PGPASSWORD="${POSTGRES_PASSWORD:-}" PGUSER="${POSTGRES_USER:-postgres}"; exec "$@"'
        return docker("exec", "-i", cid, "sh", "-ec", shell, "runtime-update", *command, **kwargs)

    def verify_database(self):
        query = "SELECT to_regclass('public.mcp_template_query_binding') IS NOT NULL;"
        result = self.database(("psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-d", "live_runtime_mcp", "-c", query),
                               capture=True, timeout=20)
        if result.stdout.strip() != b"t":
            raise RuntimeError("MCP 数据库中缺少基线表")

    def plan(self):
        self.compose("config", "--quiet")
        self.verify_database()
        active = {}
        for component, names in self.layout.items():
            image = self.images[component]
            if image_id(image["reference"]) not in (None, image["id"]):
                raise RuntimeError("版本标签冲突 " + image["reference"])
            for service in names:
                ref = self.services[service]["image"]
                allowed = ("runtime-os-local-" + component + ":1.3.2",) if self.layout is PORTABLE else SITE_BASE[component]
                if ref not in (*allowed, image["reference"]):
                    raise RuntimeError("不支持的基线镜像 " + service + " " + ref)
                cid = self.container(service)
                actual, running = output("inspect", "--format", "{{.Image}} {{.State.Running}}", cid).split()
                if running != "true" or image_id(ref) != actual:
                    raise RuntimeError("服务未运行或当前容器镜像与 Compose 不一致 " + service)
                active[service] = {"reference": ref, "id": actual, "target": image["reference"], "targetId": image["id"]}
        gateway = self.container("gateway")
        if output("inspect", "--format", "{{.State.Running}}", gateway) != "true":
            raise RuntimeError("网关容器当前未运行")
        return active

    def load(self):
        bundle = PACKAGE / "offline-images/runtime-os-update.tar.gz"
        migration = PACKAGE / "update-config/migrate-postgresql.sql"
        if digest(bundle) != self.manifest["archiveSha256"] or digest(migration) != self.manifest["migrationSha256"]:
            raise RuntimeError("归档或数据库迁移文件的 SHA-256 校验失败")
        docker("image", "load", "--input", bundle)
        for image in self.images.values():
            actual = output("image", "inspect", "--format", "{{.Id}} {{.Os}}/{{.Architecture}}", image["reference"])
            if actual != image["id"] + " linux/amd64":
                raise RuntimeError("加载镜像的 ID 或架构与交付清单不符 " + image["reference"])

    def verify_service(self, name, port, path, allowed):
        script = """exec 3<>/dev/tcp/127.0.0.1/"$1"
printf 'GET %s HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' "$2" >&3
read -r protocol status rest <&3
printf '%s' "$status"
"""
        cid = self.container(name)

        def probe():
            result = docker("exec", cid, "bash", "-ec", script, "runtime-probe", port, path, timeout=5,
                            capture=True, check=False, stderr=subprocess.DEVNULL)
            return result.returncode == 0 and result.stdout.decode().strip() in tuple(map(str, allowed))
        wait_for(probe, name)

    def verify_gateway(self):
        cid = self.container("gateway")
        script = """url=http://127.0.0.1:8080/api/v1/enterprise/auth/me
if command -v curl >/dev/null; then
    status=$(curl -sS -o /dev/null -w '%{http_code}' "$url")
else
    status=$(wget -S -O /dev/null "$url" 2>&1 | awk '/HTTP\\// { print $2; exit }')
fi
test "$status" = 401 || test "$status" = 403
"""

        def probe():
            return docker("exec", cid, "sh", "-ec", script, timeout=5, check=False,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        wait_for(probe, "gateway")

    def stop_entrypoints(self):
        self.compose("stop", "-t", "30", "gateway", *self.native_and_workers)

    def restart(self, expected):
        def up(*services):
            self.compose("up", "-d", "--no-deps", "--no-build", "--force-recreate", "--pull", "never", *services)
            containers = {service: self.container(service) for service in services}

            def running_and_ready():
                states = output("inspect", "--format", "{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{end}}",
                                *containers.values()).splitlines()
                return len(states) == len(containers) and all(state.strip() in ("true", "true healthy") for state in states)
            wait_for(running_and_ready, ", ".join(services), seconds=240)

        up("api", "mcp", "news")
        for name, (port, path, allowed) in PROBES.items():
            self.verify_service(name, port, path, allowed)
        up(*self.native_and_workers)
        up("gateway")
        self.verify_gateway()
        for name, expected_id in expected.items():
            cid = self.container(name)
            if output("inspect", "--format", "{{.Image}} {{.State.Running}}", cid) != expected_id + " true":
                raise RuntimeError("运行镜像与计划不符 " + name)

    def apply(self):
        active = self.plan()
        if all(item["id"] == item["targetId"] for item in active.values()):
            print("当前应用镜像已是此版本。")
            return
        self.load()
        candidate = json.loads(self.original)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ").lower()
        backup = self.backups / stamp
        backup.mkdir(mode=0o700)
        (backup / "compose.json").write_bytes(self.original)
        for name, item in active.items():
            item["rollback"] = "runtime-os-update-rollback/" + self.project + "/" + name + ":" + stamp
            docker("tag", item["id"], item["rollback"])
            candidate["services"][name]["image"] = item["target"]
        (backup / "state.json").write_bytes(json_bytes({"project": self.project, "images": active}))
        staged = backup / "candidate.json"
        staged.write_bytes(json_bytes(candidate))
        self.compose("config", "--quiet", file=staged)
        with (backup / "mcp-before-update.dump").open("wb") as stream:
            self.database(("pg_dump", "-Fc", "-d", "live_runtime_mcp"), stdout=stream, timeout=900)
        if self.file.read_bytes() != self.original:
            raise RuntimeError("Compose 配置自预检后发生变化，请重新执行更新")
        print("回滚记录", backup, flush=True)
        (backup / "transition").touch()
        try:
            self.stop_entrypoints()
            with (PACKAGE / "update-config/migrate-postgresql.sql").open("rb") as stream:
                self.database(("psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", "live_runtime_mcp"), stdin=stream, timeout=60)
            atomic_write(self.file, staged.read_bytes(), self.permissions)
            self.restart({name: item["targetId"] for name, item in active.items()})
        except (Exception, KeyboardInterrupt):
            print("更新失败，正在恢复原应用镜像和配置。", flush=True)
            self.rollback(backup)
            raise
        (backup / "applied").touch()
        print("更新完成", self.manifest["version"], flush=True)

    def rollback(self, backup):
        backup = backup.resolve()
        if backup.parent != self.backups or not (backup / "transition").is_file():
            raise RuntimeError("回滚目录必须来自当前实例的 .runtime-updates")
        state = json.loads((backup / "state.json").read_bytes())
        if state["project"] != self.project or set(state["images"]) != set(self.changed):
            raise RuntimeError("回滚记录与当前实例不符")
        original = (backup / "compose.json").read_bytes()
        live = self.file.read_bytes()
        retain_live_config = live not in (original, (backup / "candidate.json").read_bytes())
        composition = json.loads(live if retain_live_config else original)
        live_services = json.loads(live)["services"]
        rewrite = retain_live_config
        for name, item in state["images"].items():
            current = live_services[name]["image"]
            if current not in (item["reference"], item["target"], item["rollback"]):
                raise RuntimeError("服务已升级到其他版本，拒绝覆盖 " + name)
            if image_id(item["rollback"]) != item["id"]:
                raise RuntimeError("回滚镜像摘要不符 " + name)
            previous_available = image_id(item["reference"]) == item["id"]
            composition["services"][name]["image"] = item["reference"] if previous_available else item["rollback"]
            rewrite |= not previous_available
        self.stop_entrypoints()
        atomic_write(self.file, json_bytes(composition) if rewrite else original, self.permissions)
        self.restart({name: item["id"] for name, item in state["images"].items()})
        (backup / "rolled-back").touch()
        print("应用已回滚，保留数据库及数据卷。")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "apply", "rollback"))
    parser.add_argument("--root", type=Path, default=Path("/opt/ros21"), help="existing installation directory")
    parser.add_argument("--postgres-container", help="database container name for a site with external PostgreSQL")
    parser.add_argument("--backup", type=Path, help="backup path printed by apply")
    args = parser.parse_args()
    if args.action == "rollback" and not args.backup:
        parser.error("rollback requires --backup")
    # Acquire the lock before reading the current Compose file.
    path = args.root.resolve() / ".runtime-updates"
    if args.action == "check":
        app = Update(args)
        for name, item in app.plan().items():
            print(name, item["reference"], "→", item["target"])
        return
    if not (args.root / "compose.json").is_file():
        raise SystemExit("既有安装目录缺少 compose.json")
    path.mkdir(mode=0o700, exist_ok=True)
    with (path / "update.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        app = Update(args)
        if args.action == "apply":
            app.apply()
        else:
            app.rollback(args.backup)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None

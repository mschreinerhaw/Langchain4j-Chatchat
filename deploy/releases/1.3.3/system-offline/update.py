#!/usr/bin/env python3
"""Offline Runtime OS update for the standard 1.3.2 PostgreSQL layout."""
import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import platform
import re
import signal
import shutil
import subprocess

from support import atomic_write, compose_command, digest, docker, image_id, json_bytes, output, run_command, wait_for

PACKAGE = Path(__file__).resolve().parents[1]
ROLES = {
    "api": ("api", "worker1", "worker2"), "mcp": ("mcp",),
    "node": ("node1", "node2", "studio"),
    "recovery": ("recovery1", "recovery2"),
    "executor": ("executor1", "executor2", "studio-executor"),
    "frontend": ("frontend-resource-hub", "frontend-worker"),
}
NATIVE = ("executor", "recovery", "node")
APP_ENV = {"CLINE_GOVERNANCE_CONFIG", "CLINE_EXECUTOR_CONFIG", "CLINE_BUSINESS_CONFIG"}
GATEWAY_OVERLAY = "compose.runtime-os-offline.json"
ARCHIVES = ("runtime-os-app.tar.gz", "runtime-os-native.tar.gz",
            "runtime-os-frontend.tar.gz", "runtime-os-model-gateway.tar.gz")


def read(path):
    return json.loads(path.read_bytes())


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def image_ids(item):
    return {item["id"], *item.get("acceptedImageIds", [])}


def inspect_image(item, host=None):
    prefix = ("--host", host) if host else ()
    identity, architecture = output(*prefix, "image", "inspect", "--format",
                                    "{{.Id}} {{.Os}}/{{.Architecture}}", item["reference"]).split()
    require(identity in image_ids(item) and architecture == "linux/amd64",
            "Unexpected image identity: " + item["reference"])
    return identity


def installed_image(service):
    return output("inspect", "--format", "{{.Image}} {{.State.Running}}", service).split()


class Project:
    def __init__(self, command, root, name, files):
        require(re.fullmatch(r"[a-z0-9][a-z0-9_-]*", name or ""), "Invalid Compose project name")
        self.root, self.name, self.files = root, name, files
        self.prefix = (*command, "-p", name, "--project-directory", root)

    def compose(self, *args, files=None, **kwargs):
        selected = files if files is not None else self.files
        return run_command(*self.prefix, *(arg for f in selected for arg in ("-f", f)), *args, **kwargs)

    def container(self, name):
        result = self.compose("ps", "--all", "-q", name, capture=True).stdout.decode().splitlines()
        require(len(result) == 1, "Expected one Compose container: " + name)
        return result[0]

    def up(self, *services):
        if services:
            self.compose("up", "-d", "--no-deps", "--no-build", "--pull", "never",
                         "--force-recreate", "--wait", "--wait-timeout", "240", *services)

    def stop(self, *services):
        self.compose("stop", "-t", "40", *services)


def model_project(command, root, args):
    containers = output("ps", "-a", "--filter", "label=com.docker.compose.service=llm-gateway",
                        "--format", '{{.ID}}').splitlines()
    matches = []
    for cid in containers:
        labels = json.loads(output("inspect", "--format", "{{json .Config.Labels}}", cid))
        project = labels.get("com.docker.compose.project", "")
        workdir = labels.get("com.docker.compose.project.working_dir", "")
        if workdir and Path(workdir).resolve() == root and (not args.gateway_project or project == args.gateway_project):
            matches.append((project, labels["com.docker.compose.project.config_files"]))
    require(len(matches) == 1, "Expected one running llm-gateway project in --gateway-root")
    name, paths = matches[0]
    overlay = root / GATEWAY_OVERLAY
    files = [Path(f).resolve() for f in paths.split(",") if Path(f).resolve() != overlay]
    require(len(files) > 0 and all(f.is_file() for f in files), "Missing original model gateway Compose file")
    active = files + ([overlay] if overlay.exists() else [])
    return Project(command, root, name, active), files, overlay


def compose_services(project):
    return json.loads(project.compose("config", "--format", "json", capture=True).stdout)["services"]


def memory_guard(path):
    require(not path.is_symlink() and path.is_file(), "Expected regular file: " + str(path))
    return path.read_bytes()


def native_configs(project, config):
    configs, daemons = {}, {}
    for group in NATIVE:
        for name in ROLES[group]:
            holder = json.loads(output("inspect", "--format", "{{json .}}", project.container(name)))
            for key, value in config["services"][name].get("environment", {}).items():
                if key not in APP_ENV or not value:
                    continue
                mounted = []
                for mount in holder["Mounts"]:
                    try:
                        relative = Path(value).relative_to(mount["Destination"])
                    except ValueError:
                        continue
                    if mount["Type"] == "bind":
                        mounted.append((len(mount["Destination"]), Path(mount["Source"]) / relative))
                require(mounted, "Expected a bind-mounted Coding configuration for " + name)
                path = max(mounted)[1]
                content = memory_guard(path)
                payload = json.loads(content)
                require(isinstance(payload.get("workerImage"), str), "Missing workerImage for " + name)
                configs[path] = content
                if group == "executor":
                    socket = Path(payload.get("dockerSocket", ""))
                    require(socket.is_absolute() and socket.is_socket() and socket != Path("/var/run/docker.sock"),
                            "Expected a dedicated Docker socket for " + name)
                    host = "unix://" + str(socket)
                    daemon = output("--host", host, "info", "--format", "{{.ID}}")
                    root_daemon = output("info", "--format", "{{.ID}}")
                    require(daemon != root_daemon and daemon == payload.get("expectedDaemonId"),
                            "Dedicated daemon identity changed")
                    require(not output("--host", host, "ps", "-q"), "Active Coding containers must finish")
                    daemons[host] = daemon
    require(len(configs) >= 6 and daemons, "Incomplete Native worker bindings")
    return configs, daemons


class Update:
    def __init__(self, args):
        self.root = args.root.resolve()
        self.file = self.root / "compose.json"
        self.old = memory_guard(self.file)
        self.config = json.loads(self.old)
        command = compose_command()
        self.app = Project(command, self.root, self.config.get("name"), [self.file])
        self.model, self.model_base, self.overlay = model_project(command, args.gateway_root.resolve(), args)
        self.pg = args.postgres_container or self.app.container("postgresql")
        self.images = read(PACKAGE / "offline-images/BUILD.json")["images"]
        self.archives = PACKAGE / "offline-images"
        self.backups = self.root / ".runtime-updates"
        self.targets = {name: self.images[role] for role, names in ROLES.items() for name in names}
        self.model_targets = {"llm-gateway": self.images["llm-gateway"]}
        self.model_services = compose_services(self.model)
        if "litellm" in self.model_services and "litellm" in self.images:
            self.model_targets["litellm"] = self.images["litellm"]
        elif "litellm" in self.model_services:
            print("Keeping the installed LiteLLM service and image.", flush=True)
        elif "litellm" in self.images:
            print("LiteLLM image included for optional FRAMEWORK setup.", flush=True)

    def verify_payload(self):
        require(platform.system() == "Linux" and platform.machine() in ("x86_64", "amd64"),
                "This release requires Linux amd64")
        required = {
            "offline-images/BUILD.json", "update-config/update.py", "update-config/support.py",
            "update-config/api-postgresql.sql", "update-config/mcp-postgresql.sql",
            *("offline-images/" + name for name in ARCHIVES),
        }
        checked = set()
        for line in (PACKAGE / "SHA256SUMS").read_text().splitlines():
            sha, name = line.split(None, 1)
            payload = (PACKAGE / name).resolve()
            require(PACKAGE in payload.parents and payload.is_file(), "Invalid package inventory: " + name)
            require(digest(payload) == sha, "SHA-256 mismatch: " + name)
            checked.add(payload.relative_to(PACKAGE).as_posix())
        require(required <= checked, "Incomplete package inventory")

    def db(self, name, *args, **kwargs):
        login = 'export PGPASSWORD="${POSTGRES_PASSWORD:-}" PGUSER="${POSTGRES_USER:-postgres}"; exec "$@"'
        return docker("exec", "-i", self.pg, "sh", "-ec", login, "runtime-update",
                      args[0], "-d", "live_runtime_" + name, *args[1:], **kwargs)

    def previous(self):
        states = {}
        for project, expected, declared in (
            (self.app, self.targets, self.config["services"]),
            (self.model, self.model_targets, self.model_services),
        ):
            for name in expected:
                require(name in declared and isinstance(declared[name].get("image"), str),
                        "Unsupported Compose topology: " + name)
                ref = declared[name]["image"]
                actual = installed_image(project.container(name))
                require(len(actual) == 2 and actual[1] == "true" and image_id(ref) == actual[0],
                        "Configured image differs from running container: " + name)
                states[name] = {"reference": ref, "id": actual[0]}
        return states

    def plan(self):
        self.app.compose("config", "--quiet")
        self.model.compose("config", "--quiet")
        states = self.previous()
        configs, daemons = native_configs(self.app, self.config)
        for role, table in (("api", "sys_user"), ("mcp", "mcp_template_query_binding")):
            result = self.db(role, "psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-c",
                             "SELECT to_regclass('public." + table + "') IS NOT NULL",
                             capture=True, timeout=20)
            require(result.stdout.strip() == b"t", "Missing baseline table: " + table)
        for name, target in {**self.targets, **self.model_targets}.items():
            print(name, states[name]["reference"], "->", target["reference"], flush=True)
        return states, configs, daemons

    def check_space(self, daemons):
        missing = {}
        for image in self.images.values():
            if image_id(image["reference"]) is None:
                missing[image["id"]] = image["sizeBytes"]
        docker_root = Path(output("info", "--format", "{{.DockerRootDir}}"))
        required = sum(missing.values()) + 1024 ** 3
        require(shutil.disk_usage(docker_root).free >= required,
                "Insufficient Docker storage, estimated need: {:.1f} GiB".format(required / 1024 ** 3))
        task = self.images["task"]
        for host in daemons:
            store = Path(output("--host", host, "info", "--format", "{{.DockerRootDir}}"))
            probe = docker("--host", host, "image", "inspect", task["id"], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if probe.returncode:
                require(shutil.disk_usage(store).free >= task["sizeBytes"] + 1024 ** 3,
                        "Insufficient private daemon storage: " + str(store))

    def import_images(self, daemons):
        for item in self.images.values():
            require(image_id(item["reference"]) in image_ids(item) | {None},
                    "Image tag collision: " + item["reference"])
        for name in ARCHIVES:
            docker("image", "load", "--input", self.archives / name)
        loaded_ids = {item["reference"]: inspect_image(item) for item in self.images.values()}
        task = self.images["task"]
        task_ids = set()
        for host in daemons:
            with subprocess.Popen([os.environ.get("DOCKER_BIN") or "docker", "image", "save",
                                   task["reference"]], stdout=subprocess.PIPE) as save:
                loaded = docker("--host", host, "image", "load", stdin=save.stdout, check=False)
                save.stdout.close()
                require(save.wait() == 0 and loaded.returncode == 0, "Dedicated daemon import failed")
            task_id = inspect_image(task, host)
            task_ids.add(task_id)
            docker("--host", host, "run", "--rm", "--pull=never", "--network=none", "--user", "0:0",
                   "--entrypoint", "/usr/bin/su", task_id, "-c",
                   'test "$(id -u):$(id -g)" = 10001:10001 && /usr/local/bin/python -c "import openpyxl"',
                   "runner", timeout=60)
        require(len(task_ids) == 1, "Private daemons must agree on the pinned task image identity")
        return loaded_ids, task_ids.pop()

    def edits(self, configs, task_id):
        candidate = json.loads(self.old)
        services = candidate["services"]
        for name, item in self.targets.items():
            services[name]["image"] = item["reference"]
        hub = services["frontend-resource-hub"]["environment"]
        require(hub.get("HUB_TOKEN"), "Existing PDF renderer token is missing")
        for name in ROLES["api"]:
            env = services[name]["environment"]
            env.setdefault("CHATCHAT_REPORT_EXPORT_RENDERER_URL", "http://frontend-resource-hub:8080/api/v1/report-pdf")
            env.setdefault("CHATCHAT_REPORT_EXPORT_RENDERER_TOKEN", hub["HUB_TOKEN"])
        configs = {path: json_bytes(dict(json.loads(data), workerImage=task_id))
                   for path, data in configs.items()}
        files = {self.file: json_bytes(candidate), **configs}
        files[self.overlay] = json_bytes({"services": {name: {"image": item["reference"]}
                                                       for name, item in self.model_targets.items()}})
        for mount in services["gateway"].get("volumes", []):
            if isinstance(mount, dict) and mount.get("type") == "bind" and mount.get("target") == "/srv/coding-monitor":
                directory = Path(mount["source"])
                for asset in ("app.js", "index.html", "style.css"):
                    target = directory / asset
                    memory_guard(target)
                    files[target] = (PACKAGE / "update-config/monitor" / asset).read_bytes()
        return files


    def restart(self, app_targets, model_targets, model_files):
        self.model.files = model_files
        if "litellm" in model_targets:
            self.model.up("litellm")
        self.model.up("llm-gateway")
        self.app.up("mcp", *ROLES["frontend"])
        self.app.up("api")
        self.app.up(*(name for group in NATIVE for name in ROLES[group]))
        self.app.up(*ROLES["api"][1:], "gateway")
        for project, targets in ((self.app, app_targets), (self.model, model_targets)):
            for name, target in targets.items():
                require(installed_image(project.container(name)) == [target, "true"],
                        "Bad running image: " + name)
        gateway = self.app.container("gateway")
        script = ("url="
                  "http://127.0.0.1:8080/api/v1/enterprise/auth/me; "
                  "if command -v curl >/dev/null; then code=$(curl -s -o /dev/null -w '%{http_code}' \"$url\"); "
                  "else code=$(wget -S -O /dev/null \"$url\" 2>&1 | awk '/HTTP\\// {print $2; exit}'); fi; "
                  "test \"$code\" = 401 || test \"$code\" = 403")
        wait_for(lambda: docker("exec", gateway, "sh", "-ec", script, check=False,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=8).returncode == 0, "18100 authentication route", 120)

    def monitor_assets(self, changes):
        allowed = {self.file, self.overlay}
        for mount in self.config["services"]["gateway"].get("volumes", []):
            if isinstance(mount, dict) and mount.get("type") == "bind" and mount.get("target") == "/srv/coding-monitor":
                allowed.update(Path(mount["source"]) / asset for asset in ("app.js", "index.html", "style.css"))
        allowed.update(changes)
        return allowed

    def rollback(self, backup, automatic=False):
        require(backup.parent == self.backups and (backup / "transition").is_file(),
                "Backup must belong to this installation")
        state = read(backup / "state.json")
        require(state["app"] == self.app.name and state["model"] == self.model.name, "Rollback project mismatch")
        require(set(state["images"]) == set(self.targets) | set(self.model_targets), "Rollback topology mismatch")
        for name, item in state["images"].items():
            require(image_id(item["reference"]) == item["id"], "Previous image tag changed: " + name)
        for index, item in enumerate(state["files"]):
            path = Path(item["path"])
            require(path.is_absolute() and not path.is_symlink(), "Unsafe rollback file")
            current = path.read_bytes() if path.exists() else None
            if not automatic:
                old = (backup / ("old-" + str(index))).read_bytes() if item["existed"] else None
                candidate = (backup / ("new-" + str(index))).read_bytes()
                require(current == old or current == candidate, "Configuration edited after upgrade: " + str(path))
        if not automatic:
            native_configs(self.app, self.config)
        self.app.stop("gateway", *(n for n in self.targets if n != "mcp"))
        for index, item in enumerate(state["files"]):
            path = Path(item["path"])
            if item["existed"]:
                atomic_write(path, (backup / ("old-" + str(index))).read_bytes(),
                             (backup / ("old-" + str(index))).stat() if not path.exists() else path.stat())
            elif path.exists():
                path.unlink()
        app = {name: state["images"][name]["id"] for name in self.targets}
        model = {name: state["images"][name]["id"] for name in self.model_targets}
        self.restart(app, model, self.model_base + ([self.overlay] if self.overlay.exists() else []))
        (backup / "rolled-back").touch()
        print("Rollback complete", backup, flush=True)

    def apply(self):
        states, configs, daemons = self.plan()
        all_targets = {**self.targets, **self.model_targets}
        if all(states[name]["id"] in image_ids(item) for name, item in all_targets.items()) and all(
                json.loads(value)["workerImage"] in image_ids(self.images["task"]) for value in configs.values()):
            print("Release already active")
            return
        self.check_space(daemons)
        loaded_ids, task_id = self.import_images(daemons)
        changes = self.edits(configs, task_id)
        require(set(changes) <= self.monitor_assets(configs), "Unexpected configuration target")
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ").lower()
        backup = self.backups / stamp
        backup.mkdir(mode=0o700)
        files = []
        for index, (path, update) in enumerate(changes.items()):
            require(not path.is_symlink(), "Symlinked configuration: " + str(path))
            old = path.read_bytes() if path.exists() else None
            if path == self.file:
                require(old == self.old, "Runtime Compose changed during preflight")
            if path in configs:
                require(old == configs[path], "Native configuration changed during preflight")
            files.append({"path": str(path), "existed": old is not None})
            if old is not None:
                (backup / ("old-" + str(index))).write_bytes(old)
            (backup / ("new-" + str(index))).write_bytes(update)
        (backup / "state.json").write_bytes(json_bytes({
            "app": self.app.name, "model": self.model.name, "images": states, "files": files,
        }))
        self.app.compose("config", "--quiet", files=[backup / "new-0"])
        overlay_index = list(changes).index(self.overlay)
        staged_overlay = backup / ("new-" + str(overlay_index))
        self.model.compose("config", "--quiet", files=self.model_base + [staged_overlay])
        for role in ("api", "mcp"):
            with (backup / (role + ".dump")).open("wb") as stream:
                self.db(role, "pg_dump", "-Fc", stdout=stream, timeout=900)
        print("Rollback directory:", backup, flush=True)
        (backup / "transition").touch()
        try:
            self.app.stop("gateway", *ROLES["api"])
            for host, daemon in daemons.items():
                require(output("--host", host, "info", "--format", "{{.ID}}") == daemon
                        and not output("--host", host, "ps", "-q"), "Coding work started during preflight")
            self.app.stop("mcp", *(name for group in NATIVE for name in ROLES[group]))
            for role in ("api", "mcp"):
                with (PACKAGE / "update-config" / (role + "-postgresql.sql")).open("rb") as stream:
                    self.db(role, "psql", "-X", "-v", "ON_ERROR_STOP=1", stdin=stream, timeout=90)
            for path, update in changes.items():
                if not path.exists():
                    require(path == self.overlay, "Missing configuration: " + str(path))
                    path.touch(mode=0o600, exist_ok=False)
                atomic_write(path, update, path.stat())
            self.restart({name: loaded_ids[item["reference"]] for name, item in self.targets.items()},
                         {name: loaded_ids[item["reference"]] for name, item in self.model_targets.items()},
                         self.model_base + [self.overlay])
        except (Exception, KeyboardInterrupt):
            self.rollback(backup, automatic=True)
            raise
        (backup / "applied").touch()
        print("Offline update complete", flush=True)


def interrupted(signum, frame):
    raise KeyboardInterrupt("Upgrade interrupted")


def main():
    os.umask(0o077)
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "apply", "rollback"))
    parser.add_argument("--root", type=Path, default=Path("/opt/ros21"))
    parser.add_argument("--gateway-root", type=Path, default=Path("/opt/llm-compatibility-gateway"))
    parser.add_argument("--gateway-project")
    parser.add_argument("--postgres-container")
    parser.add_argument("--backup", type=Path)
    args = parser.parse_args()
    if args.action == "rollback" and not args.backup:
        parser.error("rollback requires --backup")
    if args.action == "check":
        app = Update(args)
        app.verify_payload()
        _, _, daemons = app.plan()
        app.check_space(daemons)
        print("Preflight complete", flush=True)
        return
    root = args.root.resolve()
    memory_guard(root / "compose.json")
    directory = root / ".runtime-updates"
    require(not directory.is_symlink(), "Backup directory must not be a symlink")
    directory.mkdir(mode=0o700, exist_ok=True)
    with (directory / "update.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        app = Update(args)
        if args.action == "apply":
            app.verify_payload()
            app.apply()
        else:
            app.rollback(args.backup.resolve())


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None

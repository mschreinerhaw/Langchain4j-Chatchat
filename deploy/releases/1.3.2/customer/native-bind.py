#!/usr/bin/env python3
"""Bind the customer Native Worker to the installation's private daemon."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

ROOT = Path("/srv/runtime-os-coding-node-20261110/strict-daemon-3")
STATE = Path("/opt/runtime-os/p18-d736e9f")
TEMPLATE = Path("/opt/runtime-os/cn2/template")
CONFIG = Path("/opt/runtime-os-docker-stage/config")
WORKER_TAG = "runtime-os-native-worker:1.3.2"
SOCKET = f"{ROOT}/run/docker.sock"


def output(*args):
    return subprocess.check_output(args, text=True, timeout=20).strip()


def write(path, value, mode=0o600):
    import tempfile
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as file:
        path_data = Path(file.name)
        file.write(json.dumps(value, indent=2) + "\n")
        path_data.chmod(mode)
    path_data.replace(path)


def verify_process(binding, program, flag, config_file):
    pid = binding["pid"]
    proc = Path(f"/proc/{pid}")
    command = (proc / "cmdline").read_bytes().decode().rstrip("\0").split("\0")
    if (pid <= 1 or Path(command[0]).name != program or flag not in command
            or command[command.index(flag) + 1] != str(ROOT / config_file)
            or (proc / "ns/net").stat().st_ino == Path("/proc/1/ns/net").stat().st_ino):
        raise RuntimeError("The dedicated Native daemon failed process isolation verification")
    if binding["startTicks"] != (proc / "stat").read_text().rsplit(")", 1)[1].split()[19]:
        raise RuntimeError("The dedicated Native daemon process identity is stale")


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--tenant")
    p.add_argument("--user", action="append")
    p.add_argument("--model")
    a = p.parse_args()
    fresh = a.tenant is not None
    executor_path = STATE / "cn2-executor.json"
    broker_path = STATE / "gateway.json"
    if fresh:
        if not a.user or not a.model or not all(re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", value)
                                                for value in [a.tenant, *a.user, a.model]):
            p.error("Specify the real customer tenant/user IDs and a published model name")
        if any(p.exists() for p in (executor_path, broker_path, CONFIG / "api/native-coding-connection.json")):
            raise RuntimeError("Native policy already exists; preserve the current identity and task state")
    elif not executor_path.exists():
        return  # Coding is configured after the initial customer bootstrap.
    daemon = json.loads(output("curl", "--noproxy", "*", "-fsS", "--unix-socket", SOCKET, "http://localhost/info"))
    if (daemon["DockerRootDir"] != str(ROOT / "data") or daemon["CgroupDriver"] == "none"
            or "name=rootless" in daemon.get("SecurityOptions", [])
            or not all(daemon.get(p) for p in ("MemoryLimit", "CpuCfsQuota", "PidsLimit"))):
        raise RuntimeError("The dedicated Native daemon failed resource verification")
    bindings = json.loads((ROOT / "run/executor-processes.json").read_text())
    verify_process(bindings["daemon"], "dockerd", "--config-file", "daemon.json")
    verify_process(bindings["containerd"], "containerd", "--config", "containerd.toml")
    if not fresh:
        config = json.loads(executor_path.read_text())
        if config["expectedDaemonId"] != daemon["ID"]:
            raise RuntimeError("The daemon changed identity; recover existing Native tasks before continuing")
        output("docker", "-H", f"unix://{SOCKET}", "image", "inspect", "-f", "{{.Id}}", config["workerImage"])
        return

    gateway = output("docker", "network", "inspect", "bridge", "-f", "{{(index .IPAM.Config 0).Gateway}}")
    worker = output("docker", "-H", f"unix://{SOCKET}", "image", "inspect", "-f", "{{.Id}}", WORKER_TAG)
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", worker):
        raise RuntimeError("Unexpected Native Worker image identity")
    issuer, token, ownership = (secrets.token_hex(32) for _ in range(3))
    env = dict(line.split("=", 1) for line in (CONFIG / "release.env").read_text().splitlines()
               if "=" in line and not line.startswith("#"))
    policy = {
        "users": a.user, "workspaces": {"project": str(TEMPLATE)}, "models": [a.model],
        "tools": ["read_files", "search_codebase", "editor", "apply_patch", "skills", "run_commands", "submit_and_exit"],
        "datasets": [], "dynamicModels": True,
        "runtimeModelAuthority": "http://127.0.0.1:18180/internal/runtime/native-coding/model",
        "mcp": {"authMode": "native-runtime", "url": "http://127.0.0.1:18180/internal/runtime/native-coding/mcp",
                "tools": []},
    }
    common = {"workerImage": worker, "signingKey": issuer, "workerRuntime": "cline-python311",
              "maxWorkers": 2, "maxPerTenant": 2, "tenants": {a.tenant: policy}, "mcpServerName": "runtime"}
    for directory in (STATE / "state", STATE / "workers", STATE / "executor-control", TEMPLATE):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    socket_path = str(STATE / "executor.sock")
    write(executor_path, common | {
        "expectedDaemonId": daemon["ID"], "daemonMode": "dedicated-rootful", "dedicatedDaemonRoot": str(ROOT),
        "dockerSocket": SOCKET, "dedicatedProcesses": bindings, "enableNativeSilo": True,
        "instanceId": "customer-native-executor", "ownershipKey": ownership, "apiToken": token,
        "workersRoot": str(STATE / "workers"), "hostWorkersRoot": str(STATE / "workers"),
        "controlRoot": str(STATE / "executor-control"), "socketPath": socket_path,
        "resources": {"memoryBytes": 3221225472, "nanoCpus": 2000000000, "pidsLimit": 256, "cpuSet": ""},
        "modelNames": {}, "operationContext": True, "dynamicModels": True,
    })
    write(broker_path, common | {
        "host": gateway, "port": 18360, "stateRoot": str(STATE / "state"), "workerRoot": str(STATE / "workers"),
        "modelProfiles": {}, "executor": {"socketPath": socket_path, "token": token},
        "s3": {"allowedOrigins": ["http://127.0.0.1:9000", "http://silo:9000"],
               "maxInputBytes": 1073741824, "maxOutputBytes": 67108864, "autoApproveAuthorizedTools": True,
               "scopeLayout": "tenant-task-v1", "bucket": "runtime-assets"},
        "frontendHub": {"url": "http://127.0.0.1:18220", "token": env["HUB_TOKEN"]},
    }, 0o640)
    path = CONFIG / "api/native-coding-connection.json"
    write(path, {"endpoint": "http://host.docker.internal:18360", "issuerKey": issuer, "defaultModel": a.model,
                 "modelProfiles": {}, "dynamicModels": True, "recoveryNodes": {"default": "http://native-recovery:18362"}})
    os.chown(path, 10001, 10001)
    print("Native configuration initialized. Start the full stack after provisioning its Silo bucket.")


if __name__ == "__main__":
    main()

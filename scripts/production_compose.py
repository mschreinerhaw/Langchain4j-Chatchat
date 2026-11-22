"""Prepare and validate the production Compose include chain without logging resolved secrets."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

PRODUCTION = Path("deploy/docker/production")
OVERLAY = "compose.host-dependencies.yaml"
WRAPPER = "compose.release.yaml"
PROBE = "opensearch-ready"
HEALTH_TEST = ["CMD-SHELL",
    "wget -Y off --no-check-certificate -S -T 3 -O /dev/null https://host.docker.internal:9200/ 2>&1 "
    "| grep -Eq 'HTTP/[0-9.]+ (200|401) '"]


def resolved(command, env=None):
    subprocess.run([*command, "config", "--quiet"], env=env, check=True)
    return json.loads(subprocess.check_output([*command, "config", "--format", "json"], env=env))


def validate_readiness(config):
    services = config["services"]
    probe = services.get(PROBE, {})
    required = {
        "image": "nginx:1.28-alpine", "entrypoint": ["sleep", "infinity"], "init": True,
        "pids_limit": 256, "read_only": True, "restart": "unless-stopped", "cap_drop": ["ALL"],
        "security_opt": ["no-new-privileges:true"],
    }
    for key, value in required.items():
        if probe.get(key) != value:
            raise ValueError(f"Invalid {PROBE} setting: {key}")
    if probe.get("privileged") or probe.get("cap_add") or probe.get("pid"):
        raise ValueError(f"Unexpected {PROBE} process privileges")
    if set(probe.get("networks", {})) != {"control"}:
        raise ValueError(f"Invalid {PROBE} network")
    hosts = probe.get("extra_hosts", [])
    if [host.replace("=", ":", 1) for host in hosts] != ["host.docker.internal:host-gateway"]:
        raise ValueError(f"Invalid {PROBE} host gateway")
    health = {"test": HEALTH_TEST, "interval": "30s", "timeout": "4s", "retries": 5,
              "start_period": "30s", "start_interval": "5s"}
    if probe.get("healthcheck") != health:
        raise ValueError(f"Invalid {PROBE} healthcheck")
    for name in ("api", "mcp"):
        dep = services.get(name, {}).get("depends_on", {}).get(PROBE, {})
        if dep.get("condition") != "service_healthy" or not dep.get("required", True) or dep.get("restart"):
            raise ValueError(f"Invalid {name} dependency on {PROBE}")


def source_overlay(release):
    relative = (PRODUCTION / OVERLAY).as_posix()
    manifest = json.loads((release / "source-manifest.json").read_text())
    data = (release / "source" / relative).read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest["files"].get(relative):
        raise ValueError("Release readiness overlay differs from its verified source")
    return data


def release_compose(release):
    if (release / OVERLAY).read_bytes() != source_overlay(release):
        raise ValueError("Generated readiness overlay differs from its verified source")
    relative = (PRODUCTION / WRAPPER).as_posix()
    wrapper = release / "source" / relative
    manifest = json.loads((release / "source-manifest.json").read_text())
    if hashlib.sha256(wrapper.read_bytes()).hexdigest() != manifest["files"].get(relative):
        raise ValueError("Release Compose wrapper differs from its verified source")
    env = dict(os.environ, RUNTIME_OS_RELEASE_DIR=str(release),
               RUNTIME_OS_HOST_DEPENDENCIES_FILE=str(release / OVERLAY))
    command = ["docker", "compose", "-f", str(wrapper)]
    validate_readiness(resolved(command, env))
    return command, env


def prepare_release_readiness(release):
    (release / OVERLAY).write_bytes(source_overlay(release))
    release_compose(release)


def validate_container(spec):
    host = spec["HostConfig"]
    health = spec["Config"]["Healthcheck"]
    if (host.get("Init") is not True or host.get("PidsLimit") != 256
        or host.get("Privileged") or host.get("SecurityOpt") != ["no-new-privileges:true"]
        or host.get("CapDrop") != ["ALL"] or not host.get("ReadonlyRootfs")
        or host.get("RestartPolicy", {}).get("Name") != "unless-stopped"
        or health != {"Test": HEALTH_TEST, "Interval": 30_000_000_000, "Timeout": 4_000_000_000,
                      "Retries": 5, "StartPeriod": 30_000_000_000, "StartInterval": 5_000_000_000}):
        raise ValueError("Installed readiness container differs from the fixed model; recreate it with the approved runbook")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Validate production readiness before starting without recreation.")
    parser.add_argument("compose")
    args = parser.parse_args()
    command = ["docker", "compose", "-f", args.compose]
    validate_readiness(resolved(command))
    existing = subprocess.check_output([*command, "ps", "-aq", PROBE], text=True).split()
    if existing:
        for container in json.loads(subprocess.check_output(["docker", "inspect", *existing], text=True)):
            validate_container(container)

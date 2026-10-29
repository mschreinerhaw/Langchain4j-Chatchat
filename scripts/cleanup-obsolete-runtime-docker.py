"""Plan/apply removal of superseded Runtime containers and application images.

Run on the deployment host. Keeps running containers, their namespace dependencies,
one stopped rollback per service, explicit release rollback, configured images and
offline runtime/infrastructure images. Never removes volumes or uses force.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess

BASES = [
    r"runtime-os-app-stage-(?:api|mcp|coding-agent|node-proxy|gateway|news|market)-1",
    r"sql-metadata-preview-(?:api|mcp)", r"runtime-analysis-preview-(?:node|proxy)", r"sql-stability-preview-coding",
]
SERVICE = re.compile(r"^("+"|".join(BASES)+r")(?:-|$)")
REPOSITORIES = {"runtime-os-api", "runtime-os-mcp", "runtime-os-coding-agent", "runtime-os-news", "runtime-os-gateway"}
REPOSITORIES |= {"localhost:18443/runtime-os/"+name for name in ["api", "mcp", "coding-agent", "news", "gateway"]}


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def inspect(kind, ids):
    values = list(dict.fromkeys(ids))
    return [item for start in range(0, len(values), 50)
            for item in json.loads(run("docker", kind, "inspect", *values[start:start+50]))]


def created(value):
    # Docker timestamps may include nanoseconds; only second precision is needed for retention.
    return datetime.fromisoformat(value[:19]+"+00:00")


def free_bytes():
    stat = os.statvfs("/opt")
    return stat.f_bavail*stat.f_frsize


def containers():
    return inspect("container", run("docker", "ps", "-aq").splitlines())


def images():
    return inspect("image", run("docker", "image", "ls", "-aq", "--no-trunc").splitlines())


def configured_images():
    stage = Path("/opt/runtime-os-docker-stage")
    env = dict(line.split("=", 1) for line in (stage/"config/deployment.env").read_text().splitlines()
               if "=" in line and not line.lstrip().startswith("#"))
    # Deployed Compose is JSON (also valid YAML), as written by the existing release helper.
    config = json.loads((stage/"compose.yaml").read_text())
    tags = set(value for key, value in env.items() if key.endswith("_IMAGE"))
    for service in config["services"].values():
        value = service.get("image", "")
        match = re.fullmatch(r"\$\{([^}:]+)(?::[-?]([^}]*))?\}", value)
        if match:
            value = env.get(match.group(1), match.group(2) or "")
        if value and "$" not in value:
            tags.add(value)
    ids = set()
    for tag in tags:
        try:
            ids.add(json.loads(run("docker", "image", "inspect", tag))[0]["Id"])
        except subprocess.CalledProcessError:
            pass  # Never fetch missing optional images as part of cleanup.
    return ids


def plan(root, grace_minutes):
    deployment = json.loads((root/"evidence/production-deployment.json").read_text())
    assert deployment["status"] == "DEPLOYED" and deployment["mode"] == "production"
    all_containers = containers()
    protected = {v["Id"] for v in all_containers if v["State"]["Status"] not in ["exited", "dead", "created"]}
    rollback_names = set(deployment["retired"].values())
    protected |= {v["Id"] for v in all_containers if v["Name"].lstrip("/") in rollback_names}
    cutoff = datetime.now(timezone.utc)-timedelta(minutes=grace_minutes)
    candidates = []
    groups = {}
    for value in all_containers:
        name = value["Name"].lstrip("/")
        match = SERVICE.match(name)
        if match and name != match.group(1) and value["State"]["Status"] == "exited" \
                and value["State"].get("ExitCode") in [0, 143] and created(value["Created"]) < cutoff:
            groups.setdefault(match.group(1), []).append(value)
        if not match or value["Id"] in protected or value["State"]["Status"] not in ["exited", "dead", "created"]:
            continue
        if name == match.group(1):
            protected.add(value["Id"])  # A deliberately stopped canonical service is not an obsolete backup.
            continue
        if created(value["Created"]) >= cutoff:
            protected.add(value["Id"])
            continue
        candidates.append(value)
    for group in groups.values():
        protected.add(max(group, key=lambda v: v["Created"])["Id"])
    # Active/retained sidecars may share another container's network namespace.
    lookup = {key: v["Id"] for v in all_containers for key in [v["Id"], v["Name"].lstrip("/")]}
    remaining = {v["Id"] for v in all_containers if v not in candidates or v["Id"] in protected}
    while True:
        dependencies = set()
        for v in all_containers:
            for field in ["NetworkMode", "PidMode", "IpcMode"]:
                mode = v["HostConfig"].get(field, "")
                if v["Id"] in remaining and mode.startswith("container:"):
                    target = mode.split(":", 1)[1]
                    resolved = lookup.get(target) or next((x["Id"] for x in all_containers if x["Id"].startswith(target)), None)
                    if resolved:
                        dependencies.add(resolved)
        if dependencies <= remaining:
            break
        remaining |= dependencies
    protected |= remaining
    removed = [v for v in candidates if v["Id"] not in protected]
    return {"graceMinutes": grace_minutes, "freeBytesBefore": free_bytes(), "containersBefore": len(all_containers),
            "imagesBefore": len(images()), "protectedContainerIds": sorted(protected),
            "containers": [{"id": v["Id"], "name": v["Name"].lstrip("/"), "imageId": v["Image"], "created": v["Created"]} for v in removed],
            "preservesVolumes": True, "usesForce": False, "offlineBasesPreserved": True}


def apply(root, result, grace_minutes):
    result["removedContainers"] = []
    result["removedImageTags"] = []
    result["errors"] = []
    for candidate in result["containers"]:
        current = json.loads(run("docker", "container", "inspect", candidate["id"]))[0]
        assert current["Name"].lstrip("/") == candidate["name"]
        assert current["State"]["Status"] in ["exited", "dead", "created"] and not current["State"]["Running"]
        run("docker", "container", "rm", candidate["id"])  # no -v and no --force
        result["removedContainers"].append(candidate["name"])
    cutoff = datetime.now(timezone.utc)-timedelta(minutes=grace_minutes)
    configured = configured_images()
    # Newer children first; no-force Docker deletion also protects any external references.
    for image in sorted(images(), key=lambda v: v["Created"], reverse=True):
        if image["Id"] in configured or created(image["Created"]) >= cutoff:
            continue
        tags = image.get("RepoTags") or []
        if tags and not all(tag.rsplit(":", 1)[0] in REPOSITORIES for tag in tags):
            continue
        # Refresh references before each deletion, including stopped and concurrently created containers.
        if image["Id"] in {v["Image"] for v in containers()}:
            continue
        try:
            run("docker", "image", "rm", *(tags or [image["Id"]]))
            result["removedImageTags"].extend(tags or [image["Id"]])
        except subprocess.CalledProcessError as error:
            result["errors"].append({"imageId": image["Id"], "reason": error.stderr.strip()[:500]})
    result["freeBytesAfter"] = free_bytes()
    result["reclaimedFilesystemBytes"] = result["freeBytesAfter"]-result["freeBytesBefore"]
    result["containersAfter"] = len(containers())
    result["imagesAfter"] = len(images())
    result["allProtectedContainersRemain"] = set(result["protectedContainerIds"]) <= {v["Id"] for v in containers()}
    assert result["allProtectedContainersRemain"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--grace-minutes", type=int, default=30)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = args.release_root.resolve()
    root.relative_to(Path("/opt/chatchat-releases").resolve())
    assert root != Path("/opt/chatchat-releases") and args.grace_minutes >= 0
    result = plan(root, args.grace_minutes)
    result["applied"] = args.apply
    if args.apply:
        apply(root, result, args.grace_minutes)
    output = root/"evidence"/("docker-cleanup.json" if args.apply else "docker-cleanup-plan.json")
    output.write_text(json.dumps(result, indent=2)+"\n")
    print(json.dumps({key: result[key] for key in ["applied", "containersBefore", "imagesBefore"]}
                     | {"plannedContainerRemovals": len(result["containers"]), "report": str(output)}
                     | ({key: result[key] for key in ["containersAfter", "imagesAfter", "reclaimedFilesystemBytes"]} if args.apply else {})))


if __name__ == "__main__":
    main()

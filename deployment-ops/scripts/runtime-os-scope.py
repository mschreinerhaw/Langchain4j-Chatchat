"""Read-only installer preflight and inventory for one recorded Runtime OS project."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def docker(*args):
    env = dict(os.environ, DOCKER_HOST="unix:///var/run/docker.sock")
    env.pop("DOCKER_CONTEXT", None)
    return subprocess.check_output(("docker", *args), env=env, text=True, timeout=12).strip()


def document(path):
    if path.is_symlink():
        raise ValueError("Symbolic link refused in installation state")
    return json.loads(path.read_bytes())


def scope(root, project):
    state = document(root / "installation.json")
    compose = document(root / "compose.json")
    if state.get("project") != project or compose.get("name") != project:
        raise ValueError("Installation project does not match the requested project")
    return compose["services"]


def project_inventory(project, services):
    ids = docker("ps", "-aq", "--no-trunc", "--filter", "label=com.docker.compose.project=" + project)
    containers, images = [], set()
    for item in json.loads(docker("inspect", *ids.splitlines())) if ids else []:
        labels = item["Config"].get("Labels") or {}
        if labels.get("com.docker.compose.project") != project:
            continue
        service = labels.get("com.docker.compose.service")
        if service not in services:
            continue
        state = item["State"]
        containers.append({"id": item["Id"], "service": service, "state": state["Status"],
                           "health": state.get("Health", {}).get("Status"), "image": item["Image"]})
        images.add(item["Image"])
    return {"project": project, "containers": sorted(containers, key=lambda item: item["service"]),
            "images": sorted(images)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "status"))
    args = parser.parse_args()
    project = os.environ["PROJECT"]
    if not re.fullmatch(r"[a-z][a-z0-9-]{2,39}", project):
        raise ValueError("Invalid installation project")
    root = Path("/install")
    installed = (root / "installation.json").is_file()
    services = scope(root, project) if installed else {}
    if args.action == "status":
        if not installed:
            raise ValueError("No owned installation to monitor")
        print(json.dumps(project_inventory(project, services), separators=(",", ":")))
        return
    if not installed and root.exists() and any(root.iterdir()):
        raise ValueError("Fresh installation requires an empty directory")
    version = docker("compose", "version", "--short")
    major = re.match(r"v?([0-9]+)\.", version)
    if not major or int(major.group(1)) < 2:
        raise ValueError("Docker Compose v2 or newer is required")
    if installed:
        docker("compose", "-p", project, "-f", str(root / "compose.json"), "config", "--quiet")
    images = document(Path("/opt/product/images.json"))
    for role, image in images.items():
        actual = docker("image", "inspect", "--format", "{{.Id}}", image["imageId"])
        if actual != image["imageId"]:
            raise ValueError("Offline image identity mismatch for " + role)
    print("Script check passed for " + project + " (" + ("upgrade" if installed else "install") + ")")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None

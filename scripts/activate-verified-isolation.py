"""Activate verified Java images on an existing isolated Compose stack."""

import argparse
import copy
import json
from pathlib import Path
import re
import subprocess


JAVA_SERVICES = frozenset(("api", "mcp", "news"))
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")
ISOLATION_PROJECT = re.compile(r"[a-z0-9][a-z0-9_-]*")


def verified_images(receipt, services):
    result = {}
    if not services or set(services) - JAVA_SERVICES:
        raise ValueError("Only explicit Java services are supported")
    for service in services:
        record = receipt.get("images", {}).get(service, {})
        image = record.get("Id", "")
        if (record.get("verification") != "PASSED" or not IMAGE_ID.fullmatch(image)
                or not re.fullmatch(r"[0-9a-f]{64}", record.get("jarSha256", ""))
                or not record.get("resources")):
            raise ValueError(f"Missing verified immutable build receipt for {service}")
        result[service] = image
    return result


def write_override(path, images):
    path.write_text(json.dumps({
        "services": {service: {"image": image} for service, image in images.items()}
    }), encoding="utf-8")


class DockerStack:
    def __init__(self, root, project, compose_files, env_file, timeout):
        if not ISOLATION_PROJECT.fullmatch(project) or "isolated" not in re.split("[-_]", project):
            raise ValueError("Supply the explicit isolated Compose project name")
        self.project = project
        self.timeout = timeout
        self.base = ["docker", "compose", "--project-directory", str(root), "--project-name", project]
        for flag, files in (("--env-file", [env_file] if env_file else []), ("-f", compose_files)):
            for name in files:
                file = (root / name).resolve(strict=True)
                if not file.is_file() or not file.is_relative_to(root):
                    raise ValueError("Compose configuration paths must be inside the isolated stack root")
                self.base.extend((flag, str(file)))

    def argv(self, override):
        return self.base + (["-f", str(override)] if override else [])

    def config(self, override=None):
        data = subprocess.check_output(self.argv(override) + ["config", "--format", "json"])
        return json.loads(data)

    def running_healthy_image(self, service, override=None):
        containers = subprocess.check_output(self.argv(override) + ["ps", "-q", service], text=True).split()
        if len(containers) != 1:
            raise RuntimeError(f"Expected one running container for {service}")
        info = json.loads(subprocess.check_output(["docker", "inspect", containers[0]]))[0]
        labels = info["Config"]["Labels"]
        if (labels.get("com.docker.compose.project") != self.project
                or labels.get("com.docker.compose.service") != service
                or info["State"].get("Health", {}).get("Status") != "healthy"):
            raise RuntimeError(f"Container identity or health mismatch for {service}")
        return info["Image"]

    def up(self, override, services):
        subprocess.run(self.argv(override) + [
            "up", "-d", "--wait", "--wait-timeout", str(self.timeout),
            "--no-deps", "--no-build", "--pull", "never", *services,
        ], check=True)


def activate(stack, images, output):
    output.mkdir(parents=True, exist_ok=False)
    record = {"project": stack.project, "requestedImages": images}
    evidence = output / "activation-result.json"

    def save(status):
        record["status"] = status
        evidence.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")

    previous = {service: stack.running_healthy_image(service) for service in images}
    record["previousImages"] = previous
    rollback, candidate = output / "rollback.override.json", output / "candidate.override.json"
    write_override(rollback, previous)
    write_override(candidate, images)
    current = stack.config()
    for override, expected_images in ((rollback, previous), (candidate, images)):
        expected = copy.deepcopy(current)
        for service, image in expected_images.items():
            expected["services"][service]["image"] = image
        if stack.config(override) != expected:
            save("CONFIG_MISMATCH")
            raise RuntimeError("Effective Compose configuration differs outside selected images")

    def require_images(override, expected):
        observed = {service: stack.running_healthy_image(service, override) for service in expected}
        if observed != expected:
            raise RuntimeError("Running image identities differ from the activation record")
        return observed

    save("ACTIVATING")
    try:
        stack.up(candidate, images)
        record["runningImages"] = require_images(candidate, images)
    except (Exception, KeyboardInterrupt) as error:
        save("ROLLING_BACK")
        try:
            stack.up(rollback, previous)
            record["runningImages"] = require_images(rollback, previous)
            save("ROLLED_BACK")
        except (Exception, KeyboardInterrupt) as rollback_error:
            save("ROLLBACK_FAILED")
            raise RuntimeError("Activation failed and previous health could not be restored") from rollback_error
        raise RuntimeError("Activation failed. Previous healthy images restored.") from error
    save("ACTIVATED")


def main():
    import fcntl

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stack-root", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose", action="append", required=True)
    parser.add_argument("--env-file")
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--services", nargs="+", choices=sorted(JAVA_SERVICES), required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    root = args.stack_root.resolve(strict=True)
    images = verified_images(json.loads(args.receipt.read_text(encoding="utf-8")), args.services)
    stack = DockerStack(root, args.project, args.compose, args.env_file, args.timeout)
    with (root / ".verified-compose-activation.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        activate(stack, images, args.evidence_dir.resolve())


if __name__ == "__main__":
    main()

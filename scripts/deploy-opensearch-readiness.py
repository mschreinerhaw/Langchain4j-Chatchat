"""Validate, apply or roll back only the host OpenSearch readiness overlay."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import time

from production_compose import OVERLAY, PROBE, PRODUCTION, resolved, validate_container, validate_readiness


def inspect(container):
    return json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]


def wait_healthy(command, seconds=90):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        container = subprocess.check_output([*command, "ps", "-q", PROBE], text=True).strip()
        if container:
            spec = inspect(container)
            validate_container(spec)
            if spec["State"].get("Health", {}).get("Status") == "healthy":
                pid1 = subprocess.check_output(["docker", "exec", container, "cat", "/proc/1/comm"], text=True).strip()
                if pid1 not in ("docker-init", "tini"):
                    raise RuntimeError("Readiness container PID 1 is not the configured init")
                return
        time.sleep(2)
    raise TimeoutError("Readiness container did not become healthy")


def intact_except_probe(before, candidate):
    if {k: v for k, v in before.items() if k != "services"} != {k: v for k, v in candidate.items() if k != "services"}:
        raise ValueError("Candidate changes project-level settings")
    if {k: v for k, v in before["services"].items() if k != PROBE} != {
        k: v for k, v in candidate["services"].items() if k != PROBE
    }:
        raise ValueError("Candidate changes services other than the readiness probe")
    old = dict(before["services"][PROBE])
    new = dict(candidate["services"][PROBE])
    for model in (old, new):
        for key in ("init", "pids_limit", "healthcheck"):
            model.pop(key, None)
    if old != new:
        raise ValueError("Candidate changes an unrelated readiness service setting")


def copy_atomic(src, dest):
    previous = dest.stat()
    with tempfile.NamedTemporaryFile(dir=dest.parent, prefix="." + dest.name + ".", delete=False) as f:
        tmp = Path(f.name)
    try:
        shutil.copyfile(src, tmp)
        os.chmod(tmp, stat.S_IMODE(previous.st_mode))
        os.chown(tmp, previous.st_uid, previous.st_gid)
        os.replace(tmp, dest)
    finally:
        tmp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "apply", "rollback"))
    parser.add_argument("--compose", type=Path, default=Path("/opt/runtime-os/compose.yaml"))
    parser.add_argument("--overlay", type=Path, default=Path("/opt/runtime-os") / OVERLAY)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1] / PRODUCTION / OVERLAY)
    parser.add_argument("--backup", type=Path)
    parser.add_argument("--api-check", type=Path, help="Existing executable that verifies API health")
    parser.add_argument("--mcp-check", type=Path, help="Existing executable that verifies MCP health")
    args = parser.parse_args()
    if "RUNTIME_OS_HOST_DEPENDENCIES_FILE" in os.environ:
        parser.error("unset RUNTIME_OS_HOST_DEPENDENCIES_FILE before use")
    compose, overlay, source = (path.resolve(strict=True) for path in (args.compose, args.overlay, args.source))
    command = ["docker", "compose", "-f", str(compose)]
    if args.action != "check" and (not args.backup or not args.api_check or not args.mcp_check):
        parser.error("apply and rollback require --backup, --api-check and --mcp-check")
    checks = [args.api_check, args.mcp_check]

    def smoke():
        for check in checks:
            subprocess.run([str(check.resolve(strict=True))], check=True, timeout=60,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def validate_candidate():
        current = resolved(command)
        current_overlay = resolved(command, dict(os.environ, RUNTIME_OS_HOST_DEPENDENCIES_FILE=str(overlay)))
        if current != current_overlay:
            raise RuntimeError("The selected overlay is not the installed wrapper's active configuration")
        candidate = resolved(command, dict(os.environ, RUNTIME_OS_HOST_DEPENDENCIES_FILE=str(source)))
        validate_readiness(candidate)
        intact_except_probe(current, candidate)
        return candidate

    if args.action == "check":
        validate_candidate()
        print("Merged configuration validated; only the readiness probe would change.")
        return

    def rollback(backup, changed_container):
        if json.loads((backup / "paths.json").read_text()) != {"compose": str(compose), "overlay": str(overlay)}:
            raise ValueError("Backup belongs to a different Compose installation")
        # Do not recreate the original sleep-as-PID-1 failure mode during rollback.
        if changed_container:
            subprocess.run([*command, "stop", "-t", "10", PROBE], check=True, timeout=30)
        copy_atomic(backup / OVERLAY, overlay)
        resolved(command)
        smoke()
        print("Original overlay restored. The readiness probe remains stopped after recreation attempts.")

    with open(str(compose) + ".lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        backup = args.backup.resolve()
        if args.action == "rollback":
            rollback(backup, True)
            return
        candidate = validate_candidate()
        smoke()
        backup.mkdir(mode=0o700)
        shutil.copy2(compose, backup / "compose.yaml")
        shutil.copy2(overlay, backup / OVERLAY)
        (backup / "paths.json").write_text(json.dumps({"compose": str(compose), "overlay": str(overlay)}))
        changed_container = False
        try:
            copy_atomic(source, overlay)
            if resolved(command) != candidate:
                raise RuntimeError("Installed Compose does not resolve to the validated candidate")
            changed_container = True
            subprocess.run([*command, "up", "-d", "--no-deps", "--force-recreate", "--no-build",
                            "--pull", "never", PROBE], check=True, timeout=60)
            wait_healthy(command)
            smoke()
        except BaseException:
            rollback(backup, changed_container)
            raise
        print("Only the readiness container was recreated. Readiness and both business checks passed.")


if __name__ == "__main__":
    main()

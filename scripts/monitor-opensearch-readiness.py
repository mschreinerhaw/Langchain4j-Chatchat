"""Sample the Docker host without starting or entering any container."""
import argparse
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone


def sample(container, proc=Path("/proc")):
    model = subprocess.check_output(["docker", "inspect", "--format",
        "{{json .State}}", container], text=True, timeout=10)
    state = json.loads(model)
    pid = state["Pid"]
    processes = zombies = child_zombies = 0
    for stat in proc.glob("[0-9]*/stat"):
        try:
            status, ppid, *_ = stat.read_text().rsplit(") ", 1)[1].split()
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
        processes += 1
        if status == "Z":
            zombies += 1
            child_zombies += int(pid > 0 and int(ppid) == pid)
    mappings = size = 0
    with (proc / "vmallocinfo").open() as entries:
        for line in entries:
            if "bpf_jit" in line:
                mappings += 1
                size += int(line.split()[1])
    # An exited container or a PID that has just disappeared has no readable PID 1.
    description = None
    if pid > 0:
        try:
            description = (proc / str(pid) / "comm").read_text().strip()
        except (FileNotFoundError, ProcessLookupError):
            pass
    return {"atUtc": datetime.now(timezone.utc).isoformat(), "status": state["Status"],
            "health": state.get("Health", {}).get("Status"), "pid1": description,
            "pid1ChildZombies": child_zombies, "hostZombies": zombies, "hostProcesses": processes,
            "bpfJitMappings": mappings, "bpfJitBytes": size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="runtime-os-source-production-opensearch-ready-1")
    parser.add_argument("--interval", type=int, default=30)
    parser.add_argument("--samples", type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.samples <= 120 or not 5 <= args.interval <= 600:
        parser.error("samples must be 1–120 and interval must be 5–600 seconds")
    for index in range(args.samples):
        if index:
            time.sleep(args.interval)
        print(json.dumps(sample(args.container)), flush=True)


if __name__ == "__main__":
    main()

"""Run the readiness regression against an explicitly selected disposable Linux Docker daemon."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from production_compose import HEALTH_TEST, OVERLAY, PROBE, PRODUCTION, WRAPPER, prepare_release_readiness, release_compose, resolved, validate_container, validate_readiness

ROOT = Path(__file__).resolve().parents[2]
FLAGS = ["--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true"]
IMAGE = "nginx:1.28-alpine"
FIXTURE = "python:3.11-alpine"
HTTP_FIXTURE = r"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl, time
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        state = Path('/fixture/state').read_text().strip()
        if state == 'slow':
            time.sleep(6)
            return
        self.send_response(int(state))
        self.end_headers()
    def log_message(self, *args):
        pass
for port, tls in ((9200, True), (9201, False)):
    server = ThreadingHTTPServer(('0.0.0.0', port), Handler)
    if tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain('/fixture/cert.pem', '/fixture/key.pem')
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
    if tls:
        import threading
        threading.Thread(target=server.serve_forever, daemon=True).start()
    else:
        server.serve_forever()
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True)
    parser.add_argument("--isolated-daemon", action="store_true", required=True,
                        help="Confirm this daemon runs on an isolated, non-production host.")
    parser.add_argument("--openssl", default="openssl")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--native-only", action="store_true", help="Run only the native healthcheck comparison.")
    args = parser.parse_args()
    docker = ["docker", "--context", args.context]
    prefix = "runtime-os-ready-p0-" + uuid.uuid4().hex[:10]
    owned = set()
    evidence = {"context": args.context, "checks": {}, "pressure": {}}

    def call(*argv, timeout=20, check=True, env=None):
        return subprocess.run([*docker, *argv], text=True, capture_output=True, timeout=timeout, check=check, env=env)

    def run(*argv):
        result = call(*argv, check=False)
        if result.returncode:
            raise RuntimeError(f"Docker {argv[:2]} returned {result.returncode}: {result.stderr.strip()}")
        return result.stdout.strip()

    def inspect(container):
        return json.loads(run("inspect", container))[0]

    def wait_for(fn, wanted, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = fn()
            if value == wanted:
                return
            time.sleep(.3)
        raise AssertionError(f"Timed out waiting for {wanted!r}; last value {value!r}")

    def create(label, image, argv, options=()):
        name = prefix + "-" + label
        owned.add(name)
        run("run", "-d", "--pull", "never", "--name", name, *FLAGS, *options, image, *argv)
        return name

    def remove(name):
        call("rm", "-f", name, check=False)
        owned.discard(name)

    def read_host():
        columns = run("exec", sampler, "sh", "-c", r"""
        awk '/bpf_jit/{n++; b+=$2} END{printf "%d %d\n", n, b}' /proc/vmallocinfo
        ps -A -o stat= | awk '/^Z/ {n++} END {print n+0,NR}'
        """).split()
        return dict(zip(("bpfJitMappings", "bpfJitBytes", "hostZombies", "hostProcesses"), map(int, columns)))

    def proc(name):
        lines = run("exec", name, "ps", "-A", "-o", "pid,ppid,stat,comm").splitlines()[1:]
        zombies = [line.split()[3] for line in lines if line.split()[2].startswith("Z")]
        return {"zombies": len(zombies), "zombieCommands": sorted(set(zombies)), "processes": len(lines)}

    def wget(name, target=None):
        cmd = HEALTH_TEST[1]
        if target:
            cmd = cmd.replace("host.docker.internal:9200", target)
        return call("exec", name, "sh", "-c", cmd, timeout=9, check=False).returncode == 0

    try:
        evidence["engine"] = run("info", "--format", "{{.ServerVersion}} kernel={{.KernelVersion}} cgroup={{.CgroupVersion}}")
        evidence["compose"] = run("compose", "version", "--short")
        for image in (IMAGE, FIXTURE):
            run("image", "inspect", image, "--format", "{{.Id}}")
        with tempfile.TemporaryDirectory(prefix=prefix) as tmp:
            path = Path(tmp)
            subprocess.run([args.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=readiness-fixture", "-keyout", str(path / "key.pem"),
                            "-out", str(path / "cert.pem")], capture_output=True, check=True, timeout=20)
            state = path / "state"
            state.write_text("200")
            (path / "fixture.py").write_text(HTTP_FIXTURE)
            run("network", "create", "--internal", prefix)
            fixture = create("endpoint", FIXTURE, ["python", "/fixture/fixture.py"], [
                "--network", prefix, "--network-alias", "host.docker.internal",
                "--mount", f"type=bind,src={path},dst=/fixture,readonly", "--pids-limit", "32"])
            sampler = create("sampler", IMAGE, ["infinity"], ["--network", "none", "--pid", "host",
                             "--pids-limit", "16", "--entrypoint", "sleep"])
            fixture_ip = inspect(fixture)["NetworkSettings"]["Networks"][prefix]["IPAddress"]
            if args.native_only:
                for init in (False, True):
                    name = create("native-" + str(init).lower(), IMAGE, ["infinity"],
                        ["--network", prefix, "--pids-limit", "16", "--entrypoint", "sleep",
                         "--health-cmd", HEALTH_TEST[1], "--health-interval", "1s", "--health-timeout", "4s",
                         *(["--init"] if init else [])])
                    initial = inspect(name)
                    pid = initial["State"]["Pid"]
                    host_before = read_host()
                    wait_for(lambda: len(inspect(name)["State"]["Health"]["Log"]) >= 4, True, seconds=8)
                    spec = inspect(name)
                    children = run("exec", sampler, "sh", "-c",
                       """ps -A -o pid,ppid,stat,comm | awk -v parent="$1" '$2==parent && $3~/^Z/{print $4}'""",
                       "sh", str(pid)).splitlines()
                    result = {"init": init, "dockerHealthRuns": len(spec["State"]["Health"]["Log"]),
                              "nativeHealth": spec["State"]["Health"]["Status"],
                              "childZombies": len(children), "zombieCommands": sorted(set(children)),
                              "before": host_before, "after": read_host()}
                    assert result["nativeHealth"] == "healthy"
                    if init:
                        assert not children, result
                    evidence["checks"]["native_" + str(init).lower()] = result
                    remove(name)
                print("Native healthcheck comparison passed:", args.output)
                return
            probe = create("semantics", IMAGE, ["infinity"], ["--network", prefix, "--init",
                            "--pids-limit", "256", "--entrypoint", "sleep"])
            wait_for(lambda: wget(probe), True)
            for code, wanted in [("200", True), ("401", True), ("503", False), ("slow", False), ("200", True)]:
                state.write_text(code)
                start = time.monotonic()
                got = wget(probe)
                evidence["checks"][code if code != "200" or code not in evidence["checks"] else "recovered"] = {
                    "usable": got, "seconds": round(time.monotonic() - start, 3)}
                assert got == wanted, code
            for target in (f"{fixture_ip}:9202", f"{fixture_ip}:9201"):
                assert not wget(probe, target), target
            evidence["checks"].update(connection_refused=True, tls_error=True)
            remove(probe)

            # Create exactly one child under PID 1; compare adoption without involving Docker exec reparenting.
            for init, expected in ((False, 1), (True, 0)):
                name = create("adoption-" + str(init).lower(), IMAGE, ["-c", 'sh -c "sleep 0.2 &"; exec sleep infinity'],
                              ["--network", "none", "--pids-limit", "16", "--entrypoint", "sh",
                               *(["--init"] if init else [])])
                time.sleep(1)
                observed = proc(name)["zombies"]
                assert observed == expected, (init, observed)
                evidence["checks"]["adoption_with_init" if init else "adoption_without_init"] = observed
                remove(name)

            # Test the exact source snapshot, generated artifact and release wrapper against Compose.
            release = path / "release"
            source = release / "source" / PRODUCTION
            source.mkdir(parents=True)
            (release / "private").mkdir()
            (release / "private/release.env").touch()
            hashes = {}
            for file in (WRAPPER, OVERLAY):
                shutil.copyfile(ROOT / PRODUCTION / file, source / file)
                hashes[(PRODUCTION / file).as_posix()] = hashlib.sha256((source / file).read_bytes()).hexdigest()
            (release / "source-manifest.json").write_text(json.dumps({"files": hashes}))
            (release / "compose.production.json").write_text(json.dumps({
                "services": {n: {"image":IMAGE, "entrypoint":["sleep","infinity"], "networks":["control"]}
                             for n in ("api","mcp")}, "networks":{"control":{"external":True,"name":prefix}}}))
            overrides = path / "empty.json"
            overrides.write_text("{}")
            env = dict(os.environ, DOCKER_CONTEXT=args.context, RUNTIME_OS_PRODUCTION_STORAGE_FILE=str(overrides),
                       RUNTIME_OS_NATIVE_DAEMON_FILE=str(overrides))
            old = os.environ.copy()
            os.environ.update(env)
            try:
                prepare_release_readiness(release)
                command, compose_env = release_compose(release)
                compose_env.pop("RUNTIME_OS_HOST_DEPENDENCIES_FILE")
                config = resolved(command, compose_env)
                validate_readiness(config)
                (release / OVERLAY).write_text("services: {}")
                try:
                    release_compose(release)
                except ValueError:
                    evidence["checks"]["tampered_release_rejected"] = True
                else:
                    raise AssertionError("Changed readiness overlay was accepted")
            finally:
                os.environ.clear()
                os.environ.update(old)

            # Use short timing only here to test Compose state transitions in seconds.
            gate = config["services"][PROBE]
            gate["extra_hosts"] = [f"host.docker.internal:{fixture_ip}"]
            gate["healthcheck"].update(interval="1s", retries=2, start_period="0s")
            for service in config["services"].values():
                service["pull_policy"] = "never"
            compose_file = path / "transition.json"
            compose_file.write_text(json.dumps(config))
            stack = ["compose", "-p", prefix, "-f", str(compose_file)]
            try:
                state.write_text("503")
                run(*stack, "create", "api", "mcp")
                run(*stack, "start", PROBE)
                identifier = run(*stack, "ps", "-aq", PROBE)
                health = lambda: inspect(identifier)["State"]["Health"]["Status"]
                wait_for(health, "unhealthy")
                for name in ("api", "mcp"):
                    assert inspect(run(*stack, "ps", "-aq", name))["State"]["Status"] == "created"
                blocked = call(*stack, "up", "-d", "api", "mcp", check=False)
                assert blocked.returncode and "unhealthy" in blocked.stderr.lower(), blocked.stderr
                state.write_text("401")
                wait_for(health, "healthy")
                run(*stack, "up", "-d", "api", "mcp")
                dependent_ids = [run(*stack, "ps", "-q", name) for name in ("api", "mcp")]
                assert all(dependent_ids) and health() == "healthy"
                evidence["checks"]["isolated_dependency_gate"] = True
                state.write_text("slow")
                wait_for(health, "unhealthy")
                logs = inspect(identifier)["State"]["Health"]["Log"]
                assert any("timeout" in item["Output"].lower() for item in logs), logs
                evidence["checks"]["native_health_timeout"] = True
                assert all(inspect(i)["State"]["Running"] for i in dependent_ids)
                state.write_text("200")
                wait_for(health, "healthy")
                run(*stack, "restart", PROBE)
                wait_for(health, "healthy")
                run(*stack, "up", "-d", "--no-deps", "--force-recreate", PROBE)
                replacement = run(*stack, "ps", "-q", PROBE)
                assert replacement != identifier
                wait_for(lambda: inspect(replacement)["State"]["Health"]["Status"], "healthy")
                assert [run(*stack, "ps", "-q", n) for n in ("api", "mcp")] == dependent_ids
                evidence["checks"]["interruption_restart_recreate"] = True
            finally:
                call(*stack, "down", "--timeout", "2", check=False)

            # Four identical serial workloads. The controlled cap is present even on the original comparison.
            state.write_text("200")
            for label, init, tuning, cap in [("original", False, False, 32), ("init", True, False, 32),
                    ("init_interval", True, True, 32), ("full", True, True, 256)]:
                hc = ["--health-cmd", HEALTH_TEST[1], "--health-timeout", "4s", "--health-interval",
                      "30s" if tuning else "5s", "--health-retries", "5" if tuning else "30"]
                if tuning:
                    hc += ["--health-start-period", "30s", "--health-start-interval", "5s"]
                name = create(label, IMAGE, ["infinity"], ["--network", prefix, "--pids-limit", str(cap),
                    "--entrypoint", "sleep", "--restart", "unless-stopped", *(["--init"] if init else []), *hc])
                before = read_host()
                records = []
                elapsed = 0.0
                success = 0
                attempted = 0
                stopped = False
                for batch in range(200):
                    start = time.monotonic()
                    # One bounded exec per batch, equivalent HTTPS subprocess workload in every variant.
                    cmd = 'i=0; successes=0; while [ "$i" -lt 5 ]; do (' + HEALTH_TEST[1] + (
                        ') && successes=$((successes+1)); i=$((i+1)); done; echo "$successes"')
                    success += int(run("exec", name, "sh", "-c", cmd))
                    attempted += 5
                    elapsed += time.monotonic() - start
                    counts = proc(name)
                    exceeded = counts["zombies"] >= 8 or counts["processes"] >= 20
                    if batch % 50 == 0 or exceeded:
                        counts.update(read_host())
                        bpf_growth = counts["bpfJitMappings"] - before["bpfJitMappings"]
                        memory_growth = counts["bpfJitBytes"] - before["bpfJitBytes"]
                        assert bpf_growth < 128 and memory_growth < 16 * 1024 * 1024, label
                    records.append(counts)
                    if exceeded:
                        assert label == "original", (label, counts)
                        stopped = True
                        break
                after = read_host()
                if stopped:
                    evidence["pressure"][label] = {"checks": attempted, "successes": success,
                        "seconds": round(elapsed, 3), "pid1": "sleep", "before": before, "after": after,
                        "zombiePeak": max(r["zombies"] for r in records), "thresholdStopped": True,
                        "samples": [r for r in records if "bpfJitMappings" in r]}
                    remove(name)
                    evidence["pressure"][label]["afterRemoval"] = read_host()
                    args.output.write_text(json.dumps(evidence, indent=2))
                    continue
                for command, expected in [("true", 0), ("false", 1), ("kill -9 $$", 137),
                        ("echo x | grep -qx x", 0), ("timeout 0.1 sleep 3", 143),
                        ("sleep 0.1 & wait", 0)]:
                    result = call("exec", name, "sh", "-c", command, check=False)
                    assert result.returncode in ({124, 137, 143} if command.startswith("timeout ") else {expected}), command
                pid1 = run("exec", name, "cat", "/proc/1/comm")
                assert (pid1 == "sleep") == (not init), pid1
                spec = inspect(name)
                assert spec["State"]["Health"]["Status"] == "healthy"
                if label == "full":
                    validate_container(spec)
                assert success == 1000 and proc(name)["zombies"] == 0
                evidence["pressure"][label] = {"checks": 1000, "successes": success, "seconds": round(elapsed, 3),
                    "pid1": pid1, "before": before, "after": read_host(), "zombiePeak": max(r["zombies"] for r in records),
                    "samples": [r for r in records if "bpfJitMappings" in r]}
                remove(name)
                args.output.write_text(json.dumps(evidence, indent=2))
            for _ in range(20):
                run("run", "--rm", "--pull", "never", "--network", "none", *FLAGS, "--pids-limit", "16",
                    "--entrypoint", "sh", IMAGE, "-c", r'grep -Eq "^Seccomp:[[:space:]]+2$" /proc/self/status')
            evidence["checks"]["default_seccomp_creates"] = 20
    finally:
        for name in sorted(owned):
            remove(name)
        call("network", "rm", prefix, check=False)
        args.output.write_text(json.dumps(evidence, indent=2))
    print("Readiness regression passed:", args.output)


if __name__ == "__main__":
    main()

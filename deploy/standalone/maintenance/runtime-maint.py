#!/usr/bin/env python3
"""Maintain an installed Runtime OS Compose project using its existing files."""

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request


FOUNDATION = ("postgresql", "temporal-db", "redis", "opensearch", "silo", "executor-daemon")
CONTROL = ("temporal", "mcp", "api")


class Installation:
    def __init__(self, args):
        self.root = Path(args.root).resolve(strict=True)
        self.spec = self.root / "compose.json"
        self.original = self.spec.read_bytes()
        config = json.loads(self.original)
        project = config.get("name", "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", project):
            raise RuntimeError("compose.json 中缺少有效的项目名")
        self.project = project
        self.docker = args.docker_bin
        self.env = dict(os.environ, DOCKER_HOST="unix:///var/run/docker.sock")
        self.env.pop("DOCKER_CONTEXT", None)
        if args.config_dir:
            docker_config = Path(args.config_dir).resolve(strict=True) / "docker-config"
            if not docker_config.is_dir():
                raise RuntimeError("指定的 install-config 下缺少 docker-config")
            self.env["DOCKER_CONFIG"] = str(docker_config)
        candidates = [(self.docker, "compose")]
        if shutil.which("docker-compose"):
            candidates.append(("docker-compose",))
        for command in candidates:
            result = subprocess.run([*command, "version", "--short"], env=self.env,
                                    capture_output=True, text=True, timeout=15)
            version = re.match(r"v?(\d+)\.", result.stdout.strip())
            if result.returncode == 0 and version and int(version[1]) >= 2:
                self.command = (*command, "-p", project, "--project-directory", str(self.root),
                                "-f", str(self.spec))
                break
        else:
            raise RuntimeError("没有可用的 Compose v2。可用 --config-dir 指定原交付介质的 install-config")
        self.services = set(self.compose("config", "--services", capture=True).splitlines())
        self.attached = [name for name, service in config["services"].items()
                         if name in self.services and service.get("network_mode") == "service:api"]
        self.timeout = args.timeout
        print("项目 {} · 安装目录 {}".format(project, self.root), flush=True)

    def run(self, *command, capture=False):
        result = subprocess.run(command, env=self.env, check=True,
                                stdout=subprocess.PIPE if capture else None, text=True)
        return result.stdout.strip() if capture else None

    def compose(self, *command, capture=False):
        return self.run(*self.command, *command, capture=capture)

    def validate_services(self, names):
        unknown = sorted(set(names) - self.services)
        if unknown:
            raise RuntimeError("Compose 服务不存在或未启用: " + ", ".join(unknown))
        return list(dict.fromkeys(names))

    def up(self, names, *, recreate=False, all_services=False):
        if not names and not all_services:
            return
        options = ["--force-recreate" if recreate else "--no-recreate"]
        if not all_services:
            options.append("--no-deps")
        self.compose("up", "-d", "--no-build", "--pull", "never",
                     "--wait", "--wait-timeout", str(self.timeout), *options, *names)

    def running(self, name):
        return bool(self.compose("ps", "-q", name, capture=True))

    def exists(self, name):
        return bool(self.compose("ps", "-a", "-q", name, capture=True))

    def stop(self, names):
        if names:
            self.compose("stop", "-t", "30", *names)

    def restart(self, name):
        if not self.exists(name):
            raise RuntimeError("找不到已有容器，请使用 start 或 recover: " + name)
        attached = [service for service in self.attached
                    if name == "api" and self.running(service)]
        self.stop(attached)
        self.compose("restart", "-t", "30", name)
        self.up([name])
        self.up(attached)

    def start(self, name):
        missing_api = name == "api" and not self.exists(name)
        detached = [service for service in self.attached if missing_api and self.running(service)]
        self.stop(detached)
        self.up([name])
        self.up(detached, recreate=True)

    def recover(self):
        # If the API container was removed, existing dependants may still point
        # at its old network namespace. Reattach only that known group.
        missing_api = "api" in self.services and not self.exists("api")
        detached = [service for service in self.attached if missing_api and self.exists(service)]
        self.up([service for service in FOUNDATION if service in self.services])
        self.stop(detached)
        for service in CONTROL:
            if service in self.services:
                self.up([service])
        self.up(detached, recreate=True)
        self.up([], all_services=True)
        self.compose("ps", "-a")

    def gateway_url(self, port):
        ports = self.compose("port", "gateway", str(port), capture=True).splitlines()
        if not ports:
            raise RuntimeError("未找到 gateway 的发布端口 {}".format(port))
        address = ports[0]
        host, _, published = address.rpartition(":")
        if host in ("", "0.0.0.0"):
            host = "127.0.0.1"
        elif host in ("::", "[::]"):
            host = "[::1]"
        elif ":" in host and not host.startswith("["):
            host = "[" + host + "]"
        return "http://{}:{}".format(host, int(published))

    def verify(self, app_url, mcp_url):
        self.compose("ps", "-a")
        for name in ("gateway", "api", "mcp", "executor-daemon"):
            if name not in self.services:
                continue
            cid = self.compose("ps", "-q", name, capture=True)
            if not cid:
                raise RuntimeError("服务未运行: " + name)
            state = json.loads(self.run(self.docker, "inspect", "--format", "{{json .State}}",
                                        cid, capture=True))
            health = (state.get("Health") or {}).get("Status")
            if not state["Running"] or health not in (None, "healthy"):
                raise RuntimeError("服务未就绪: {} ({})".format(name, health or state["Status"]))
        app = app_url or self.gateway_url(8080)
        mcp = mcp_url or self.gateway_url(8090)
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for base, path in ((app, "/index.html"), (app, "/monitor/"), (mcp, "/admin/")):
            url = base.rstrip("/") + path
            with client.open(url, timeout=15) as response:
                if response.status != 200 or "text/html" not in response.headers.get("Content-Type", ""):
                    raise RuntimeError("入口未返回预期页面: " + url)
                print("HTTP 200 " + url)
        print("基础服务及入口检查通过。可使用现场账号登录并提交一项小分析任务做业务验收。")


def main():
    parser = argparse.ArgumentParser(description="Runtime OS 已安装实例维护")
    parser.add_argument("--root", default="/opt/ros21", help="现有安装目录")
    parser.add_argument("--config-dir", help="原离线交付介质中的 install-config 目录")
    parser.add_argument("--docker-bin", default="docker", help="Docker CLI 路径")
    parser.add_argument("--timeout", default=360, type=int, help="单次 Compose 等待秒数")
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("services", help="列出服务名")
    status = actions.add_parser("status", help="查看容器和健康状态")
    status.add_argument("services", nargs="*")
    logs = actions.add_parser("logs", help="查看指定服务日志")
    logs.add_argument("services", nargs="+")
    logs.add_argument("--tail", type=int, default=200)
    logs.add_argument("--since", default="15m")
    logs.add_argument("--follow", action="store_true")
    for name in ("start", "stop", "restart"):
        service = actions.add_parser(name, help="{} 单个现有 Compose 服务".format(name))
        service.add_argument("service")
    actions.add_parser("recover", help="按顺序恢复现有 Compose 项目")
    verify = actions.add_parser("verify", help="检查容器和发布入口")
    verify.add_argument("--app-url", help="指定应用基础 URL")
    verify.add_argument("--mcp-url", help="指定 MCP 基础 URL")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须为正整数")
    try:
        site = Installation(args)
        if args.action == "services":
            print("\n".join(sorted(site.services)))
        elif args.action == "status":
            site.compose("ps", "-a", *site.validate_services(args.services))
        elif args.action == "logs":
            names = site.validate_services(args.services)
            follow = ["--follow"] if args.follow else []
            site.compose("logs", "--tail", str(args.tail), "--since", args.since, *follow, *names)
        elif args.action == "verify":
            site.verify(args.app_url, args.mcp_url)
        else:
            # Use the same lock as the offline analysis installer.
            old_umask = os.umask(0o077)
            try:
                with (site.root / ".runtime-native-model-fix.lock").open("a") as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    if site.spec.read_bytes() != site.original:
                        raise RuntimeError("Compose 清单在读取后已变更，请重试")
                    if args.action == "recover":
                        site.recover()
                        return
                    name, = site.validate_services([args.service])
                    if args.action == "stop":
                        if name == "api":
                            site.stop([service for service in site.attached if site.running(service)])
                        site.stop([name])
                    elif args.action == "start":
                        site.start(name)
                    else:
                        site.restart(name)
                    site.compose("ps", "-a", name)
            finally:
                os.umask(old_umask)
    except BlockingIOError:
        parser.exit(1, "已有维护或离线补丁操作持有锁，请稍后重试。\n")
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, "维护操作失败: {}\n".format(error))
    except KeyboardInterrupt:
        parser.exit(130)


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v docker >/dev/null
test -x /usr/bin/python3
command -v systemctl >/dev/null
test "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}/{{index .Config.Labels "com.docker.compose.service"}}' runtime-os-app-stage-coding-agent-1)" = 'runtime-os-app-stage/coding-agent'
install -d -m 0755 /opt/runtime-os-ops
install -m 0755 "$source_dir/coding-agent-watchdog.py" /opt/runtime-os-ops/coding-agent-watchdog.py
install -m 0644 "$source_dir/runtime-os-coding-agent-watchdog.service" /etc/systemd/system/runtime-os-coding-agent-watchdog.service
install -m 0644 "$source_dir/runtime-os-coding-agent-watchdog.timer" /etc/systemd/system/runtime-os-coding-agent-watchdog.timer
systemd-analyze verify /etc/systemd/system/runtime-os-coding-agent-watchdog.service /etc/systemd/system/runtime-os-coding-agent-watchdog.timer
systemctl daemon-reload
# Enable boot recovery without restarting Docker or touching unrelated containers.
systemctl enable docker.service runtime-os-coding-agent-watchdog.timer
systemctl start runtime-os-coding-agent-watchdog.timer
systemctl is-enabled docker.service runtime-os-coding-agent-watchdog.timer
systemctl is-active runtime-os-coding-agent-watchdog.timer

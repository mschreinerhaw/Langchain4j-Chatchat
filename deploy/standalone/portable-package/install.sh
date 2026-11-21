#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'HELP'
用法: bash install.sh {check|load|prepare|deploy|install|status|monitor-repair|password}
  check     检查 Linux、Docker、Compose、内存、端口和宿主机 Python 版本
  load      校验并加载离线镜像，检查容器内 Python 语法
  prepare   在空目录生成 Compose 配置、证书和初始密码
  deploy    初始化并启动服务，也可在修复错误后续跑
  install   按顺序执行 check、load、prepare 和 deploy
  status    查看容器和验收报告
  monitor-repair  修复已有环境的监控入口，并验证首页和静态资源
  password  读取本次安装生成的初始管理员密码
HELP
}

die() { printf '%s\n' "$*" >&2; exit 1; }

action=${1:-help}
case "$action" in
    help|-h|--help) usage; exit 0 ;;
    check|load|prepare|deploy|install|status|monitor-repair|password) ;;
    *) usage >&2; exit 2 ;;
esac

CONFIG=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
IMAGES="$CONFIG/../offline-images"
source "$CONFIG/manual-install.env"
INSTALLER=${INSTALLER_IMAGE:-runtime-os-portable-installer:20261121-skill-r3}
export DOCKER_CONFIG="$CONFIG/docker-config" DOCKER_HOST=unix:///var/run/docker.sock
unset DOCKER_CONTEXT

[[ $EUID == 0 ]] || die '请在目标 Linux 主机用 root 执行。'
[[ $ROOT == /* && $ROOT != / ]] || die 'ROOT 必须是专用的绝对安装目录。'
[[ $PROJECT =~ ^[a-z][a-z0-9-]{2,39}$ ]] || die 'PROJECT 格式无效。'
for port in "$HTTP_PORT" "$MCP_PORT"; do
    [[ $port =~ ^[1-9][0-9]{3,4}$ ]] && ((port >= 1024 && port <= 65535)) || die "端口无效：$port"
done
[[ $HTTP_PORT != "$MCP_PORT" ]] || die '应用与 MCP 端口必须不同。'

if [[ -z ${DOCKER_BIN:-} ]]; then
    DOCKER_BIN=$(type -P docker || true)
    if [[ -z $DOCKER_BIN ]]; then
        for candidate in /usr/bin/docker /usr/local/bin/docker /bin/docker /usr/sbin/docker /usr/local/sbin/docker /snap/bin/docker; do
            if [[ -x $candidate ]]; then
                DOCKER_BIN=$candidate
                break
            fi
        done
    fi
fi
[[ -n $DOCKER_BIN ]] || die '找不到 Docker CLI。请在普通用户会话执行 command -v docker，将结果作为绝对路径写入 manual-install.env 的 DOCKER_BIN。若没有结果，请先安装 Docker CLI 和 Engine。'
[[ $DOCKER_BIN == /* && -f $DOCKER_BIN && -x $DOCKER_BIN ]] || die "DOCKER_BIN ????????????$DOCKER_BIN"

docker_cli() { "$DOCKER_BIN" "$@"; }

compose() { docker_cli compose --project-directory "$ROOT" -f "$ROOT/compose.json" "$@"; }

installer() {
    docker_cli run --rm --pull=never --network host --name "$PROJECT-installer" \
        -e HOSTNAME="$PROJECT-installer" -e PROJECT="$PROJECT" \
        -e HTTP_PORT="$HTTP_PORT" -e MCP_PORT="$MCP_PORT" \
        -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT:/install" "$@"
}

check_host() {
    local kernel arch memory storage engine tool listeners docker_info install_parent
    for tool in sha256sum ss sysctl df; do
        command -v "$tool" >/dev/null || die "缺少命令：$tool"
    done
    [[ -S /var/run/docker.sock ]] || die '本机 Docker daemon 未启动。'
    docker_info=$(docker_cli info --format '{{.OSType}} {{.Architecture}} {{.MemTotal}} {{.ServerVersion}} {{.DockerRootDir}}')
    read -r kernel arch memory engine storage <<< "$docker_info"
    [[ $kernel == linux && ( $arch == x86_64 || $arch == amd64 ) ]] || die '该离线包需要 Linux amd64 Docker。'
    printf 'Docker %s %s/%s，内存 %s 字节\n' "$engine" "$kernel" "$arch" "$memory"
    docker_cli compose version
    for tool in python python2 python3; do
        if command -v "$tool" >/dev/null; then
            printf '宿主机 %s：' "$tool"
            "$tool" --version 2>&1 || true
        fi
    done
    printf '%s\n' '宿主机 Python 版本仅供参考。安装程序在镜像内使用 Python 3。'
    ((memory >= 12 * 1024 ** 3)) || die 'Docker 可用内存少于 12 GiB。'
    install_parent=$(dirname -- "$ROOT")
    while [[ ! -d $install_parent ]]; do install_parent=$(dirname -- "$install_parent"); done
    df -h -- "$storage" "$install_parent"
    sysctl vm.max_map_count
    if [[ ! -f $ROOT/compose.json ]]; then
        listeners=$(ss -H -ltn "sport = :$HTTP_PORT or sport = :$MCP_PORT")
        [[ -z $listeners ]] || die "新安装需要空闲端口 $HTTP_PORT 和 $MCP_PORT。"
    fi
}

verify_images() {
    local ref expected actual count=0
    while IFS=$'\t' read -r ref expected; do
        actual=$(docker_cli image inspect --format '{{.Id}}' "$ref") || die "镜像未加载：$ref"
        [[ " $expected " == *" $actual "* ]] || die "镜像摘要不匹配：$ref ($actual)"
        count=$((count + 1))
    done < "$IMAGES/images.tsv"
    ((count > 0)) || die '镜像清单为空。'
}

load_images() {
    (
        cd "$IMAGES"
        sha256sum -c SHA256SUMS
        while read -r digest archive; do
            [[ $digest =~ ^[[:xdigit:]]{64}$ && $archive =~ ^[a-zA-Z0-9._-]+\.tar(\.gz)?$ ]] || die '归档清单记录无效。'
            docker_cli image load -i "$archive"
        done < SHA256SUMS
    )
    verify_images
    docker_cli run --rm --pull=never --network none --entrypoint python3 "$INSTALLER" -c '
from pathlib import Path
import sys
assert sys.version_info >= (3, 10), sys.version
for path in (Path("/opt/product/installer.py"), Path("/opt/ops-config/verify.py")):
    compile(path.read_bytes(), str(path), "exec")
print("Installer Python", sys.version.split()[0], "syntax OK")'
}

verify_configuration() {
    local current_project
    [[ -s $ROOT/compose.json && -s $ROOT/installation.json ]] || die '安装目录缺少配置，请先执行 prepare。'
    current_project=$(compose config | sed -n 's/^name: //p')
    [[ $current_project == "$PROJECT" ]] || die 'Compose 项目与 manual-install.env 不一致。'
    compose --profile '*' config --quiet
}

prepare() {
    verify_images
    if [[ -s $ROOT/compose.json ]]; then
        verify_configuration
        return
    fi
    [[ ! -L $ROOT ]] || die '请使用实际安装目录。'
    install -d -m 0700 -- "$ROOT"
    [[ -z $(find "$ROOT" -mindepth 1 -maxdepth 1 -print -quit) ]] || die '生成配置需要空安装目录。'
    installer --entrypoint python3 "$INSTALLER" -c '
import runpy
app = runpy.run_path("/opt/product/installer.py")["Installation"]()
app.prepare()
app.render()'
    verify_configuration
}

patch_monitor_route() {
    local conf="$ROOT/bootstrap/public-gateway.conf" tmp
    local legacy='location = /monitor/ { alias /srv/coding-monitor/index.html; default_type text/html; }'
    if grep -Fq "$legacy" "$conf"; then
        tmp=$(mktemp "$conf.XXXXXX")
        sed -e 's@location = /monitor/ { alias /srv/coding-monitor/index.html; default_type text/html; }@location = /monitor/ { rewrite ^ /monitor/index.html last; }\n    location = /monitor/index.html { alias /srv/coding-monitor/index.html; default_type text/html; }@' "$conf" > "$tmp"
        chmod --reference="$conf" "$tmp"
        mv -f -- "$tmp" "$conf"
    fi
    grep -Fq 'location = /monitor/index.html {' "$conf" || die '监控网关路由缺失。'
}

verify_monitor() {
    compose exec -T gateway sh -ec '
        base=http://127.0.0.1:8080/monitor
        test "$(curl -fsSL "$base" | grep -c "<title>Coding")" -ge 1
        case "$(curl -fsS -o /dev/null -w "%{content_type}" "$base/app.js")" in *javascript*) ;; *) exit 1;; esac
        case "$(curl -fsS -o /dev/null -w "%{content_type}" "$base/style.css")" in text/css*) ;; *) exit 1;; esac
    ' || die '监控首页、JavaScript 或 CSS 验证失败。'
    printf '%s\n' '监控首页及静态资源验证通过。'
}

deploy() {
    verify_images
    verify_configuration
    local max_maps
    max_maps=$(sysctl -n vm.max_map_count)
    if ((max_maps < 262144)); then sysctl -w vm.max_map_count=262144; fi
    compose up -d --wait --wait-timeout 240 --pull never \
        postgresql temporal-db redis opensearch silo executor-daemon
    installer "$INSTALLER" bootstrap-compose
    patch_monitor_route
    if [[ -n $(compose ps --all -q gateway) ]]; then
        compose up -d --no-deps --force-recreate --wait --wait-timeout 90 --pull never gateway
    fi
    compose up -d --wait --wait-timeout 360 --pull never
    verify_monitor
    installer "$INSTALLER" finalize-compose
    compose ps
    cat -- "$ROOT/verification.json"
}

case "$action" in
    check) check_host ;;
    load) load_images ;;
    prepare) prepare ;;
    deploy) deploy ;;
    install) check_host; load_images; prepare; deploy ;;
    status) verify_configuration; compose ps; [[ ! -f $ROOT/verification.json ]] || cat -- "$ROOT/verification.json" ;;
    monitor-repair)
        verify_configuration
        patch_monitor_route
        compose up -d --no-deps --force-recreate --wait --wait-timeout 90 --pull never gateway
        verify_monitor
        ;;
    password)
        [[ -s $ROOT/installation.json ]] || die '安装记录不存在。'
        docker_cli run --rm --pull=never --network none --entrypoint python3 \
            --mount "type=bind,source=$ROOT,target=/install,readonly" "$INSTALLER" \
            -c 'import json; print(json.load(open("/install/installation.json"))["adminPassword"])'
        ;;
esac

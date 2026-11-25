#!/usr/bin/env bash
set -euo pipefail
umask 077
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
source "$HERE/manual-install.env"
ROOT=$GATEWAY_ROOT
ENV_FILE=$ROOT/.env
action=${1:-install}

fail() { printf '%s\n' "$*" >&2; exit 1; }
setting() { sed -n "s/^$1=//p" "$ENV_FILE"; }
select_compose() {
    command -v docker >/dev/null || fail '安装需要 Docker CLI。'
    if docker compose version >/dev/null 2>&1; then
        compose_command=(docker compose)
        return
    fi
    local bundled="$HERE/docker-config/cli-plugins/docker-compose"
    [[ -f $bundled ]] || fail 'Docker Compose 不可用，请解压包含 Compose 插件的网关安装包。'
    if [[ ! -x $bundled ]]; then chmod u+x -- "$bundled" || fail '包内 Compose 无法设为可执行文件。'; fi
    "$bundled" version >/dev/null || fail '包内 Compose 无法运行，请确认宿主机为 Linux amd64。'
    compose_command=("$bundled")
    printf '%s\n' '使用安装包内的 Docker Compose。'
}
compose() {
    local files=(-f "$ROOT/docker-compose.yml")
    if [[ $(setting ENABLE_PACKAGE_HTTP) == true ]]; then
        local http_override="$ROOT/docker-compose.http.yml"
        if [[ ! -f $http_override ]]; then http_override="$ROOT/docker-compose.http-test.yml"; fi
        files+=(-f "$http_override")
    fi
    "${compose_command[@]}" --project-name "$GATEWAY_PROJECT" --project-directory "$ROOT" --env-file "$ENV_FILE" "${files[@]}" "$@"
}
install_http_override() {
    if [[ ! -f $ROOT/docker-compose.http.yml ]]; then
        install -m 0600 "$HERE/docker-compose.http.yml" "$ROOT/docker-compose.http.yml"
    fi
}
start_gateway() { compose up -d --no-build --pull never --wait --wait-timeout 90; }
addresses() {
    local host
    host=$(setting TLS_SERVER_NAMES)
    host=${host%%,*}
    [[ $host != *:* ]] || host="[$host]"
    printf '管理台 https://%s:%s\n模型 API https://%s:%s/v1\n' "$host" "$(setting ADMIN_PUBLIC_PORT)" "$host" "$(setting HTTPS_PUBLIC_PORT)"
    if [[ $(setting ENABLE_PACKAGE_HTTP) == true ]]; then
        printf '模型 API http://%s:%s/v1（绑定 %s）\n' "$host" "$(setting HTTP_PUBLIC_PORT)" "${1:-127.0.0.1}"
    fi
}

case "$action" in
    install)
        command -v openssl >/dev/null || fail '安装需要 openssl。'
        select_compose
        (cd "$HERE/../offline-images" && sha256sum -c SHA256SUMS)
        IFS=$'\t' read -r image image_id < "$HERE/../offline-images/images.tsv"
        [[ -n $image && $image_id =~ ^sha256:[a-f0-9]{64}$ ]] || fail '镜像清单无效。'
        existing=$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null || true)
        [[ -z $existing || $existing == "$image_id" ]] || fail '目标镜像标签与交付清单冲突。'
        if [[ -e $ROOT && ! -f $ROOT/.gateway-package ]]; then
            [[ -d $ROOT && -z $(ls -A -- "$ROOT") ]] || fail '安装目录已有内容，请选择空目录或使用原部署入口。'
        fi
        if [[ -f $ROOT/.gateway-package ]]; then
            [[ $(cat "$ROOT/.gateway-package") == "$GATEWAY_PROJECT $image" ]] || fail '已有安装的项目或版本不同。'
        fi
        if [[ ! -f $ENV_FILE ]]; then
            host=${2:-${GATEWAY_HOST:-$(hostname)}}
            [[ $host =~ ^[[:alnum:].:-]+$ ]] || fail '请传入一个有效的证书域名或 IP 地址。'
            admin_password=$(openssl rand -hex 20)
            api_key=$(openssl rand -hex 32)
            encryption_key=$(openssl rand -hex 32)
            install -d -m 0700 -- "$ROOT"
            printf '%s %s\n' "$GATEWAY_PROJECT" "$image" > "$ROOT/.gateway-package"
            draft=$(mktemp "$ROOT/.env.XXXXXX")
            trap 'rm -f -- "$draft"' EXIT
            {
                printf 'ADMIN_PASSWORD=%s\n' "$admin_password"
                printf 'GATEWAY_API_KEY=%s\n' "$api_key"
                printf 'CONFIG_ENCRYPTION_KEY=%s\n' "$encryption_key"
                printf 'GATEWAY_IMAGE=%s\nTLS_SERVER_NAMES=%s\nTLS_SELF_SIGNED=true\n' "$image" "$host"
                printf 'API_BIND=%s\nADMIN_BIND=%s\n' "$GATEWAY_BIND" "$GATEWAY_BIND"
                printf 'HTTPS_PUBLIC_PORT=%s\nADMIN_PUBLIC_PORT=%s\n' "$GATEWAY_HTTPS_PORT" "$GATEWAY_ADMIN_PORT"
                printf 'ENABLE_PACKAGE_HTTP=%s\nHTTP_PUBLIC_PORT=%s\nHTTP_BIND=%s\n' "$GATEWAY_ENABLE_HTTP" "$GATEWAY_HTTP_PORT" "$GATEWAY_HTTP_BIND"
            } > "$draft"
            mv -- "$draft" "$ENV_FILE"
            trap - EXIT
        fi
        [[ $(setting GATEWAY_IMAGE) == "$image" ]] || fail '已有配置镜像与交付清单不同。'
        # The non-root container needs to traverse the empty self-signed bootstrap mount.
        if [[ ! -d $ROOT/tls-bootstrap ]]; then install -d -m 0755 -- "$ROOT/tls-bootstrap"; fi
        if [[ ! -f $ROOT/docker-compose.yml ]]; then install -m 0600 "$HERE/docker-compose.yml" "$ROOT/docker-compose.yml"; fi
        install_http_override
        if [[ -z $existing ]]; then
            docker image load --input "$HERE/../offline-images/llm-compatibility-gateway.tar"
        fi
        [[ $(docker image inspect --format '{{.Id}} {{.Os}}/{{.Architecture}}' "$image") == "$image_id linux/amd64" ]] || fail '镜像摘要或架构不符。'
        start_gateway
        addresses "$(setting HTTP_BIND)"
        printf '查看初始管理员密码 bash "%s/install.sh" password\n' "$HERE"
        ;;
    enable-http)
        [[ -f $ENV_FILE && -f $ROOT/.gateway-package ]] || fail '请指定由本安装包管理的网关目录。'
        [[ $(cat "$ROOT/.gateway-package") == "$GATEWAY_PROJECT $(setting GATEWAY_IMAGE)" ]] || fail '安装项目或镜像配置不一致。'
        select_compose
        install_http_override
        backup=$(mktemp "$ROOT/.env.http-backup.XXXXXX")
        draft=$(mktemp "$ROOT/.env.http.XXXXXX")
        trap 'rm -f -- "$draft"' EXIT
        cp -p -- "$ENV_FILE" "$backup"
        sed '/^ENABLE_PACKAGE_HTTP=/d; /^HTTP_BIND=/d' "$ENV_FILE" > "$draft"
        printf '\nENABLE_PACKAGE_HTTP=true\nHTTP_BIND=%s\n' "$GATEWAY_HTTP_BIND" >> "$draft"
        mv -- "$draft" "$ENV_FILE"
        if ! start_gateway; then
            mv -- "$backup" "$ENV_FILE"
            start_gateway || fail 'HTTP 启用失败，配置已恢复，请检查原网关容器状态。'
            fail 'HTTP 启用失败，原配置已恢复。'
        fi
        rm -f -- "$backup"
        addresses "$(setting HTTP_BIND)"
        ;;
    status) [[ -f $ENV_FILE ]] || fail '尚未安装。'; select_compose; compose ps ;;
    password) [[ -r $ENV_FILE ]] || fail '无法读取安装配置。'; setting ADMIN_PASSWORD ;;
    *) fail '用法 bash install.sh {install [域名或IP]|enable-http|status|password}' ;;
esac

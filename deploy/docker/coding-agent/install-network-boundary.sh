#!/usr/bin/env bash
# Dedicated Coding callback gateway and source-scoped Docker egress policy.
set -euo pipefail
test "$(id -u)" = 0 || { echo 'Run as root' >&2; exit 1; }
nginx_bin=${NGINX_BIN:-/opt/bigdata/nginx/sbin/nginx}
upstream_port=${CODING_JAVA_PORT:-8080}
[[ "$upstream_port" =~ ^[0-9]+$ ]] && ((upstream_port > 0 && upstream_port < 65536))
test -x "$nginx_bin"
command -v iptables >/dev/null
gateway_home=/opt/runtime-coding-gateway
install -d -m 755 "$gateway_home"
cat > "$gateway_home/nginx.conf" <<EOF
worker_processes 1;
pid $gateway_home/nginx.pid;
error_log $gateway_home/error.log warn;
events { worker_connections 128; }
http {
    access_log off;
    client_body_temp_path $gateway_home/body;
    proxy_temp_path $gateway_home/proxy;
    server {
        listen 172.30.91.1:18081;
        server_name _;
        client_max_body_size 1m;
        allow 172.30.91.10;
        allow 172.30.91.11;
        deny all;
        location ~ ^/internal/coding/cline/(reserve-model|model|settle-model|execute-python|get-python|cancel-python|read-metadata|read-artifact|event|finish)\$ {
            if (\$request_method != POST) { return 405; }
            proxy_pass http://127.0.0.1:$upstream_port;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_connect_timeout 3s;
            proxy_read_timeout 120s;
            proxy_next_upstream off;
        }
        location / { return 404; }
    }
}
EOF
"$nginx_bin" -t -p "$gateway_home/" -c nginx.conf
cat > "$gateway_home/apply-firewall.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
iptables -w -N CODING_HOST 2>/dev/null || true
iptables -w -N CODING_EGRESS 2>/dev/null || true
iptables -w -F CODING_HOST
iptables -w -A CODING_HOST -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
iptables -w -A CODING_HOST -d 172.30.91.1 -p tcp --dport 18081 -j ACCEPT
iptables -w -A CODING_HOST -j REJECT
iptables -w -F CODING_EGRESS
iptables -w -A CODING_EGRESS -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
iptables -w -A CODING_EGRESS -j REJECT
iptables -w -C INPUT -s 172.30.91.0/24 -j CODING_HOST 2>/dev/null ||
    iptables -w -I INPUT 1 -s 172.30.91.0/24 -j CODING_HOST
iptables -w -C DOCKER-USER -s 172.30.91.0/24 -j CODING_EGRESS 2>/dev/null ||
    iptables -w -I DOCKER-USER 1 -s 172.30.91.0/24 -j CODING_EGRESS
EOF
chmod 700 "$gateway_home/apply-firewall.sh"
cat > /etc/systemd/system/runtime-coding-boundary.service <<EOF
[Unit]
Description=Runtime OS Coding callback gateway and network boundary
After=docker.service
Requires=docker.service
PartOf=docker.service
[Service]
Type=forking
PIDFile=$gateway_home/nginx.pid
ExecStartPre=$gateway_home/apply-firewall.sh
ExecStart=$nginx_bin -p $gateway_home/ -c nginx.conf
ExecReload=$nginx_bin -p $gateway_home/ -c nginx.conf -s reload
ExecStop=$nginx_bin -p $gateway_home/ -c nginx.conf -s quit
Restart=on-failure
[Install]
WantedBy=multi-user.target docker.service
EOF
systemctl daemon-reload
"$gateway_home/apply-firewall.sh"
systemctl enable runtime-coding-boundary.service
if systemctl is-active --quiet runtime-coding-boundary.service; then
    systemctl reload runtime-coding-boundary.service
else
    systemctl start runtime-coding-boundary.service
fi
echo 'Coding callback gateway: http://172.30.91.1:18081; egress policy applied'

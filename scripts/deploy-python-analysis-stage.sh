#!/usr/bin/env bash
# Isolated acceptance deployment. Existing API/MCP/worker services are not replaced.
set -euo pipefail
release=${1:?usage: deploy-python-analysis-stage.sh /opt/release-directory}
[[ "$release" == /opt/* && "$release" != *..* ]] || exit 2
image=${CODING_STAGE_IMAGE:-runtime-coding-agent:python-analysis-20261017}
secret=${CODING_STAGE_SECRET_FILE:-/opt/runtime-coding/config/shared-secret}
environment_id=${CODING_STAGE_ENVIRONMENT_ID:?Set the published network-disabled Python environment ID}
network=runtime-python-analysis-stage
node_ip=172.30.92.10
gateway=172.30.92.1
container=python-analysis-stage-20261017
nginx=/opt/bigdata/nginx/sbin/nginx
test -s "$release/app.jar"
test -s "$release/smoke-classes.jar"
test -s "$secret"
mkdir -p "$release/gateway" "$release/app/config" "$release/app/data/h2" "$release/evidence"
chmod 700 "$release" "$release/app" "$release/evidence"
if ! docker network inspect "$network" >/dev/null 2>&1; then
  # Refuse an overlapping route rather than change an existing network.
  if ip -4 route show | grep -q '172.30.92.0/24'; then echo 'Stage subnet is already in use' >&2; exit 2; fi
  docker network create --internal --subnet 172.30.92.0/24 --gateway "$gateway" "$network"
fi
test "$(docker network inspect -f '{{.Internal}}' "$network")" = true
chain=CODING_PY_STAGE
iptables -N "$chain" 2>/dev/null || true
iptables -C "$chain" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -A "$chain" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -C "$chain" -d "$gateway" -p tcp --dport 18083 -j ACCEPT 2>/dev/null || iptables -A "$chain" -d "$gateway" -p tcp --dport 18083 -j ACCEPT
iptables -C "$chain" -j DROP 2>/dev/null || iptables -A "$chain" -j DROP
iptables -C INPUT -s "$node_ip" -j "$chain" 2>/dev/null || iptables -I INPUT 1 -s "$node_ip" -j "$chain"
iptables -C DOCKER-USER -s "$node_ip" -j DROP 2>/dev/null || iptables -I DOCKER-USER 1 -s "$node_ip" -j DROP
cat > "$release/gateway/nginx.conf" <<EOF
worker_processes 1;
pid $release/gateway/nginx.pid;
error_log $release/gateway/error.log warn;
events { worker_connections 128; }
http {
  # Keep the bounded contract/model envelopes in memory; stage artifacts stay root-only.
  client_body_buffer_size 1m;
  client_body_in_single_buffer on;
  proxy_buffering off;
  access_log off;
  client_body_temp_path $release/gateway/body;
  proxy_temp_path $release/gateway/proxy;
  server {
    listen 127.0.0.1:8794;
    client_max_body_size 1m;
    allow 127.0.0.1;
    deny all;
    location / {
      proxy_pass http://$node_ip:3100;
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_connect_timeout 3s;
      proxy_read_timeout 300s;
      proxy_next_upstream off;
    }
  }
  server {
    listen $gateway:18083;
    client_max_body_size 1m;
    allow $node_ip;
    deny all;
    location ~ ^/internal/coding/cline/(reserve-model|model|settle-model|execute-python|get-python|cancel-python|read-metadata|read-artifact|event|finish|checkpoint|data-request|validate-sql|workspace-read|workspace-propose)$ {
      if (\$request_method != POST) { return 405; }
      proxy_pass http://127.0.0.1:18080;
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_connect_timeout 3s;
      proxy_read_timeout 300s;
      proxy_next_upstream off;
    }
    location / { return 404; }
  }
}
EOF
"$nginx" -t -p "$release/gateway/" -c nginx.conf
if test -f "$release/gateway/nginx.pid" && kill -0 "$(cat "$release/gateway/nginx.pid")" 2>/dev/null; then
  "$nginx" -p "$release/gateway/" -c nginx.conf -s reload
else
  "$nginx" -p "$release/gateway/" -c nginx.conf
fi
if ! docker container inspect "$container" >/dev/null 2>&1; then
  docker run -d --name "$container" --network "$network" --ip "$node_ip" \
    --group-add "$(stat -c %g "$secret")" \
    --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --cap-drop ALL --security-opt no-new-privileges --memory 512m --cpus 1 --pids-limit 128 \
    -v "$secret:/run/secrets/coding:ro" \
    -e RUNTIME_CODING_SHARED_SECRET_FILE=/run/secrets/coding \
    -e RUNTIME_CODING_CALLBACK_URL=http://$gateway:18083 \
    -e RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS=$gateway \
    -e RUNTIME_CODING_HOST=0.0.0.0 -e RUNTIME_CODING_PORT=3100 "$image"
fi
# Clone only an inactive acceptance database. The caller checks the source is not open.
if ! test -f "$release/app/data/h2/chatchat.mv.db"; then
  source_db=/opt/coding-deploy-stage-20261015/java-stage/data/h2/chatchat.mv.db
  if command -v fuser >/dev/null && fuser "$source_db" >/dev/null 2>&1; then echo 'Source fixture database is active' >&2; exit 2; fi
  cp -- "$source_db" "$release/app/data/h2/chatchat.mv.db"
fi
ln -sfn /opt/chatchat-1.0.0-SNAPSHOT/config/internal-credential.key "$release/app/config/internal-credential.key"
(cd "$release/app"; /opt/jdk-17.0.5/bin/jar xf "$release/app.jar")
cat > "$release/run-smoke.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd '$release/app'
export CHATCHAT_DATASOURCE_CONFIG=datasource-h2.yml
export PYTHON_ANALYSIS_EVIDENCE_FILE='$release/evidence/real-model.json'
exec /opt/jdk-17.0.5/bin/java -Xms256m -Xmx1g -Dfile.encoding=UTF-8 \\
 -cp '$release/smoke-classes.jar:$release/app/BOOT-INF/classes:$release/app/BOOT-INF/lib/*' \\
 com.chatchat.api.runtime.coding.acceptance.PythonAnalysisSmokeDriver \\
 --server.address=127.0.0.1 --server.port=18080 --spring.profiles.active=dev \\
 '--spring.datasource.url=jdbc:h2:file:./data/h2/chatchat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1' \\
 --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= \\
 --spring.jpa.database-platform=org.hibernate.dialect.H2Dialect \\
 --chatchat.coding.enabled=true --chatchat.coding.cline.enabled=true \\
 --chatchat.coding.cline.node-url=http://127.0.0.1:8794 \\
 --chatchat.coding.cline.allowed-peer-addresses=127.0.0.1 \\
 --chatchat.coding.cline.shared-secret-file='$secret' \\
 --chatchat.coding.analysis.enabled=true --chatchat.coding.analysis.timeout-seconds=600 \\
 --chatchat.coding.analysis.environment-id='$environment_id' \\
 --chatchat.chat.detail-store.external-text.enabled=false --chatchat.search.engine=lucene \\
 --chatchat.search.opensearch.enabled=false --chatchat.mcp.center.enabled=false \\
 --chatchat.mcp.center.base-url=http://127.0.0.1:8090 --logging.level.com.chatchat=INFO
EOF
chmod 700 "$release/run-smoke.sh"
echo "Stage prepared at $release. Launch run-smoke.sh and retain its exit status and evidence."

[CmdletBinding()]
param(
    [string]$EnvFile = ".env.production",
    [string]$ProjectName
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repositoryRoot "docker-compose.yaml"
$resolvedEnvFile = Join-Path $repositoryRoot $EnvFile
$projectArguments = if ([string]::IsNullOrWhiteSpace($ProjectName)) { @() } else { @("-p", $ProjectName) }

if (-not (Test-Path -LiteralPath $resolvedEnvFile)) {
    throw "Production infrastructure environment file does not exist: $resolvedEnvFile"
}

function Invoke-Compose([string[]]$Arguments) {
    & docker compose @projectArguments --env-file $resolvedEnvFile -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($Arguments -join ' ')"
    }
}

function Assert-Healthy([string]$ContainerName) {
    $status = (& docker inspect $ContainerName --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}').Trim()
    if ($LASTEXITCODE -ne 0 -or $status -ne "healthy") {
        throw "$ContainerName is not healthy (status=$status)"
    }
}

function Invoke-ComposeScript([string]$Service, [string]$Shell, [string]$Script) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Script))
    $output = & docker compose @projectArguments --env-file $resolvedEnvFile -f $composeFile `
        exec -T $Service $Shell -ec "printf '%s' '$encoded' | base64 -d | $Shell"
    if ($LASTEXITCODE -ne 0) {
        throw "$Service infrastructure verification script failed"
    }
    return $output
}

Push-Location $repositoryRoot
try {
    Invoke-Compose @("config", "--quiet")
    Assert-Healthy "chatchat-mysql"
    Assert-Healthy "chatchat-redis"
    Assert-Healthy "chatchat-opensearch"

    $securityExit = (& docker inspect chatchat-opensearch-security-init --format '{{.State.ExitCode}}').Trim()
    if ($LASTEXITCODE -ne 0 -or $securityExit -ne "0") {
        throw "OpenSearch security initialization did not complete successfully (exit=$securityExit)"
    }

    $schemaCommand = @'
MYSQL_PWD=$(cat /run/secrets/mysql_root_password) mysql -N -uroot <<'SQL'
select table_schema, count(*)
from information_schema.tables
where table_schema in ('live_runtime_api', 'live_runtime_mcp', 'chatchat_news')
group by table_schema order by table_schema;
select password from live_runtime_mcp.mcp_redis_cache_config where id='default';
SQL
'@
    $schemaOutput = (Invoke-ComposeScript "mysql" "bash" $schemaCommand) -join "`n"
    foreach ($schema in @("live_runtime_api", "live_runtime_mcp", "chatchat_news")) {
        if ($schemaOutput -notmatch "(?m)^$([regex]::Escape($schema))\s+[1-9][0-9]*$") {
            throw "MySQL schema is missing or empty: $schema"
        }
    }
    if ($schemaOutput -notmatch "(?m)^file:/run/secrets/redis_password$") {
        throw "MCP Redis cache password must be a Docker secret reference"
    }

    $redisCommand = @'
export REDISCLI_AUTH=$(cat /run/secrets/redis_password)
test "$(redis-cli --user chatchat --no-auth-warning ping)" = PONG
test "$(redis-cli --user chatchat --no-auth-warning set db-query-cache:release-smoke ok EX 30)" = OK
test "$(redis-cli --user chatchat --no-auth-warning get db-query-cache:release-smoke)" = ok
redis-cli --user chatchat --no-auth-warning set forbidden:release-smoke denied 2>&1 | grep -q NOPERM
'@
    Invoke-ComposeScript "redis" "sh" $redisCommand | Out-Null

    $openSearchCommand = @'
password=$(cat /run/secrets/opensearch_admin_password)
admin_code=$(curl --silent --output /tmp/health.json --write-out '%{http_code}' \
  --cacert /usr/share/opensearch/config/certs/root-ca.pem -u admin:$password \
  https://localhost:9200/_cluster/health)
anonymous_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --cacert /usr/share/opensearch/config/certs/root-ca.pem \
  https://localhost:9200/_cluster/health)
demo_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --cacert /usr/share/opensearch/config/certs/root-ca.pem -u kibanaserver:kibanaserver \
  https://localhost:9200/_cluster/health)
test "$admin_code" = 200
test "$anonymous_code" = 401
test "$demo_code" = 401
grep -q '"status"' /tmp/health.json
unset password
'@
    Invoke-ComposeScript "opensearch" "bash" $openSearchCommand | Out-Null

    Write-Host "Production infrastructure smoke test passed: MySQL schemas, Redis query cache ACL, OpenSearch TLS/security."
} finally {
    Pop-Location
}

#!/bin/sh
set -eu

password="$(tr -d '\r\n' </run/secrets/redis_password)"
case "$password" in
  *[!A-Za-z0-9._~!@#%+=-]*|'')
    echo "redis_password must be 32-128 URL-safe characters" >&2
    exit 1
    ;;
esac
if [ "${#password}" -lt 32 ] || [ "${#password}" -gt 128 ]; then
  echo "redis_password must be 32-128 URL-safe characters" >&2
  exit 1
fi

umask 077
mkdir -p /run/redis
printf 'user default off\nuser chatchat on >%s ~db-query-cache:* +@read +@write +ping\n' "$password" \
  >/run/redis/users.acl
unset password

exec redis-server /usr/local/etc/redis/redis.conf --maxmemory "${REDIS_MAXMEMORY:-768mb}"

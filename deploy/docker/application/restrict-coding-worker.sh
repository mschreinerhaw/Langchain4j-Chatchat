#!/usr/bin/env bash
# Exact test-worker boundary. No other container, host service or production source is selected.
set -euo pipefail
node=runtime-os-app-stage-coding-agent-1
api=runtime-os-app-stage-api-1
network=runtime-os-app-stage_coding
node_ip=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}" "$node")
api_ip=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}" "$api")
[[ "$node_ip" == 172.30.93.10 && "$api_ip" == 172.30.93.20 ]] || { echo 'Unexpected test network identity' >&2; exit 2; }
chain=CLINE_APP_STAGE
iptables -N "$chain" 2>/dev/null || true
iptables -C "$chain" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -A "$chain" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -C "$chain" -d "$api_ip" -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -A "$chain" -d "$api_ip" -p tcp --dport 8080 -j ACCEPT
iptables -C "$chain" -j DROP 2>/dev/null || iptables -A "$chain" -j DROP
iptables -C INPUT -s "$node_ip" -j "$chain" 2>/dev/null || iptables -I INPUT 1 -s "$node_ip" -j "$chain"
iptables -C DOCKER-USER -s "$node_ip" -j "$chain" 2>/dev/null || iptables -I DOCKER-USER 1 -s "$node_ip" -j "$chain"
echo 'Test Coding worker permits only Java callbacks and established replies'

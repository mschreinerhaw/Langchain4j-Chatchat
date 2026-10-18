#!/bin/sh
# Run on the destination host; choose an installed host NIC explicitly.
set -eu
iface=${1:?Usage: host-license-identity.sh HOST_NIC}
case "$iface" in ''|*[!a-zA-Z0-9_.:-]*) echo 'Invalid host NIC name' >&2; exit 2;; esac
nic="/sys/class/net/$iface"
if [ ! -e "$nic/device" ] || [ -e "$nic/bridge" ] || [ ! -r "$nic/address" ]; then
    echo 'Choose a host device NIC, not a Docker bridge or virtual Ethernet endpoint' >&2
    exit 2
fi
mac=$(cat "$nic/address")
printf '%s\n' "$mac" | LC_ALL=C grep -Eq '^([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}$'
printf 'MCP_HOST_MAC_FILE=%s/address\n' "$nic"

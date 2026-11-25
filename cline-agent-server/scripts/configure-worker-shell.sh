#!/bin/sh
set -eu

# Cline's command runner uses the execution account's shell from passwd.
test "$(id -u runner):$(id -g runner)" = 10001:10001
test -x /bin/bash
/usr/sbin/usermod --shell /bin/bash runner
test "$(getent passwd runner | cut -d: -f7)" = /bin/bash
su --command 'test "$(id -u):$(id -g)" = 10001:10001 && true && exec /usr/local/bin/python -c "import openpyxl; print(openpyxl.__version__)"' runner

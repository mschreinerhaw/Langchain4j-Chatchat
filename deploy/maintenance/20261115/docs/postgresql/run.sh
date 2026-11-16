#!/bin/bash
set -euo pipefail
docker run -d --name postgresql18 --restart unless-stopped \
  -p 5432:5432 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/postgresql18.env \
  --mount type=volume,src=998a12dcec518fdb8a7201c81cbd3d507cae16d6e817851638a9d2cbadd3adf5,dst=/var/lib/postgresql \
  --mount type=bind,src=/data/postgresql/18/docker,dst=/var/lib/postgresql/18/docker \
  postgres:18.6

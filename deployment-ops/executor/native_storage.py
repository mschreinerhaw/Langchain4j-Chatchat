"""Native executor storage validation; called before any state files are opened."""
import os
from pathlib import Path

def validate():
    mount = Path('/mnt/disk01')
    if not mount.is_mount() or mount.resolve() != mount:
        raise RuntimeError('Required real mount /mnt/disk01 is unavailable')
    if mount.stat().st_dev==Path('/').stat().st_dev:
        raise RuntimeError('Data mount resolves to the system filesystem; refusing fallback')
    for key in ('OPS_STATE', 'OPS_PACKAGES', 'OPS_TRUST', 'OPS_APP_DATA'):
        value = Path(os.environ[key]).resolve()
        if mount not in value.parents:
            raise RuntimeError(key + ' must resolve below /mnt/disk01')
    if os.getenv('OPS_RUNTIME_ROOT') and mount not in Path(os.environ['OPS_RUNTIME_ROOT']).resolve().parents:
        raise RuntimeError('Native Runtime OS storage must resolve below /mnt/disk01')

if os.getenv('OPS_NATIVE') == '1':
    validate()

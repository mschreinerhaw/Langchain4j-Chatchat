"""Native executor storage validation; called before any state files are opened."""
import os
from pathlib import Path

def validate():
    mount = Path('/mnt/disk01')
    if not mount.is_mount() or mount.resolve() != mount:
        raise RuntimeError('Required real mount /mnt/disk01 is unavailable')
    for key in ('OPS_STATE', 'OPS_PACKAGES', 'OPS_TRUST', 'OPS_APP_DATA'):
        value = Path(os.environ[key]).resolve()
        if mount not in value.parents:
            raise RuntimeError(key + ' must resolve below /mnt/disk01')

if os.getenv('OPS_NATIVE') == '1':
    validate()

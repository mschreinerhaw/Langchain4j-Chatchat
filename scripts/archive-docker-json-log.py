"""Emergency, lossless copy/rotate of one explicitly named Docker JSON log.

Run as host administrator with --container NAME --archive /OTHER_VOLUME/name.gz.
Only the inspected container's json-file log is touched. Volumes are untouched.
Docker's ordinary log rotation should be configured after this recovery.
"""
import argparse
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import time


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', required=True)
    parser.add_argument('--archive', type=Path, required=True)
    args = parser.parse_args()
    container = json.loads(run('docker', 'inspect', args.container))[0]
    if container['HostConfig']['LogConfig']['Type'] != 'json-file':
        raise RuntimeError('Only json-file logs are supported')
    log = Path(container['LogPath'])
    docker_root = Path(run('docker', 'info', '--format', '{{.DockerRootDir}}'))
    if log.is_symlink() or log.resolve() != docker_root / 'containers' / container['Id'] / (container['Id'] + '-json.log'):
        raise RuntimeError('Unexpected Docker-owned log path')
    archive = args.archive.resolve()
    if not args.archive.is_absolute() or archive.exists():
        raise RuntimeError('Use a new absolute archive filename')
    archive.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if archive.parent.stat().st_dev == log.stat().st_dev:
        raise RuntimeError('Archive must be on a different filesystem')
    if shutil.disk_usage(archive.parent).free < 2 * 1024**3:
        raise RuntimeError('Archive filesystem requires at least 2 GiB free')
    digest = hashlib.sha256()
    copied = 0
    paused = False
    started = time.time()
    with log.open('r+b', buffering=0) as source, os.fdopen(os.open(archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as target:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise RuntimeError('Not a regular Docker log')
        def copy_until(end):
            nonlocal copied
            pending = deque()
            with ThreadPoolExecutor(max_workers=4) as pool:
                while copied < end:
                    data = source.read(min(8 * 1024**2, end - copied))
                    if not data:
                        raise RuntimeError('Log changed or was rotated during archive')
                    digest.update(data)
                    copied += len(data)
                    pending.append(pool.submit(gzip.compress, data, compresslevel=1))
                    if len(pending) >= 4:
                        target.write(pending.popleft().result())
                while pending:
                    target.write(pending.popleft().result())
        copy_until(os.fstat(source.fileno()).st_size)
        while os.fstat(source.fileno()).st_size - copied > 16 * 1024**2:
            copy_until(os.fstat(source.fileno()).st_size)
        try:
            if container['State']['Running']:
                run('docker', 'pause', container['Id'])
                paused = True
                time.sleep(.2)  # Let Docker's stdout pipe drain before sealing.
            copy_until(os.fstat(source.fileno()).st_size)
            target.flush()
            os.fsync(target.fileno())
            if log.stat().st_ino != os.fstat(source.fileno()).st_ino:
                raise RuntimeError('Docker rotated the log; refusing truncation')
            source.truncate(0)
            os.fsync(source.fileno())
        finally:
            if paused:
                run('docker', 'unpause', container['Id'])
    receipt = dict(container=container['Name'], archivedBytes=copied,
                   rawSha256=digest.hexdigest(), compressedBytes=archive.stat().st_size,
                   seconds=round(time.time()-started, 2), archive=str(archive),
                   volumesModified=False, status='ARCHIVED_AND_ROTATED')
    archive.with_suffix('.receipt.json').write_text(json.dumps(receipt, indent=2))
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()

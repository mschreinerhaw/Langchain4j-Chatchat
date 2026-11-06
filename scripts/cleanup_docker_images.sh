#!/usr/bin/env bash
set -Eeuo pipefail

# Default: preview only. No container/volume/network/build-cache pruning.
# Requires bash, python3 and Docker. Unknown images are retained.
exec python3 - "$@" <<'PY'
import argparse
from datetime import datetime, timedelta, timezone
import fnmatch
import json
from pathlib import Path
import subprocess
import sys

APP_REPOS = {'runtime-os-api', 'runtime-os-mcp', 'runtime-os-news',
             'runtime-os-coding-agent', 'runtime-os-gateway'}
APP_REPOS.update('localhost:18443/runtime-os/' + name
                 for name in ('api', 'mcp', 'news', 'coding-agent', 'gateway'))
PROTECTED_PREFIXES = ('chatchat-python-runtime', 'python', 'runtime-os-api-runtime',
                      'runtime-os-java-base', 'runtime-os-node-base', 'registry')
PROTECTED_MARKERS = ('baseline', 'rollback', 'offline-parent', 'sdk-reuse-baseline')


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.PIPE).strip()


def objects(kind):
    args = ('ps', '-aq', '--no-trunc') if kind == 'container' else ('image', 'ls', '-aq', '--no-trunc')
    ids = sorted(set(docker(*args).split()))
    return [value for start in range(0, len(ids), 50)
            for value in json.loads(docker(kind, 'inspect', *ids[start:start + 50]))]


def repository(ref):
    ref = ref.split('@', 1)[0]
    return ref.rsplit(':', 1)[0] if ':' in ref.rsplit('/', 1)[-1] else ref


def refs(image):
    return [ref for ref in (image.get('RepoTags') or []) + (image.get('RepoDigests') or [])
            if ref != '<none>:<none>']


def configuration(stage, keep_file):
    # Missing/unreadable deployment configuration aborts before deleting anything.
    required = [stage / 'compose.yaml', stage / 'config/deployment.env']
    files = set(required)
    files.update(p for p in (stage / 'config').rglob('*')
                 if p.is_file() and p.suffix.lower() in ('.yaml', '.yml', '.json', '.env', '.properties', '.conf'))
    text = '\n'.join(p.read_text(encoding='utf-8') for p in sorted(files))
    patterns = []
    if keep_file is not None:
        patterns = [line.strip() for line in keep_file.read_text(encoding='utf-8').splitlines()
                    if line.strip() and not line.lstrip().startswith('#')]
    return text, patterns


def protections(image, used, config, patterns, newest, cutoff):
    identity, tags = image['Id'], refs(image)
    if identity in used:
        return '任意容器引用（包括已停止/回滚容器）'
    if identity in config or any(ref in config for ref in tags):
        return '部署配置引用'
    if any(fnmatch.fnmatchcase(ref, pattern) for ref in [identity, *tags] for pattern in patterns):
        return '自定义保留清单'
    labels = image.get('Config', {}).get('Labels') or {}
    if str(labels.get('com.chatchat.cleanup.protect', '')).lower() in ('true', '1', 'yes'):
        return '保护标签'
    if any(repository(ref).rsplit('/', 1)[-1] in PROTECTED_PREFIXES
           or any(marker in ref.lower() for marker in PROTECTED_MARKERS) for ref in tags):
        return '运行时/基础镜像/回滚依赖'
    if tags and any(repository(ref) not in APP_REPOS for ref in tags):
        return '清理白名单之外（含基础设施、工具镜像及未知别名）'
    if not tags and str(labels.get('com.chatchat.cleanup', '')).lower() != 'true':
        return '无标签镜像来源不明'
    if identity in newest:
        return '每个应用仓库至少保留最新两份'
    try:
        # Docker Created may include nanoseconds; retention uses seconds.
        timestamp = datetime.fromisoformat(image['Created'][:19] + '+00:00')
    except (KeyError, ValueError, TypeError):
        return '创建时间无法确认'
    if timestamp >= cutoff:
        return '尚未超过保留天数'
    return None


def main():
    parser = argparse.ArgumentParser(description='仅清理明确过期的应用镜像；保留全部容器和关键运行依赖。')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true', help='仅预览（默认）')
    mode.add_argument('--execute', action='store_true', help='执行已通过保护检查的镜像清理')
    parser.add_argument('--config-dir', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    parser.add_argument('--keep-file', type=Path, help='额外保留的镜像名/摘要/glob，每行一项；不覆盖内置保护')
    parser.add_argument('--min-age-days', type=int, default=3)
    args = parser.parse_args()
    if args.min_age_days < 1:
        parser.error('--min-age-days 必须至少为 1')
    keep_file = args.keep_file
    if keep_file is None and Path('/opt/cleanup_docker_images.keep').exists():
        keep_file = Path('/opt/cleanup_docker_images.keep')
    docker('info')
    config, patterns = configuration(args.config_dir, keep_file)
    containers, images = objects('container'), objects('image')
    used = {value['Image'] for value in containers}
    newest = set()
    for repo in APP_REPOS:
        group = [image for image in images if any(repository(ref) == repo for ref in refs(image))]
        newest.update(image['Id'] for image in sorted(group, key=lambda v: v.get('Created', ''), reverse=True)[:2])
    cutoff = datetime.now(timezone.utc) - timedelta(days=args.min_age_days)
    print('模式：' + ('执行' if args.execute else '预览；不会删除任何内容'))
    print('保留全部容器；禁止 force、prune，不清理卷/网络/构建缓存。')
    candidates = []
    for image in images:
        reason = protections(image, used, config, patterns, newest, cutoff)
        description = image['Id'] + ' ' + ', '.join(refs(image))
        print(('[保护] ' + description + '：' + reason) if reason else '[候选] ' + description)
        if reason is None:
            candidates.append(image)
    removed, skipped = 0, 0
    if args.execute:
        for candidate in candidates:
            # Refresh all references/configuration and the image immediately before removal.
            # Any Docker/config read failure aborts. Never interpret failed reads as empty.
            used = {value['Image'] for value in objects('container')}
            config, patterns = configuration(args.config_dir, keep_file)
            current = json.loads(docker('image', 'inspect', candidate['Id']))[0]
            reason = protections(current, used, config, patterns, newest, cutoff)
            if reason:
                print('[跳过] ' + current['Id'] + '：' + reason)
                skipped += 1
                continue
            try:
                # No force, no tag removal: Docker also protects stopped containers,
                # multiple tags and parent layers referenced by descendant images.
                docker('image', 'rm', current['Id'])
                removed += 1
                print('[已删除] ' + current['Id'])
            except subprocess.CalledProcessError:
                skipped += 1
                print('[跳过] Docker 拒绝删除 ' + current['Id'])
    print('保留容器 %d；镜像 %d；候选 %d；删除 %d；跳过 %d' %
          (len(containers), len(images), len(candidates), removed, skipped))


try:
    main()
except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
    # Do not print deployment config or Docker inspect contents (may contain secrets).
    print('错误：安全检查未完成，停止清理。类型：' + type(error).__name__, file=sys.stderr)
    sys.exit(1)
PY

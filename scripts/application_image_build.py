"""Build an application from clean runtime templates, never a previous app image."""
import os
from pathlib import Path
import shutil
import subprocess


def build_application_image(service, jar, tag, context):
    if service not in {'api', 'mcp', 'news'}:
        raise ValueError('Unexpected application service')
    repo = Path(__file__).resolve().parents[1]
    templates = Path(os.environ.get('CHATCHAT_IMAGE_TEMPLATES', repo / 'deploy/docker/application'))
    base = os.environ.get('CHATCHAT_JAVA_RUNTIME_IMAGE', 'eclipse-temurin:17-jre-jammy')
    # A named business image (even if retagged as a runtime) cannot be a base.
    history = subprocess.check_output(['docker', 'history', '--no-trunc', '--format', '{{.CreatedBy}}', base], text=True)
    if 'app.jar' in history:
        raise ValueError('Refusing a runtime base containing a business JAR; use a clean Java runtime')
    context = Path(context); context.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, context / 'app.jar')
    name = {'api': 'Dockerfile.api', 'mcp': 'Dockerfile.mcp', 'news': 'Dockerfile'}[service]
    shutil.copy2(templates / name, context / 'Dockerfile')
    shutil.copy2(templates / '.dockerignore', context / '.dockerignore')
    if service == 'api':
        shutil.copy2(templates / 'sql-toolchain-requirements.txt', context / 'sql-toolchain-requirements.txt')
    if service == 'mcp':
        cli = os.environ.get('CHATCHAT_DOCKER_CLI')
        if not cli:
            raise ValueError('CHATCHAT_DOCKER_CLI must name a trusted Linux Docker CLI binary')
        shutil.copy2(cli, context / 'docker')
    subprocess.run(['docker', 'build', '--build-arg', 'JAVA_RUNTIME_IMAGE=' + base,
                    '--build-arg', 'APP_PORT=' + ('8091' if service == 'news' else '8080'),
                    '-t', tag, str(context)], check=True)
    size = int(subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Size}}', tag], text=True))
    if size > 1200 * 1024 * 1024:
        raise ValueError('Application image exceeds the 1200 MiB release budget')

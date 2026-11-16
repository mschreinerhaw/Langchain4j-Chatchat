"""Compile on the target host using a prepared Boot runtime archive; no network."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
import os
import re
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--runtime', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--version', help='Override the native module version in the JAR manifest')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
project = ET.parse(root / 'pom.xml').getroot()
namespace = {'m':'http://maven.apache.org/POM/4.0.0'}
version = args.version or project.findtext('m:version',namespaces=namespace) or 'development'
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,79}',version):raise SystemExit('Invalid native artifact version')
with tempfile.TemporaryDirectory() as temp:
    work = Path(temp)
    libs = work / 'libs'
    libs.mkdir()
    classes = work / 'classes'
    classes.mkdir()
    with zipfile.ZipFile(args.runtime) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
                (libs / Path(entry.filename).name).write_bytes(archive.read(entry))
    java_home = __import__('os').environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', str(libs / '*'),
                    '-d', str(classes), *map(str, (root / 'src/main/java').rglob('*.java')),
                    str(root / 'backend/src/Main.java'), str(root / 'backend/src/ManagerApi.java')], check=True)
    shutil.copytree(root / 'src/main/resources', classes, dirs_exist_ok=True)
    shutil.copy2(root / 'backend/schema.sql', classes / 'schema.sql')
    if (root / 'web-ui/dist').is_dir():
        shutil.copytree(root / 'web-ui/dist', classes / 'static', dirs_exist_ok=True)
    shutil.copytree(root / 'frontend', classes / 'static/legacy', dirs_exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    staged = args.output.with_name(args.output.name + '.building-' + str(os.getpid()))
    with zipfile.ZipFile(args.runtime) as runtime, zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED, strict_timestamps=False) as output:
        output.writestr('BOOT-INF/classes/', b'')
        for entry in runtime.infolist():
            if not entry.filename.startswith('BOOT-INF/classes/'):
                # Nested libraries must be STORED for Spring Boot's loader.
                data = runtime.read(entry)
                if entry.filename == 'META-INF/MANIFEST.MF':
                    lines = [line for line in data.decode('utf-8').splitlines() if not line.startswith(('Implementation-Version:','Implementation-Title:'))]
                    while lines and not lines[-1]:lines.pop()
                    lines += ['Implementation-Title: Deployment Ops','Implementation-Version: '+version]
                    data = ('\r\n'.join(lines)+'\r\n\r\n').encode('utf-8')
                elif entry.filename == 'META-INF/maven/com.chatchat/deployment-ops/pom.xml':
                    data = (root/'pom.xml').read_bytes()
                elif entry.filename == 'META-INF/maven/com.chatchat/deployment-ops/pom.properties':
                    data = ('groupId=com.chatchat\nartifactId=deployment-ops\nversion='+version+'\n').encode('utf-8')
                output.writestr(entry, data)
        for path in classes.rglob('*'):
            if path.is_file():
                output.write(path, 'BOOT-INF/classes/' + path.relative_to(classes).as_posix())
            elif path.is_dir():
                output.writestr('BOOT-INF/classes/' + path.relative_to(classes).as_posix() + '/', b'')
    os.replace(staged,args.output)
print(hashlib.sha256(args.output.read_bytes()).hexdigest(), args.output)

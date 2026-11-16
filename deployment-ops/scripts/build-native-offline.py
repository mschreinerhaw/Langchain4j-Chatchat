"""Compile on the target host using a prepared Boot runtime archive; no network."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--runtime', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
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
    with zipfile.ZipFile(args.runtime) as runtime, zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as output:
        output.writestr('BOOT-INF/classes/', b'')
        for entry in runtime.infolist():
            if not entry.filename.startswith('BOOT-INF/classes/'):
                # Nested libraries must be STORED for Spring Boot's loader.
                output.writestr(entry, runtime.read(entry))
        for path in classes.rglob('*'):
            if path.is_file():
                output.write(path, 'BOOT-INF/classes/' + path.relative_to(classes).as_posix())
print(hashlib.sha256(args.output.read_bytes()).hexdigest(), args.output)

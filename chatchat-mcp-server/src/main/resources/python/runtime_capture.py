"""Runtime-owned stream boundary. Raw process streams stay in bounded memory only."""
import builtins
import ast
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import threading
from pathlib import Path


def check_source(source):
    """Check syntax and unconditional imports without executing user statements.

    Optional imports inside try/if/functions are deliberately left to Python.
    This runs in the same restricted image, mounts and budget as the program.
    Never install packages or import a package just to check its presence.
    """
    text = Path(source).read_bytes()
    tree = ast.parse(text, filename=source)
    compile(tree, source, 'exec')
    sys.path.insert(0, str(Path(source).parent))
    for node in tree.body:
        modules = [item.name for item in node.names] if isinstance(node, ast.Import) else (
            [node.module] if isinstance(node, ast.ImportFrom) and node.level == 0 and node.module else [])
        for module in modules:
            root = module.split('.')[0]
            if root not in sys.modules and importlib.util.find_spec(root) is None:
                sys.stderr.write(f'  File "{source}", line {node.lineno}\n')
                raise ModuleNotFoundError(f'No module named {root!r}', name=root)


def run_captured(command):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               env={**os.environ, 'PYTHONIOENCODING': 'utf-8'})
    out, err = {}, {}
    readers = [threading.Thread(target=capture, args=(process.stdout, out)),
               threading.Thread(target=capture, args=(process.stderr, err))]
    for reader in readers:
        reader.start()
    code = process.wait()
    for reader in readers:
        reader.join()
    return code, out, err


def capture(stream, result):
    digest = hashlib.sha256()
    tail = bytearray()
    size = 0
    while True:
        block = stream.read(8192)
        if not block:
            break
        size += len(block)
        digest.update(block)
        tail.extend(block)
        if len(tail) > 65536:
            del tail[:-65536]
    result.update(bytes=size, sha256=digest.hexdigest(), tail=tail.decode('utf-8', 'replace'))


def main():
    source = sys.argv[1]
    try:
        import resource
        before_usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    except ImportError:
        resource = None
        before_usage = None
    code, out, err = run_captured([sys.executable, str(Path(__file__).resolve()), '--check-source', source])
    preflight_passed = code == 0
    if preflight_passed:
        code, out, err = run_captured([sys.executable, '-u', source])
    diagnostic = {'schemaVersion': 'runtime_os.python_diagnostic.v1',
                  'preflightStatus': 'PASSED' if preflight_passed else 'FAILED',
                  'policy': 'METADATA_ONLY_V1', 'stdoutBytes': out['bytes'], 'stderrBytes': err['bytes'],
                  'stdoutSha256': out['sha256'], 'stderrSha256': err['sha256']}
    if resource is not None:
        usage = resource.getrusage(resource.RUSAGE_CHILDREN)
        diagnostic['cpuTimeMs'] = max(0, int(1000 * (
            usage.ru_utime + usage.ru_stime - before_usage.ru_utime - before_usage.ru_stime)))
        diagnostic['maxRssBytes'] = max(0, int(usage.ru_maxrss * (1 if sys.platform == 'darwin' else 1024)))
    exceptions = {name for name in dir(builtins) if isinstance(getattr(builtins, name), type)
                  and issubclass(getattr(builtins, name), BaseException)}
    for name in re.findall(r'^([A-Za-z]+Error|[A-Za-z]+Exception|StopIteration|KeyboardInterrupt|SystemExit)(?=:|$)', err['tail'], re.M):
        if name in exceptions:
            diagnostic['exceptionType'] = name
    allowed = {source: os.path.basename(source)}
    # Only names declared by the Runtime-owned project envelope may cross the log boundary.
    try:
        program = Path(source).read_text(encoding='utf-8')
        if program.startswith('# runtime_os.python.code-project.v1\n'):
            import base64
            match = re.search(r'^__project_b64__ = "([A-Za-z0-9+/=]{1,250000})"$', program, re.M)
            project = json.loads(base64.b64decode(match.group(1))) if match else {}
            for file in project.get('files', [])[:32]:
                relative = file.get('path', '')
                if (relative.endswith('.py') and not relative.startswith('/') and '\\' not in relative
                        and ':' not in relative and all(p not in ('', '.', '..') for p in relative.split('/'))):
                    allowed['/workspace/output/validation/project/' + relative] = 'project/' + relative
                    allowed['/tmp/runtime-python-project/' + relative] = 'project/' + relative
    except (OSError, ValueError, TypeError, KeyError):
        pass
    frames = [{'file': allowed[file], 'line': int(line)}
              for file, line in re.findall(r'File "([^"\n]{1,500})", line ([0-9]{1,8})', err['tail']) if file in allowed]
    if frames:
        diagnostic['line'] = frames[-1]['line']
        diagnostic['stack'] = frames[-16:]
    hint = diagnostic_code(err['tail'])
    if hint:
        diagnostic['diagnosticCode'] = hint
    path = sys.argv[2] if len(sys.argv) > 2 else '/workspace/output/logs/runtime-diagnostic.json'
    with open(path + '.part', 'w', encoding='utf-8') as output:
        json.dump(diagnostic, output, sort_keys=True)
    os.replace(path + '.part', path)
    return code if code >= 0 else 128 - code


def diagnostic_code(tail):
    """Fixed technical categories only; never persist exception text or values."""
    if re.search(r'^(?:PermissionError:|OSError: \[Errno (?:1|13|30)\])', tail, re.M):
        return 'PERMISSION_DENIED'
    if re.search(r'^sqlite3\.(?:OperationalError|ProgrammingError|IntegrityError|DataError|InterfaceError|DatabaseError|NotSupportedError):', tail, re.M):
        return 'LOCAL_SQL_ERROR'
    if re.search(r'^(?:json\.decoder\.JSONDecodeError|_?csv\.Error):', tail, re.M):
        return 'INPUT_FORMAT_ERROR'
    if re.search(r'^re\.(?:error|PatternError):', tail, re.M):
        return 'PATTERN_SYNTAX_ERROR'
    if re.search(r'^decimal\.(?:InvalidOperation|DivisionByZero|FloatOperation|InvalidContext|Overflow|Underflow):', tail, re.M):
        return 'NUMERIC_TYPE_MISMATCH'
    if re.search(r"^TypeError: '[A-Za-z0-9_.]+' object is not subscriptable\s*$", tail, re.M):
        return 'VALUE_NOT_SUBSCRIPTABLE'
    if re.search(r'^TypeError: unsupported operand type\(s\) for ', tail, re.M):
        return 'NUMERIC_TYPE_MISMATCH'
    if re.search(r'^TypeError: Object of type [A-Za-z0-9_.]+ is not JSON serializable\s*$', tail, re.M):
        return 'JSON_VALUE_NOT_SERIALIZABLE'
    if re.search(r'^ModuleNotFoundError:', tail, re.M):
        return 'PACKAGE_NOT_INSTALLED'
    if re.search(r'^AttributeError: .* has no attribute |^ImportError: cannot import name ', tail, re.M):
        return 'PACKAGE_API_UNAVAILABLE'
    return None


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--check-source':
        check_source(sys.argv[2])
    else:
        sys.exit(main())

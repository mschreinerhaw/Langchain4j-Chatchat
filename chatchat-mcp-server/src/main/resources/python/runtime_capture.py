"""Runtime-owned stream boundary. Raw process streams stay in bounded memory only."""
import builtins
import hashlib
import json
import os
import re
import subprocess
import sys
import threading


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
    process = subprocess.Popen([sys.executable, '-u', source], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out, err = {}, {}
    readers = [threading.Thread(target=capture, args=(process.stdout, out)),
               threading.Thread(target=capture, args=(process.stderr, err))]
    for reader in readers:
        reader.start()
    code = process.wait()
    for reader in readers:
        reader.join()
    diagnostic = {'policy': 'METADATA_ONLY_V1', 'stdoutBytes': out['bytes'], 'stderrBytes': err['bytes'],
                  'stdoutSha256': out['sha256'], 'stderrSha256': err['sha256']}
    exceptions = {name for name in dir(builtins) if isinstance(getattr(builtins, name), type)
                  and issubclass(getattr(builtins, name), BaseException)}
    for name in re.findall(r'^([A-Za-z]+Error|[A-Za-z]+Exception|StopIteration|KeyboardInterrupt|SystemExit)(?=:|$)', err['tail'], re.M):
        if name in exceptions:
            diagnostic['exceptionType'] = name
    frames = re.findall(r'File "' + re.escape(source) + r'", line ([0-9]{1,8})', err['tail'])
    if frames:
        diagnostic['line'] = int(frames[-1])
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
    if re.search(r"^TypeError: '[A-Za-z0-9_.]+' object is not subscriptable\s*$", tail, re.M):
        return 'VALUE_NOT_SUBSCRIPTABLE'
    if re.search(r'^TypeError: unsupported operand type\(s\) for ', tail, re.M):
        return 'NUMERIC_TYPE_MISMATCH'
    if re.search(r'^TypeError: Object of type [A-Za-z0-9_.]+ is not JSON serializable\s*$', tail, re.M):
        return 'JSON_VALUE_NOT_SERIALIZABLE'
    if re.search(r'^ModuleNotFoundError:', tail, re.M):
        return 'PACKAGE_NOT_INSTALLED'
    if re.search(r'^AttributeError: .* has no attribute ', tail, re.M):
        return 'PACKAGE_API_UNAVAILABLE'
    return None


if __name__ == '__main__':
    sys.exit(main())

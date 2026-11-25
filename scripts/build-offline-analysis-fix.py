"""Package the server-built classes against the published Runtime OS 1.3.3 JARs."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import runpy
import shutil
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
RELEASE = ROOT / "deploy/releases/1.3.3"
NAME = "runtime-os-1.3.3-offline-analysis-fix-20261125"
AGENTS_JAR = "BOOT-INF/lib/chatchat-agents-1.0.0-SNAPSHOT.jar"
BASE_HASHES = {
    "f7-api": "9afbd86bfdb433f997f557c52070f0877c90084661847a1c684b1bac5b3b5c39",
    "r2-api": "b4d4fc2a67ff80107f0c3d878f829dc66218c8572c73fea61b65889b79e9b0f4",
    "f7-mcp": "d68a741b798d401e4743e2027647dbb39f91513439d09463523d2ecc36040041",
}
CLASSES = {
    "chatchat-api": "com/chatchat/api/runtime/coding/nativecline/NativeS3CodingAdapter",
    "chatchat-agents": "com/chatchat/agents/runtime/coding/DurableCodingTaskService",
    "chatchat-mcp-server": "com/chatchat/mcpserver/templatepublication/publisher/TemplateQueryMcpToolPublisher",
}


def overlay(source, target, updates):
    remaining = dict(updates)
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(target, "w") as after:
        after.comment = before.comment
        for entry in before.infolist():
            if entry.filename in remaining:
                after.writestr(entry, remaining.pop(entry.filename))
            else:
                with before.open(entry) as src, after.open(entry, "w") as dst:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
        for name, data in sorted(remaining.items()):
            after.writestr(zipfile.ZipInfo(name, (2026, 11, 25, 0, 0, 0)), data,
                           compress_type=zipfile.ZIP_DEFLATED)


def class_updates(payload, module, prefix=""):
    start = module + "/" + CLASSES[module]
    result = {
        prefix + name.split("/", 1)[1]: payload.read(name)
        for name in payload.namelist()
        if name == start + ".class" or name.startswith(start + "$") and name.endswith(".class")
    }
    if prefix + CLASSES[module] + ".class" not in result:
        raise RuntimeError("Missing compiled class for " + module)
    return result


def verify_jar(before, after, updated):
    with zipfile.ZipFile(before) as a, zipfile.ZipFile(after) as b:
        if not set(a.namelist()) <= set(b.namelist()) or b.testzip():
            raise RuntimeError("Invalid release JAR inventory: " + str(after))
        for entry in a.infolist():
            if entry.filename not in updated:
                other = b.getinfo(entry.filename)
                if (entry.CRC, entry.file_size) != (other.CRC, other.file_size):
                    raise RuntimeError("Unexpected JAR entry change: " + entry.filename)
        if AGENTS_JAR in updated and b.getinfo(AGENTS_JAR).compress_type != zipfile.ZIP_STORED:
            raise RuntimeError("Spring Boot nested JAR must remain uncompressed")


def build(args):
    tools = runpy.run_path(str(ROOT / "scripts/jar-delta.py"))
    stage = args.work / NAME
    stage.mkdir(parents=True, exist_ok=True)
    for key, expected in BASE_HASHES.items():
        if tools["digest"](args.work / (key + ".jar")) != expected:
            raise RuntimeError("Unexpected published baseline: " + key)
    recipes = {"api": [], "mcp": []}
    class_archive = args.work / "java-classes.zip"
    with zipfile.ZipFile(class_archive) as payload:
        for key in BASE_HASHES:
            role = key.rsplit("-", 1)[-1]
            base = args.work / (key + ".jar")
            target = args.work / (key + "-new.jar")
            if role == "api":
                updates = class_updates(payload, "chatchat-api", "BOOT-INF/classes/")
                with zipfile.ZipFile(base) as before:
                    nested = io.BytesIO()
                    overlay(io.BytesIO(before.read(AGENTS_JAR)), nested, class_updates(payload, "chatchat-agents"))
                    updates[AGENTS_JAR] = nested.getvalue()
            else:
                updates = class_updates(payload, "chatchat-mcp-server", "BOOT-INF/classes/")
            overlay(base, target, updates)
            verify_jar(base, target, updates)
            signature = args.work / (key + ".signature.json")
            patch = stage / (key + ".patch.zip")
            tools["signature"](base, signature)
            tools["create"](signature, target, patch)
            verified = args.work / (key + "-verified.jar")
            tools["apply"](base, patch, verified)
            assert tools["digest"](verified) == tools["digest"](target)
            verified.unlink()
            recipes[role].append({"file": patch.name, "baseSha256": BASE_HASHES[key],
                                  "targetSha256": tools["digest"](target)})
    files = {
        "apply.py": RELEASE / "offline-analysis-fix/apply.py",
        "README.md": RELEASE / "offline-analysis-fix/README.md",
        "app.Dockerfile": RELEASE / "offline-native-recovery/api.Dockerfile",
        "model_fix.py": RELEASE / "native-model-fix/apply.py",
        "jar-delta.py": ROOT / "scripts/jar-delta.py",
        "Dockerfile.native-worker-shell": ROOT / "deploy/docker/production/Dockerfile.native-worker-shell",
        "cline-agent-server/scripts/configure-worker-shell.sh": ROOT / "cline-agent-server/scripts/configure-worker-shell.sh",
        "docker-compose": args.work / "runtime-os-1.3.3-offline-executor-fix-r2/docker-compose",
    }
    for name, source in files.items():
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (stage / "docker-compose").chmod(0o755)
    (stage / ".dockerignore").write_text("*\n!cline-agent-server/scripts/configure-worker-shell.sh\n", encoding="utf-8")
    (stage / "BUILD.json").write_text(json.dumps({
        "javaBase": "f7c093a1c3b0c49d15e427c0efe1f1121d535146",
        "javaClassPayloadSha256": tools["digest"](class_archive),
        "sourceDigests": json.loads((args.work / "java-source-digests.json").read_text(encoding="utf-8")),
        "jars": recipes,
    }, indent=2) + "\n", encoding="utf-8")
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / (NAME + ".tar.gz")
    def permissions(entry):
        entry.mode = 0o755 if entry.isdir() or entry.name.endswith("/docker-compose") else 0o644
        return entry
    payload_files = [*files, ".dockerignore", "BUILD.json",
                     *(item["file"] for group in recipes.values() for item in group)]
    with tarfile.open(archive, "w:gz", compresslevel=5) as bundle:
        for name in sorted(payload_files):
            bundle.add(stage / name, arcname=NAME + "/" + name, filter=permissions)
    (args.output / (archive.name + ".sha256")).write_text(
        tools["digest"](archive) + "  " + archive.name + "\n", encoding="ascii")
    print(archive)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    build(parser.parse_args())

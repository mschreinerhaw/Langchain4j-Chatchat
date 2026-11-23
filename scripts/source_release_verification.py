"""Verify source provenance and the Spring Boot JAR that will actually run."""

import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile


MODULES = {
    "api": "chatchat-api",
    "mcp": "chatchat-mcp-server",
    "news": "chatchat-runtime-news",
}
REQUIRED = {
    "api": (
        "application.yml",
        "coding-monitor/app.js",
        "coding-monitor/index.html",
        "coding-monitor/style.css",
        "skills/opensearch-asset-discovery/SKILL.md",
        "skills/template-parameter-extraction/SKILL.md",
        "workspace/business.json",
        "static/index.html",
    ),
    "mcp": ("application.yml", "static/admin/index.html"),
    "news": ("application.yml",),
}
PREFIX = "BOOT-INF/classes/"


def stream_sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def sha256(path):
    with path.open("rb") as stream:
        return stream_sha256(stream)


def extract_verified_source(archive_path, source):
    """Extract exactly the files named in the source manifest."""
    manifest_path = "source-manifest.json"
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate source archive members")
        manifest = json.loads(archive.read(manifest_path))
        records = {row["path"]: row for row in manifest["files"]}
        if (len(records) != len(manifest["files"]) or manifest_path in records
                or set(names) != set(records) | {manifest_path}):
            raise ValueError("Source archive and manifest entries differ")
        for name in names:
            path = PurePosixPath(name)
            if (path.as_posix() != name or ".." in path.parts or chr(92) in name
                    or not (source / name).resolve().is_relative_to(source)):
                raise ValueError(f"Archive member outside source root: {name}")
        archive.extractall(source)
    for name, record in records.items():
        file = source / name
        if not file.is_file() or file.stat().st_size != record["size"] or sha256(file) != record["sha256"]:
            raise ValueError(f"Source archive hash or size mismatch: {name}")
    return manifest


def release_resource(path, service):
    # The container-image Maven profile intentionally excludes credentials
    # and MCP replaces its legacy admin assets with the compiled Vue app.
    name = path.rsplit("/", 1)[-1]
    if (name.endswith(".key") or name == "license.dat" or name.startswith(".env")
            or name.endswith(".pem") and ("private" in name or "signing" in name)):
        return False
    return service != "mcp" or not path.startswith(("static/admin/", "static-backup/"))


def verify_jar(jar_path, source, service):
    resource_root = source / MODULES[service] / "src/main/resources"
    expected = {
        file.relative_to(resource_root).as_posix(): file
        for file in resource_root.rglob("*")
        if file.is_file() and release_resource(file.relative_to(resource_root).as_posix(), service)
    }
    if not resource_root.is_dir():
        raise ValueError(f"Missing source resource directory: {resource_root}")
    entries = {}
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate JAR members for {service}")
        missing = sorted((set(expected) | set(REQUIRED[service])) -
                         {name.removeprefix(PREFIX) for name in names if name.startswith(PREFIX)})
        if missing:
            raise ValueError(f"Missing JAR resources for {service}: {', '.join(missing)}")
        for path in sorted(set(expected) | set(REQUIRED[service])):
            info = archive.getinfo(PREFIX + path)
            with archive.open(info) as data:
                digest = stream_sha256(data)
            if path in REQUIRED[service] and info.file_size == 0:
                raise ValueError(f"Empty required JAR resource for {service}: {path}")
            if path in expected and digest != sha256(expected[path]):
                raise ValueError(f"JAR resource differs from source for {service}: {path}")
            entries[path] = {"sha256": digest, "size": info.file_size, "sourceMatched": path in expected}
    return {"jarSha256": sha256(jar_path), "jarBytes": jar_path.stat().st_size, "resources": entries}


def verify_image_jar(image_id, source, service, log_dir):
    """Inspect the immutable image without starting its entrypoint."""
    with tempfile.TemporaryDirectory(prefix="jar-check-", dir=log_dir) as scratch:
        jar = Path(scratch) / "app.jar"
        inspectable = subprocess.check_output(["docker", "create", image_id], text=True).strip()
        try:
            subprocess.run(["docker", "cp", f"{inspectable}:/srv/app/app.jar", str(jar)], check=True)
            return verify_jar(jar, source, service)
        finally:
            subprocess.run(["docker", "rm", inspectable], check=True, stdout=subprocess.DEVNULL)

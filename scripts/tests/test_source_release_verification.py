import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_release_verification import extract_verified_source, verify_jar


class SourceReleaseVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def build_news_fixture(self, entries):
        resources = self.root / "chatchat-runtime-news" / "src/main/resources"
        resources.mkdir(parents=True)
        (resources / "application.yml").write_bytes(b"source yaml")
        (resources / "runtime-extra.yaml").write_bytes(b"extra yaml")
        (resources / "integrator.key").write_bytes(b"excluded deployment key")
        jar = self.root / "app.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for name, resource in entries.items():
                archive.writestr("BOOT-INF/classes/" + name, resource)
        return jar

    def test_all_source_resources_are_checked_against_image_jar(self):
        jar = self.build_news_fixture({"application.yml": b"source yaml", "runtime-extra.yaml": b"extra yaml"})
        result = verify_jar(jar, self.root, "news")
        self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(), result["jarSha256"])
        self.assertEqual({"application.yml", "runtime-extra.yaml"}, set(result["resources"]))
        self.assertTrue(result["resources"]["application.yml"]["sourceMatched"])

    def test_missing_optional_source_resource_prevents_release(self):
        jar = self.build_news_fixture({"application.yml": b"source yaml"})
        with self.assertRaisesRegex(ValueError, "Missing JAR resources.*runtime-extra.yaml"):
            verify_jar(jar, self.root, "news")

    def test_stale_source_resource_prevents_release(self):
        jar = self.build_news_fixture({"application.yml": b"stale yaml", "runtime-extra.yaml": b"extra yaml"})
        with self.assertRaisesRegex(ValueError, "differs from source.*application.yml"):
            verify_jar(jar, self.root, "news")

    def test_extra_unfingerprinted_source_member_prevents_build(self):
        payload = b"known input"
        archive_path = self.root / "source.zip"
        manifest = {"files": [{
            "path": "pom.xml", "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest()
        }]}
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("source-manifest.json", json.dumps(manifest))
            archive.writestr("pom.xml", payload)
            archive.writestr("untracked.sh", b"injected file")
        with self.assertRaisesRegex(ValueError, "archive and manifest entries differ"):
            extract_verified_source(archive_path, self.root / "source")

    def test_fingerprinted_source_with_wrong_bytes_prevents_build(self):
        archive_path = self.root / "bad.zip"
        manifest = {"files": [{"path": "pom.xml", "size": 3, "sha256": "0" * 64}]}
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("source-manifest.json", json.dumps(manifest))
            archive.writestr("pom.xml", b"bad")
        with self.assertRaisesRegex(ValueError, "archive hash or size mismatch"):
            extract_verified_source(archive_path, self.root / "source")


if __name__ == "__main__":
    unittest.main()

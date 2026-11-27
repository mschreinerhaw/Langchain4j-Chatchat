#!/usr/bin/env python3
"""Exercise configuration, migration sequencing and rollback with synthetic Compose state."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from update import Update, inspect_image, json_bytes


class UpgradeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        (root / "update-config").mkdir()
        for role in ("api", "mcp"):
            (root / "update-config" / (role + "-postgresql.sql")).write_text("SELECT 1;")
        package = patch("update.PACKAGE", root)
        package.start()
        self.addCleanup(package.stop)
        self.app = object.__new__(Update)
        self.app.file = root / "compose.json"
        self.raw = b'{"name":"fixture","services":{}}\n'
        self.app.file.write_bytes(self.raw)
        self.app.old = self.raw
        self.app.config = json.loads(self.raw)
        self.app.overlay = root / "compose.runtime-os-offline.json"
        self.app.backups = root / ".runtime-updates"
        self.app.backups.mkdir()
        self.app.app = Mock(name="runtime")
        self.app.app.name = "fixture"
        self.app.model = Mock(name="gateway")
        self.app.model.name = "gateway-fixture"
        self.app.model_base = [root / "gateway.yml"]
        self.app.targets = {"api": {"id": "new-api", "reference": "new-api-tag"}}
        self.app.model_targets = {"llm-gateway": {"id": "new-gateway", "reference": "new-gateway-tag"}}
        self.app.images = {"task": {"id": "new-task"}}
        self.app.plan = Mock(return_value=({
            "api": {"reference": "old-api-tag", "id": "old-api"},
            "llm-gateway": {"reference": "old-gateway-tag", "id": "old-gateway"},
        }, {}, {}))
        self.data = {
            self.app.file: json_bytes({"name": "fixture", "services": {"api": {"image": "new-api-tag"}}}),
            self.app.overlay: json_bytes({"services": {"llm-gateway": {"image": "new-gateway-tag"}}}),
        }
        self.app.edits = Mock(return_value=self.data)
        self.app.monitor_assets = Mock(return_value=set(self.data))
        self.app.check_space = Mock()
        self.app.import_images = Mock(return_value=({"new-api-tag": "new-api", "new-gateway-tag": "new-gateway"}, "new-task"))
        self.app.restart = Mock()
        self.app.db = Mock()

    def test_import_checks_engine_specific_id_and_architecture(self):
        image = {"reference": "fixture", "id": "oci-id", "acceptedImageIds": ["legacy-config-id"]}
        with patch("update.output", return_value="legacy-config-id linux/amd64"):
            self.assertEqual(inspect_image(image), "legacy-config-id")
        with patch("update.output", return_value="legacy-config-id linux/arm64"):
            with self.assertRaisesRegex(RuntimeError, "Unexpected image identity"):
                inspect_image(image)

    def test_apply_and_manual_rollback(self):
        self.app.apply()
        record, = self.app.backups.iterdir()
        self.assertTrue((record / "applied").exists())
        self.assertEqual(self.app.file.read_bytes(), self.data[self.app.file])
        self.assertTrue(self.app.overlay.exists())
        self.assertEqual([(call[0][0], call[0][1]) for call in self.app.db.call_args_list],
                         [("api", "pg_dump"), ("mcp", "pg_dump"), ("api", "psql"), ("mcp", "psql")])
        with patch("update.native_configs"), patch("update.image_id", side_effect=lambda value: value[:-4]):
            self.app.rollback(record)
        self.assertEqual(self.app.file.read_bytes(), self.raw)
        self.assertFalse(self.app.overlay.exists())
        self.assertTrue((record / "rolled-back").exists())

    def test_candidate_failure_restores_exact_original(self):
        self.app.restart.side_effect = [RuntimeError("candidate unhealthy"), None]
        with patch("update.image_id", side_effect=lambda value: value[:-4]):
            with self.assertRaisesRegex(RuntimeError, "candidate unhealthy"):
                self.app.apply()
        record, = self.app.backups.iterdir()
        self.assertTrue((record / "rolled-back").exists())
        self.assertFalse((record / "applied").exists())
        self.assertFalse(self.app.overlay.exists())
        self.assertEqual(self.app.file.read_bytes(), self.raw)

    def test_manual_rollback_rejects_edit_after_release(self):
        self.app.apply()
        record, = self.app.backups.iterdir()
        self.app.file.write_bytes(b"external change")
        with patch("update.image_id", side_effect=lambda value: value[:-4]):
            with self.assertRaisesRegex(RuntimeError, "edited after upgrade"):
                self.app.rollback(record)
        self.assertEqual(self.app.file.read_bytes(), b"external change")


if __name__ == "__main__":
    unittest.main()

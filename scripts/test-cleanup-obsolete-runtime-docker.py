"""Safety regression for retention decisions; never contacts Docker."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("cleanup", Path(__file__).with_name("cleanup-obsolete-runtime-docker.py"))
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


def container(identity, name, status="exited", created="2020-01-01T00:00:00Z", **host):
    return {"Id": identity, "Name": "/"+name, "Created": created, "Image": "image-"+identity,
            "State": {"Status": status, "Running": status == "running", "ExitCode": 143}, "HostConfig": host}


class RetentionTest(unittest.TestCase):
    def plan(self, values):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/"evidence").mkdir()
            (root/"evidence/production-deployment.json").write_text(json.dumps({"status": "DEPLOYED", "mode": "production",
                "retired": {"api": "runtime-os-app-stage-api-1-before-current"}}))
            with patch.object(cleanup, "containers", return_value=values), patch.object(cleanup, "images", return_value=[]), patch.object(cleanup, "free_bytes", return_value=100):
                return cleanup.plan(root, 30)

    def test_running_explicit_rollback_canonical_stopped_and_unknown_services_are_retained(self):
        result = self.plan([
            container("active", "runtime-os-app-stage-api-1", "running"),
            container("rollback", "runtime-os-app-stage-api-1-before-current"),
            container("old", "runtime-os-app-stage-api-1-before-old", created="2019-01-01T00:00:00Z"),
            container("stopped", "runtime-os-app-stage-mcp-1"),
            container("unknown", "customer-database-backup"),
        ])
        self.assertEqual([row["id"] for row in result["containers"]], ["old"])
        self.assertTrue(result["preservesVolumes"])
        self.assertFalse(result["usesForce"])

    def test_namespace_dependencies_of_retained_sidecars_are_not_removed(self):
        for field in ["NetworkMode", "PidMode", "IpcMode"]:
            with self.subTest(field=field):
                result = self.plan([
                    container("proxy", "runtime-os-app-stage-node-proxy-1", "running", **{field: "container:dependency"}),
                    container("rollback", "runtime-os-app-stage-api-1-before-current"),
                    container("dependency", "runtime-os-app-stage-api-1-before-dependency", created="2019-01-01T00:00:00Z"),
                ])
                self.assertEqual(result["containers"], [])

    def test_recent_work_is_not_treated_as_expired_and_one_previous_preview_is_kept(self):
        result = self.plan([
            container("current", "sql-metadata-preview-api", "running"),
            container("recent", "sql-metadata-preview-api-new-build", created="2099-01-01T00:00:00Z"),
            container("previous", "sql-metadata-preview-api-previous"),
            container("obsolete", "sql-metadata-preview-api-obsolete", created="2019-01-01T00:00:00Z"),
        ])
        self.assertEqual([row["id"] for row in result["containers"]], ["obsolete"])


if __name__ == "__main__":
    unittest.main()

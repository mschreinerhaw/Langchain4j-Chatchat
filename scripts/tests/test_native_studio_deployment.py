import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("native_studio", Path(__file__).parents[1] / "deploy-native-studio.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class NativeStudioDeploymentTest(unittest.TestCase):
    def test_temporal_application_owns_studio_network_and_compose_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "private"
            private.mkdir()
            for name, data in [("executor.json", {"workerImage": "worker-image"}),
                               ("studio-executor.json", {}), ("studio-gateway.json", {})]:
                (private / name).write_text(json.dumps(data))
            (root / "compose.json").write_text(json.dumps({"services": {"executor": {}, "node": {}}}))
            application = root / "application.json"
            application.write_text(json.dumps({"services": {"temporal-control-api": {"image": "api-image"}}}))
            with patch.object(module.subprocess, "run") as command:
                module.deploy(root, root / "secret", "sha256:" + "a" * 64,
                              application_compose=application,
                              application_service="temporal-control-api",
                              application_project="runtime-native-temporal-candidate")
            studio = json.loads(application.read_text())["services"]["studio-gateway"]
            self.assertEqual(studio["network_mode"], "service:temporal-control-api")
            self.assertTrue(studio["depends_on"]["temporal-control-api"]["restart"])
            args = command.call_args.args[0]
            self.assertIn("runtime-native-temporal-candidate", args)
            self.assertNotIn("--env-file", args)

if __name__ == "__main__":
    unittest.main()

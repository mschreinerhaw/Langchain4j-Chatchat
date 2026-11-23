import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "activate_release", Path(__file__).resolve().parents[1] / "activate-verified-isolation.py"
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


OLD = "sha256:" + "a" * 64
NEW = "sha256:" + "b" * 64


class FixtureStack:
    project = "runtime-os-isolated-build"

    def __init__(self, failing=None, alter_environment=False):
        self.state = {"api": OLD}
        self.calls = []
        self.failing = failing
        self.alter_environment = alter_environment

    def config(self, override=None):
        config = {"services": {
            "api": {"image": OLD, "environment": {"DB": "isolated"}},
            "mcp": {"image": OLD},
        }}
        if override:
            images = json.loads(override.read_text())["services"]
            for service, image in images.items():
                config["services"][service].update(image)
            if self.alter_environment:
                config["services"]["api"]["environment"]["DB"] = "unexpected"
        return config

    def running_healthy_image(self, service, override=None):
        return self.state[service]

    def up(self, override, services):
        name = override.name
        self.calls.append(name)
        if self.failing == name:
            raise RuntimeError("Fixture health check failed")
        self.state = {
            service: config["image"] for service, config in self.config(override)["services"].items()
        }


class ActivateVerifiedIsolationTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.output = self.root / "release"

    def state(self):
        return json.loads((self.output / "activation-result.json").read_text())

    def test_verified_images_require_matching_receipt(self):
        receipt = {"images": {"api": {
            "Id": NEW, "verification": "PASSED", "jarSha256": "c" * 64,
            "resources": {"application.yml": {}},
        }}}
        self.assertEqual({"api": NEW}, release.verified_images(receipt, ["api"]))
        unverified = copy.deepcopy(receipt)
        unverified["images"]["api"]["verification"] = "FAILED"
        with self.assertRaisesRegex(ValueError, "Missing verified"):
            release.verified_images(unverified, ["api"])

    def test_health_failure_restores_previous_image(self):
        stack = FixtureStack(failing="candidate.override.json")
        with self.assertRaisesRegex(RuntimeError, "Previous healthy images restored"):
            release.activate(stack, {"api": NEW}, self.output)
        self.assertEqual(["candidate.override.json", "rollback.override.json"], stack.calls)
        self.assertEqual("ROLLED_BACK", self.state()["status"])
        self.assertEqual({"api": OLD}, self.state()["runningImages"])

    def test_records_success_only_after_image_verification(self):
        stack = FixtureStack()
        release.activate(stack, {"api": NEW}, self.output)
        self.assertEqual(["candidate.override.json"], stack.calls)
        self.assertEqual("ACTIVATED", self.state()["status"])
        self.assertEqual({"api": NEW}, self.state()["runningImages"])
        self.assertEqual(OLD, stack.state["mcp"])

    def test_receipt_write_failure_after_activation_restores_previous_image(self):
        stack = FixtureStack()
        replace = Path.replace

        def fail_activation_once(pending, evidence):
            if json.loads(pending.read_text())["status"] == "ACTIVATED":
                raise OSError("Fixture receipt write failed")
            return replace(pending, evidence)

        with patch.object(Path, "replace", fail_activation_once):
            with self.assertRaisesRegex(RuntimeError, "Previous healthy images restored"):
                release.activate(stack, {"api": NEW}, self.output)
        self.assertEqual({"api": OLD}, self.state()["runningImages"])

    def test_rejects_unrelated_compose_changes_before_start(self):
        stack = FixtureStack(alter_environment=True)
        with self.assertRaisesRegex(RuntimeError, "outside selected images"):
            release.activate(stack, {"api": NEW}, self.output)
        self.assertEqual([], stack.calls)
        self.assertEqual("CONFIG_MISMATCH", self.state()["status"])

    def test_rejects_nonisolated_project_and_external_compose_file(self):
        with self.assertRaisesRegex(ValueError, "isolated Compose project"):
            release.DockerStack(self.root, "runtime-os-app-stage", [], None, 1)
        env = self.root / ".env"
        env.write_text("")
        nested = self.root / "nested"
        nested.mkdir()
        with self.assertRaisesRegex(ValueError, "inside the isolated stack root"):
            release.DockerStack(nested, "runtime-isolated-test", [], env, 1)


if __name__ == "__main__":
    unittest.main()

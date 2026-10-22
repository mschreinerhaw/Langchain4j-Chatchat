import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('watchdog', Path(__file__).resolve().parents[2] / 'deploy/docker/application/coding-agent-watchdog.py')
watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watchdog)


class CodingAgentWatchdogTest(unittest.TestCase):
    def container(self, **state):
        return {'Id': 'container', 'Config': {'Labels': {'com.docker.compose.project': 'runtime-os-app-stage', 'com.docker.compose.service': 'coding-agent'}},
                'HostConfig': {'RestartPolicy': {'Name': 'unless-stopped'}},
                'State': {'Running': True, 'Health': {'Status': 'unhealthy'}, **state}}

    def decide(self, container, state=None):
        return watchdog.decision(container, state or {}, 1000, 'runtime-os-app-stage', 'coding-agent', 300)

    def test_only_unhealthy_running_worker_is_restarted(self):
        self.assertEqual('RESTART', self.decide(self.container()))
        for status in ('healthy', 'starting', None):
            self.assertEqual('NOOP', self.decide(self.container(Health={'Status': status})))

    def test_manual_stop_pause_and_docker_restart_are_respected(self):
        for state in ({'Running': False}, {'Paused': True}, {'Restarting': True}):
            self.assertEqual('NOOP', self.decide(self.container(**state)))

    def test_other_project_or_service_cannot_be_restarted(self):
        for label in ('com.docker.compose.project', 'com.docker.compose.service'):
            container = self.container(); container['Config']['Labels'][label] = 'other'
            self.assertEqual('SCOPE_MISMATCH', self.decide(container))

    def test_cooldown_prevents_restart_storms(self):
        self.assertEqual('COOLDOWN', self.decide(self.container(), {'containerId': 'container', 'lastAttemptAt': 800}))
        self.assertEqual('RESTART', self.decide(self.container(), {'containerId': 'container', 'lastAttemptAt': 600}))

    def test_automatic_process_exit_policy_is_required(self):
        container = self.container(); container['HostConfig']['RestartPolicy']['Name'] = 'no'
        self.assertEqual('RESTART_POLICY_REQUIRED', self.decide(container))

    def test_atomic_state_is_readable_and_replaced_without_temporary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            watchdog.persist(path, {'attempts': 1}); watchdog.persist(path, {'attempts': 2})
            self.assertEqual({'attempts': 2}, watchdog.json.loads(path.read_text()))
            self.assertEqual([path], list(path.parent.iterdir()))


if __name__ == '__main__':
    unittest.main()

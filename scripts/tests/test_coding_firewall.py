"""Validate rule construction without mutating a host firewall."""
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SOURCE = (Path(__file__).resolve().parents[2] / 'deploy/docker/application/restrict-coding-worker.sh').read_text(encoding='utf-8').split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]


class CodingFirewallTest(unittest.TestCase):
    def test_recreated_containers_replace_owned_stale_rules_atomically(self):
        def output(args, **kwargs):
            if args[:2] == ['docker', 'inspect']:
                ip = '10.77.0.8' if args[2] == 'worker' else '10.77.0.9'
                return json.dumps([{'NetworkSettings': {'Networks': {'isolated': {'IPAddress': ip}}}}])
            return '-N DOCKER-USER\n-N CLINE_APP_STAGE\n-A INPUT -s 10.77.0.2/32 -j CLINE_APP_STAGE\n-A INPUT -s 10.1.1.1 -j OTHER\n'
        with patch.dict('os.environ', {'CODING_NETWORK': 'isolated', 'CODING_WORKER_CONTAINER': 'worker', 'CODING_API_CONTAINER': 'api'}), \
                patch.object(subprocess, 'check_output', side_effect=output), patch.object(subprocess, 'run') as run:
            exec(compile(SOURCE, '<firewall>', 'exec'), {})
        run.assert_called_once()
        batch = run.call_args.kwargs['input']
        self.assertIn('-D INPUT -s 10.77.0.2/32 -j CLINE_APP_STAGE', batch)
        self.assertIn('-I INPUT 1 -s 10.77.0.8 -j CLINE_APP_STAGE', batch)
        self.assertIn('-d 10.77.0.9 -p tcp --dport 8080', batch)
        self.assertNotIn('OTHER', batch)
        self.assertTrue(batch.endswith('COMMIT\n'))

    def test_missing_network_never_changes_firewall(self):
        with patch.object(subprocess, 'check_output', return_value='[{"NetworkSettings":{"Networks":{}}}]'), \
                patch.object(subprocess, 'run') as run, self.assertRaises(KeyError):
            exec(compile(SOURCE, '<firewall>', 'exec'), {})
        run.assert_not_called()

    def test_validation_mode_never_commits_firewall_rules(self):
        value=json.dumps([{'NetworkSettings':{'Networks':{'isolated':{'IPAddress':'10.77.0.8'}}}}])
        other=value.replace('10.77.0.8','10.77.0.9')
        with patch.dict('os.environ',{'CODING_NETWORK':'isolated','CODING_FIREWALL_VALIDATE_ONLY':'1'}), \
                patch.object(subprocess,'check_output',side_effect=[value,other,'-N DOCKER-USER\n']), \
                patch.object(subprocess,'run') as run:
            exec(compile(SOURCE,'<firewall>','exec'),{})
        self.assertIn('--test',run.call_args.args[0])

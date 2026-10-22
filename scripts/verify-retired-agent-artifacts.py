"""Reject stale professional-Agent classes and A2A dependencies in final Spring Boot artifacts."""
import argparse
import io
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release-dir', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
retired = ['com/chatchat/common/runtime/agent/', 'com/chatchat/agents/runtime/federation/',
           'com/chatchat/integration/agent/', 'AgentAnalysisController.class',
           'DomainIntelligenceAnalysisWorkflow.class', 'FederatedAgentAnalysisWorkflow.class',
           'JpaAgentRegistry.class', 'ExternalEngineAgentRuntimeAdapter.class']
checks = {}
for service in ['api', 'mcp']:
    with zipfile.ZipFile(args.release_dir / (service + '.jar')) as artifact:
        names = artifact.namelist()
        assert not any('a2a' in name.lower() or 'java-json-canonicalization' in name.lower() for name in names if name.startswith('BOOT-INF/lib/')), service
        scanned = list(names)
        for name in names:
            if name.startswith('BOOT-INF/lib/chatchat-') and name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(artifact.read(name))) as library:
                    scanned.extend(library.namelist())
        assert not any(pattern in name for pattern in retired for name in scanned), service
        assert not any('DomainIntelligenceView' in name for name in names), service
        checks[service + 'NoRetiredClassesOrDependencies'] = True
report = {'status': 'PASSED', 'checks': checks}
args.output.write_text(json.dumps(report, indent=2))
print(json.dumps(report))

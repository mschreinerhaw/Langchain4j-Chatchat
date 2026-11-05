"""Opt-in paired real-provider benchmark. No production writes or generated-code execution.

Run from the repository root; the private model configuration is never copied to reports.
Both variants use native compaction. Only deferred tool disclosure changes.
"""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("variant", choices=["full", "deferred"])
parser.add_argument("--model-config", required=True, type=Path)
parser.add_argument("--model", required=True)
parser.add_argument("--parser-python", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
cmd = ["mvn.cmd", "-pl", "chatchat-api", "-am", "test",
       "-Dtest=CodingWorkspaceNodeIntegrationTest#authorizedRealModelComparison",
       "-Dsurefire.failIfNoSpecifiedTests=false", "-Dfrontend.skip=true", "-Dfrontend.install.skip=true",
       "-Dcoding.nodeE2e=true", "-Dcoding.contextOptimizationEnabled=true",
       "-Dcoding.deferredToolsEnabled=" + str(args.variant == "deferred").lower(),
       "-Dcoding.sqlglotPython=" + str(args.parser_python.resolve()),
       "-Dcoding.realModelConfig=" + str(args.model_config.resolve()),
       "-Dcoding.allowedModel=" + args.model,
       "-Dcoding.benchmarkOutput=" + str(args.output.resolve())]
with (args.output / "execution.log").open("w", encoding="utf-8") as log:
    result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=False)
print(args.variant, "exit:", result.returncode)
raise SystemExit(result.returncode)

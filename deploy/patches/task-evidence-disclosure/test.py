"""Run response disclosure regression tests against the exact deployed dependencies."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build-directory', type=Path, required=True)
parser.add_argument('--junit-console', type=Path, required=True)
parser.add_argument('--spring-test', type=Path, required=True)
args = parser.parse_args()
build = args.build_directory.resolve()
classpath = os.pathsep.join(map(str, [build / 'classes', build / 'dependencies/BOOT-INF/classes',
    build / 'dependencies/BOOT-INF/lib/*', args.junit_console.resolve(), args.spring_test.resolve()]))
tests = [Path(__file__).resolve().parents[3] / 'chatchat-api/src/test/java/com/chatchat/api/runtime/coding/knowledge/KnowledgeResponseAdviceTest.java']
output = build / 'test-classes'
output.mkdir(exist_ok=True)
subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(output), *map(str, tests)], check=True)
subprocess.run(['java', '-cp', str(output) + os.pathsep + classpath, 'org.junit.platform.console.ConsoleLauncher',
    'execute', '--select-class', 'com.chatchat.api.runtime.coding.knowledge.KnowledgeResponseAdviceTest', '--disable-banner'], check=True)

// Phase 1 evidence collector. No provider calls, production configuration, or SDK changes.
// node docs/testing/cline-cli-native-phase1.mjs <official-source-dir> <binary-package-dir>
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
const [source, binaryDir] = process.argv.slice(2).map(p => resolve(p));
if (!source || !binaryDir) throw new Error('Pass official source and binary package directories');
const out = resolve('docs/testing/evidence/cline-cli-native-phase1-20261107');
const scratch = resolve('target/cli-baseline/smoke');
mkdirSync(out, { recursive: true });
mkdirSync(scratch, { recursive: true });
const core = 'sdk/packages/core/src/';
const files = [
  'LICENSE', 'AGENTS.md', 'apps/cli/package.json', 'apps/cli/src/index.ts',
  'apps/cli/src/main.ts', 'apps/cli/src/session/session.ts',
  'apps/cli/src/runtime/run-agent.ts', 'apps/cli/src/commands/program.ts',
  'apps/cli/src/utils/approval.ts', 'apps/cli/src/utils/helpers.ts',
  'apps/cli/src/runtime/session-events.ts', 'apps/cli/src/acp/acpAgent.ts',
  'apps/cli/src/acp/permissions.ts', 'sdk/packages/core/package.json',
  'sdk/packages/agents/src/agent-runtime.ts',
  ...['ClineCore.ts', 'runtime/host/host.ts', 'runtime/host/local-runtime-host.ts',
    'runtime/orchestration/runtime-builder.ts', 'runtime/orchestration/session-runtime-orchestrator.ts',
    'extensions/tools/model-tool-routing.ts', 'extensions/config/user-instruction-service.ts',
    'extensions/config/user-instruction-config-loader.ts', 'extensions/mcp/manager.ts',
    'hub/client/index.ts', 'hub/server/hub-event-log.ts',
    'hub/discovery/workspace.ts', 'hub/discovery/defaults.ts',
    'session/session-versioning-service.ts', 'session/checkpoint-restore.ts',
    'session/services/persistence-service.ts'].map(p => core + p),
];
const sha = (b, algorithm = 'sha256', encoding = 'hex') => createHash(algorithm).update(b).digest(encoding);
const sourceFiles = files.map(path => {
  const bytes = readFileSync(join(source, path));
  return { path, bytes: bytes.length, sha256: sha(bytes) };
});
const baseline = resolve('target/cli-baseline');
const meta = JSON.parse(readFileSync(join(baseline, 'binary.json'), 'utf8').replace(/^\uFEFF/, ''));
const tarball = readFileSync(join(baseline, 'windows-curl.tgz'));
const actualIntegrity = 'sha512-' + sha(tarball, 'sha512', 'base64');
if (actualIntegrity !== meta.dist.integrity) throw new Error('Official binary tarball integrity mismatch');
const executable = join(binaryDir, 'package/bin/cline.exe');
const executableSha256 = sha(readFileSync(executable));
const run = (label, args, input, timeoutMs = 20000) => new Promise(resolveRun => {
  const taskRoot = join(scratch, label);
  mkdirSync(taskRoot, { recursive: true });
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
    !/^(CLINE_|ANTHROPIC_|OPENAI_|OPENROUTER_|GEMINI_|GOOGLE_|AZURE_|AWS_)/i.test(key)));
  Object.assign(env, { CLINE_SESSION_BACKEND_MODE: 'local', CLINE_DIR: join(taskRoot, 'config'),
    CLINE_DATA_DIR: join(taskRoot, 'data'), CLINE_PROVIDER_SETTINGS_PATH: join(taskRoot, 'providers.json'),
    CLINE_HOME: taskRoot, DO_NOT_TRACK: '1' });
  let stdout = '', stderr = '', timedOut = false, replied = false;
  const start = performance.now();
  const child = spawn(executable, args, { cwd: taskRoot, env, windowsHide: true });
  const timer = setTimeout(() => { timedOut = true; child.kill(); }, timeoutMs);
  child.stdout.on('data', chunk => {
    stdout += chunk;
    if (input && !replied) {
      for (const line of stdout.split('\n')) {
        try { if (JSON.parse(line).id === 1) { replied = true; child.stdin.end(); } } catch {}
      }
    }
  });
  child.stderr.on('data', chunk => { stderr += chunk; });
  child.on('error', error => { stderr += error.message; });
  child.on('close', (exitCode, signal) => {
    clearTimeout(timer);
    resolveRun({ label, args, exitCode, signal, timedOut, replied,
      durationMs: Math.round(performance.now() - start), stdout, stderr });
  });
  if (input) child.stdin.write(JSON.stringify(input) + '\n'); else child.stdin.end();
});
const checks = [];
checks.push(await run('version', ['--version']));
checks.push(await run('help', ['--help']));
checks.push(await run('acp-initialize', ['--acp', '--auto-approve', 'false'], {
  jsonrpc: '2.0', id: 1, method: 'initialize', params: {
    protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: 'runtime-phase1-audit', version: '1' },
  },
}));
writeFileSync(join(out, 'source-and-smoke.json'), JSON.stringify({
  recordedAt: new Date().toISOString(), sourceCommit: '071e1b25b48f9ef5dff7fc32085e6a16f02bf02e',
  releaseTag: 'cli-v3.0.74', binaryPackage: meta.name, binaryVersion: meta.version,
  registryIntegrity: meta.dist.integrity, actualIntegrity, tarballSha256: sha(tarball),
  executableSha256, sourceFiles, checks,
  limitations: ['Smoke checks do not prove agent task quality, tenant isolation, permissions, cancellation, or crash recovery.',
    'No model/provider request or A/B/C quality comparison was performed.',
    'Registry integrity verified; Sigstore certificate chain and reproducible binary build not independently verified.'],
}, null, 2) + '\n');
console.log(JSON.stringify(checks.map(({ label, exitCode, timedOut, replied }) => ({ label, exitCode, timedOut, replied }))));

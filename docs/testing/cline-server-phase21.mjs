// Read-only upstream audit collector. No installs, provider calls, or service startup.
// node docs/testing/cline-server-phase21.mjs <source-directory> <official-vsix> <source-tgz>
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve, join, dirname, basename } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
const args = process.argv.slice(2);
if (args.length !== 3) throw new Error('Expected source directory, VSIX, source tarball');
const [source, vsix, archive] = args.map(p => resolve(p));
const out = resolve('docs/testing/evidence/cline-server-phase21-20261108');
const pin = JSON.parse(readFileSync(join(out, 'release-pin.json'), 'utf8'));
const sha = b => createHash('sha256').update(b).digest('hex');
const fingerprint = (root, path) => {
  const bytes = readFileSync(join(root, path));
  return { path, bytes: bytes.length, sha256: sha(bytes) };
};
const v = 'apps/vscode/';
const c = 'sdk/packages/core/src/';
const files = [
  'AGENTS.md', 'LICENSE', 'package.json', 'bun.lock',
  '.github/workflows/ext-vscode-publish.yml',
  ...['package.json', 'LICENSE', 'esbuild.mjs', 'scripts/package-standalone.mjs',
    'scripts/test-hostbridge-server.ts', 'scripts/test-standalone-core-api-server.ts',
    'standalone/runtime-files/package.json', 'standalone/runtime-files/package-lock.json',
    'standalone/runtime-files/vscode/index.js', 'standalone/runtime-files/vscode/vscode-impls.js',
    'standalone/runtime-files/vscode/vscode-stubs.js',
    'src/extension.ts', 'src/common.ts', 'src/exports/index.ts', 'src/exports/cline.d.ts',
    'src/hosts/host-provider.ts', 'src/hosts/host-provider-types.ts',
    'src/hosts/external/host-bridge-auth.ts', 'src/hosts/external/ExternalWebviewProvider.ts',
    'src/hosts/external/ExternalEditPreview.ts', 'src/hosts/external/host-bridge-client-manager.ts',
    'src/standalone/cline-core.ts', 'src/standalone/protobus-service.ts',
    'src/standalone/hostbridge-client.ts', 'src/standalone/core-connection.ts',
    'src/standalone/core-connection-dispatcher.ts', 'src/standalone/vscode-context.ts',
    'src/standalone/vscode-context-utils.ts', 'src/core/controller/index.ts',
    'src/core/controller/task/newTask.ts', 'src/core/controller/task/askResponse.ts',
    'src/core/controller/task/cancelTask.ts', 'proto/cline/task.proto', 'proto/cline/state.proto',
    'proto/host/workspace.proto', 'proto/host/diff.proto', 'proto/host/env.proto', 'proto/host/window.proto',
    'src/sdk/SdkController.ts', 'src/sdk/vscode-session-host.ts', 'src/sdk/cline-session-factory.ts',
    'src/sdk/sdk-task-start-coordinator.ts', 'src/sdk/sdk-session-lifecycle.ts',
    'src/sdk/sdk-interaction-coordinator.ts', 'src/sdk/sdk-session-event-coordinator.ts',
    'src/sdk/sdk-diff-edit-coordinator.ts', 'src/sdk/vscode-runtime-builder.ts',
    'src/sdk/vscode-run-commands-tool.ts', 'src/sdk/vscode-terminal-execution-mode.ts',
    'src/sdk/vscode-file-read-executor.ts', 'src/services/mcp/McpHub.ts'].map(p => v + p),
  ...['ClineCore.ts', 'types/events.ts', 'runtime/host/host.ts', 'runtime/host/local-runtime-host.ts',
    'runtime/orchestration/runtime-builder.ts', 'runtime/orchestration/session-runtime-orchestrator.ts',
    'extensions/tools/model-tool-routing.ts', 'extensions/mcp/manager.ts',
    'extensions/config/user-instruction-config-loader.ts'].map(p => c + p),
  'sdk/packages/agents/src/agent-runtime.ts',
  ...['agents', 'core', 'shared', 'llms', 'sdk'].map(p => `sdk/packages/${p}/package.json`),
];
const asset = pin.release.assets.find(a => a.name === 'cline-4.1.26.vsix');
const actualVsix = sha(readFileSync(vsix));
if ('sha256:' + actualVsix !== asset.digest) throw new Error('Official VSIX digest mismatch');
// .NET reads the ZIP without executing or extracting its contents. Arguments travel in env.
const ps = `
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($env:AUDIT_VSIX_PATH)
try {
  $items = foreach ($name in @('extension/package.json','extension.vsixmanifest','extension/LICENSE.txt','extension/dist/extension.js','extension/dist/plugin-runtime/plugin-sandbox-bootstrap.js')) {
    $entry = $zip.GetEntry($name)
    if ($null -eq $entry) { throw "Missing ZIP entry: $name" }
    $stream = $entry.Open()
    try { $hasher = [System.Security.Cryptography.SHA256]::Create(); $digest = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-','').ToLowerInvariant() } finally { $stream.Dispose(); $hasher.Dispose() }
    @{path=$name;bytes=$entry.Length;sha256=$digest}
  }
  $reader=[System.IO.StreamReader]::new($zip.GetEntry('extension/package.json').Open())
  try { $manifest=$reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
  @{entries=@($items);manifest=$manifest;standaloneEntryPresent=($null -ne $zip.GetEntry('extension/dist-standalone/cline-core.js'))} | ConvertTo-Json -Depth 30 -Compress
} finally { $zip.Dispose() }
`;
const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], {
  encoding: 'utf8', windowsHide: true, env: { ...process.env, AUDIT_VSIX_PATH: vsix }, maxBuffer: 4 * 1024 * 1024,
});
if (result.status !== 0) throw new Error(result.stderr || 'Could not inspect VSIX');
const zip = JSON.parse(result.stdout.replace(/^\uFEFF/, ''));
const pkg = JSON.parse(readFileSync(join(source, v, 'package.json'), 'utf8'));
if (pkg.version !== '4.1.26' || zip.manifest.version !== pkg.version) throw new Error('Manifest version mismatch');
const sourceFiles = files.map(p => fingerprint(source, p));
const manifests = ['package.json', v + 'package.json', v + 'standalone/runtime-files/package.json',
  ...['agents', 'core', 'shared', 'llms', 'sdk'].map(p => `sdk/packages/${p}/package.json`)];
const runtimeFiles = ['coding-agent-node/package.json', 'coding-agent-node/src/runner.js',
  'coding-agent-node/src/sdk-adapter.js', 'coding-agent-node/src/native-skills.js', 'coding-agent-node/src/code-repair.js'];
const evidence = {
  recordedAt: new Date().toISOString(), sourceCommit: pin.tag.object.sha, releaseTag: pin.release.tag_name,
  vsix: { ...fingerprint(dirname(vsix), basename(vsix)), expectedDigest: asset.digest, verified: true, ...zip },
  sourceArchive: { bytes: readFileSync(archive).length, sha256: sha(readFileSync(archive)), url: pin.sourceArchiveUrl },
  extractionLimit: 'Windows tar failed to create upstream instruction/skill symlinks; regular audited source files are present and hashed. Not a complete executable checkout.',
  sourceFiles, componentManifests: manifests.map(path => ({ path, manifest: JSON.parse(readFileSync(join(source, path), 'utf8')) })),
  runtimeFiles: runtimeFiles.map(p => fingerprint(process.cwd(), p)),
  absentSourcePaths: [v + 'src/exports.ts', v + 'src/core/task/index.ts'].map(path => ({ path, exists: existsSync(join(source, path)) })),
  validation: { releaseDigest: 'PASS', manifestVersion: 'PASS', sourceReview: 'STATIC_ONLY',
    standaloneBuild: 'NOT_RUN_BUN_NOT_INSTALLED', realModelHttpParity: 'NOT_RUN_DESIGN_ONLY',
    binaryReproducibility: 'NOT_VERIFIED', transitiveLicenseAudit: 'PENDING_BUILD_SBOM' },
};
mkdirSync(out, { recursive: true });
writeFileSync(join(out, 'source-and-release.json'), JSON.stringify(evidence, null, 2) + '\n');
console.log(JSON.stringify({ sourceCommit: evidence.sourceCommit, vsixSha256: actualVsix, sourceFiles: sourceFiles.length, validation: evidence.validation }, null, 2));

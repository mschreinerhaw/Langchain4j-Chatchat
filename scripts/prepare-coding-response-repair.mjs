import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
const root = process.cwd();
const options = process.argv[2] ? JSON.parse(fs.readFileSync(process.argv[2], 'utf8')) : {};
const release = path.resolve(root, options.outputDirectory ?? 'target/coding-response-repair-release');
fs.mkdirSync(release, { recursive: true });
const manifest = {
  version: options.version ?? '2.4.15-model-response-repair-20261024',
  sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  apiBaseline: options.apiBaseline ?? '996c73d58d1f7721494b55efa3b008cef1f9929502787dba3fce46ef1e22f92b',
  nodeBaseline: options.nodeBaseline ?? {
    'runner.js': 'cf5ead7496f6ad13f209559a3a5ad6cbfc7113f241ca1752b58df7b26a651790',
    'runtime-model.js': '89b91bf1a322e5b470b193ca93352c58d69d1957436f26fd0416e427b992e141'
  },
  classes: []
};
const specs = options.classSpecs ?? [
  ['chatchat-api', 'com/chatchat/api/runtime/coding/cline/ClineModelCallbackController.class', 'c0798660840ce6e2907c2e7d1c698a642329bf1344f14b76fe69e9c8f31c74d1'],
  ['chatchat-api', 'com/chatchat/api/runtime/coding/cline/ClineRuntimeModelBridge.class', 'a489423a48dae4ef1b269d73c4983bbdea67d4652cdb8d8022111f0b51ebc583'],
  ['chatchat-agents', 'com/chatchat/agents/runtime/coding/DurableCodingTaskService.class', '7ce03ce3900ebfb4119f1e9fd31731d9409bb806e1ead7ee8bcc16d7d531fe07'],
  ['chatchat-agents', 'com/chatchat/agents/orchestration/AgentOrchestrationEngine.class', 'a25fe55c015dc19dbaadf811c4b59a13a881a5dbf5cbd2486607735a211943ef'],
  ['chatchat-agents', 'com/chatchat/agents/orchestration/model/AgentChatModelResolver.class', '427d1089a486c01d62be9e9a0e3357d4b2748b4adfe362150b4aea93a277e32e'],
  ['chatchat-platform-spring', 'com/chatchat/common/config/ModelResourceRegistry.class', '1f50e765b48d256dbfdae4b484312021b0809815bd4f471ffef858891035101e']
];
for (const [module, relative, baseline] of specs) {
  const file = 'classes/' + path.basename(relative);
  fs.mkdirSync(path.join(release, 'classes'), { recursive: true });
  fs.copyFileSync(path.join(root, module, 'target/classes', relative), path.join(release, file));
  manifest.classes.push({ entry: module === 'chatchat-api' ? 'BOOT-INF/classes/' + relative : 'BOOT-INF/lib/' + module + '-1.0.0-SNAPSHOT.jar',
    ...(module !== 'chatchat-api' ? { nested: relative } : {}), baseline, file });
}
for (const name of Object.keys(manifest.nodeBaseline)) {
  fs.mkdirSync(path.join(release, 'dist'), { recursive: true });
  fs.copyFileSync(path.join(root, 'coding-agent-node/dist', name), path.join(release, 'dist', name));
}
for (const folder of ['src', 'test', 'web', 'fixtures']) fs.cpSync(path.join(root, 'coding-agent-node', folder), path.join(release, 'verification', folder), { recursive: true });
fs.cpSync(path.join(root, 'deploy/docker/application'), path.join(release, 'templates'), { recursive: true });
for (const name of ['deploy-coding-response-repair.py', 'deploy-unified-management.py', 'application_image_build.py', 'verify-runtime-hang-stage.py'])
  fs.copyFileSync(path.join(root, 'scripts', name), path.join(release, name));
fs.writeFileSync(path.join(release, 'repair-manifest.json'), JSON.stringify(manifest, null, 2));
console.log(release);

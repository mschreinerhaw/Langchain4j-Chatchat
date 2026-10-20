import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

// Read tracked source only: never collect credentials, data rows, or local config bodies.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const git = (...args) => execFileSync('git', args, {cwd: root, encoding: 'utf8'}).trim();
const files = git('ls-files').split(/\r?\n/);
const rules = {
  ragImports: /import\s+dev\.langchain4j\.rag\./,
  documentVector: /(?:vectorSearch|vectorRerankHits|ensureVectorMappingIfNecessary|embeddingClient\.embed\()/,
  javaExploration: /(?:new ModelNativeAnalysisHarness|new com\.chatchat\..*ModelNativeAnalysisHarness|analyzeInterpretationPlanEvidence\()/,
  indirectRegistration: /(?:ServiceLoader\.load|Class\.forName|spring\.factories|AutoConfiguration\.imports)/,
};
const matches = Object.fromEntries(Object.keys(rules).map(key => [key, []]));
for (const path of files.filter(path => /\.(?:java|xml|ya?ml|properties|js|ts)$/.test(path)
    && !path.includes('package-lock') && !path.includes('src/test/'))) {
  let text;
  try { text = readFileSync(resolve(root, path), 'utf8'); } catch { continue; }
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    for (const [name, pattern] of Object.entries(rules)) {
      if (pattern.test(lines[index])) matches[name].push({path, line: index + 1});
    }
  }
}
const poms = files.filter(path => /(^|\/)pom.xml$/.test(path));
const modules = poms.map(path => {
  const text = readFileSync(resolve(root, path), 'utf8');
  return {path, dependencies: [...text.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(match => ({
    groupId: match[1].match(/<groupId>(.*?)<\/groupId>/)?.[1],
    artifactId: match[1].match(/<artifactId>(.*?)<\/artifactId>/)?.[1],
    scope: match[1].match(/<scope>(.*?)<\/scope>/)?.[1] ?? 'compile',
  }))};
});
const report = {schemaVersion: 'runtime_os.refactor.audit.v1', capturedAt: new Date().toISOString(),
  commit: git('rev-parse', 'HEAD'), branch: git('branch', '--show-current'),
  backupCommit: git('rev-parse', 'backup/runtime-os-before-coding-refactor'),
  tagCommit: git('rev-parse', 'runtime-os-pre-coding-refactor-20261020^{commit}'),
  status: git('status', '--porcelain'), modules, matches,
  note: 'Static matches require call-site review; an embedding used by Skills/MCP is not automatically legacy RAG.'};
const output = resolve(root, process.argv[2] ?? 'target/runtime-os-refactor-20261020/source-audit.json');
mkdirSync(dirname(output), {recursive: true});
writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({output, commit: report.commit, modules: modules.length,
  matches: Object.fromEntries(Object.entries(matches).map(([key, value]) => [key, value.length]))}));

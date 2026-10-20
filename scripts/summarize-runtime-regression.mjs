import {readFileSync, readdirSync, statSync, mkdirSync, writeFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const since = Date.parse(process.argv[2]);
const until = process.argv[4] ? Date.parse(process.argv[4]) : Infinity;
if (!Number.isFinite(since) || !process.argv[3]) throw new Error('Usage: node summarize-runtime-regression.mjs ISO_START OUTPUT');
const suites = [];
for (const module of readdirSync(root).filter(name => name.startsWith('chatchat-'))) {
  const directory = resolve(root, module, 'target/surefire-reports');
  let files;
  try { files = readdirSync(directory); } catch { continue; }
  for (const file of files.filter(name => /^TEST-.*\.xml$/.test(name))) {
    const path = resolve(directory, file);
    if (statSync(path).mtimeMs < since || statSync(path).mtimeMs > until) continue;
    const xml = readFileSync(path, 'utf8');
    const header = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '';
    const attr = (key, value = header) => value.match(new RegExp('\\b' + key + '="([^"]*)"'))?.[1];
    suites.push({module, name: attr('name'), tests: Number(attr('tests')), failures: Number(attr('failures')),
      errors: Number(attr('errors')), skipped: Number(attr('skipped')), seconds: Number(attr('time')),
      failedCases: [...xml.matchAll(/<testcase\b([^>]*?)>([\s\S]*?)<\/testcase>/g)]
        .filter(match => /<(?:failure|error)\b/.test(match[2]))
        .map(match => ({name: attr('name', match[1]), type: match[2].match(/<(?:failure|error)\b[^>]*type="([^"]*)"/)?.[1]}))});
  }
}
const totals = suites.reduce((result, suite) => {
  for (const key of ['tests', 'failures', 'errors', 'skipped']) result[key] += suite[key];
  return result;
}, {tests: 0, failures: 0, errors: 0, skipped: 0});
const report = {schemaVersion: 'runtime_os.regression.v1', since: new Date(since).toISOString(),
  until: Number.isFinite(until) ? new Date(until).toISOString() : null,
  capturedAt: new Date().toISOString(), totals, suites,
  acceptance: 'Maven exit status, native crashes, missing modules, and required integration gates must also pass; XML totals alone are insufficient.'};
const output = resolve(root, process.argv[3]);
mkdirSync(dirname(output), {recursive: true});
writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({output, suites: suites.length, ...totals}));

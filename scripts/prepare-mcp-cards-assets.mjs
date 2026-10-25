// Reuse the running frontend runtime; change only the MCP view and its import references.
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join, basename } from 'node:path';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const [baseline, build, output, version] = process.argv.slice(2);
assert(baseline && build && output && /^[a-z0-9-]+$/i.test(version));
const find = (directory, pattern) => {
  const names = readdirSync(directory).filter(name => pattern.test(name));
  assert.equal(names.length, 1, `Expected one matching asset in ${directory}`);
  return names[0];
};
const oldView = find(baseline, /^McpCenterView-.*\.js$/);
const oldCss = find(baseline, /^McpCenterView-.*\.css$/);
const newView = find(build, /^McpCenterView-.*\.js$/);
const newCss = find(build, /^McpCenterView-.*\.css$/);
const oldCode = readFileSync(join(baseline, oldView), 'utf8');
let code = readFileSync(join(build, newView), 'utf8');
const imports = source => {
  const match = source.match(/^import\{([^}]+)\}from"\.\/(index-[^"]+\.js)";/);
  assert(match, 'Unexpected MCP view import layout');
  return { statement: match[0], names: match[1], main: match[2], aliases: match[1].split(',').map(item => item.split(' as ')[0]) };
};
const previous = imports(oldCode), current = imports(code);
// These stable export slots are shared by the old and new view. Verify before rebinding.
assert.deepEqual(current.aliases, previous.aliases);
const main = readFileSync(join(baseline, previous.main), 'utf8');
const iconAlias = current.names.match(/(?:^|,)c as ([\w$]+)/)?.[1];
assert(iconAlias && /P\("blocks",/.test(oldCode), 'Unexpected legacy Lucide factory');
const rebound = current.names.replace(`c as ${iconAlias}`, 'c as __mcpLegacyIcon');
code = code.replace(current.statement, `import{${rebound}}from"./${previous.main}";const ${iconAlias}=definition=>__mcpLegacyIcon(definition.name,definition.node);`);
const nextView = `McpCenterView-${version}.js`, nextCss = `McpCenterView-${version}.css`;
assert(main.includes(oldView) && main.includes(oldCss));
const nextMain = main.replaceAll(oldView, nextView).replaceAll(oldCss, nextCss);
const assetDir = join(output, 'assets');
mkdirSync(assetDir, { recursive: true });
writeFileSync(join(assetDir, previous.main), nextMain);
writeFileSync(join(assetDir, nextView), code);
writeFileSync(join(assetDir, nextCss), readFileSync(join(build, newCss)));
const sha256 = value => createHash('sha256').update(value).digest('hex');
writeFileSync(join(output, 'asset-manifest.json'), JSON.stringify({
  main: previous.main, oldView, oldCss, nextView, nextCss,
  baselineMainSha256: sha256(main), mainSha256: sha256(nextMain),
  viewSha256: sha256(code), cssSha256: sha256(readFileSync(join(build, newCss))),
  preservedRuntime: true, legacyIconAdapter: true,
}, null, 2));
console.log(JSON.stringify({ main: basename(previous.main), nextView, nextCss, importedSlotsVerified: current.aliases.length }));

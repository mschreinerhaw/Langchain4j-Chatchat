// Explicit, pinned, read-only import. Never execute upstream scripts or hooks.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, readdirSync, lstatSync, copyFileSync, existsSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import { parseSkillConfigFromMarkdown } from '@cline/core';

const commit = '3f5b461b2314e01c400564051e92469114c5b68f';
const upstream = resolve(process.argv[2] || 'target/microsoft-skills-upstream');
if (execFileSync('git', ['-C', upstream, 'rev-parse', 'HEAD'], { encoding:'utf8' }).trim() !== commit)
  throw new Error('Upstream checkout is not the reviewed commit');
const selections = [
  ['azure-kusto', '.github/plugins/azure-skills/skills/azure-kusto', 'Azure Data Explorer / KQL; requires an independently authorized ADX connection. Not a MySQL SQL skill.'],
  ['azure-ai-ml-py', '.github/plugins/azure-sdk-python/skills/azure-ai-ml-py', 'Azure ML SDK v2 methodology; Azure services and SDK are not installed or authorized by this package.'],
  ['mcp-builder', '.github/skills/mcp-builder', 'MCP development methodology; published SQL services still use the existing Runtime release controls.'],
  ['skill-creator', '.github/skills/skill-creator', 'Native Skill authoring methodology; does not invoke or replace the Runtime domain compiler.'],
];
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const catalogPath = resolve('coding-agent-node/skills/catalog.json');
const existingCatalog = JSON.parse(readFileSync(catalogPath));
const catalog = existingCatalog.filter(c => c.publisher !== 'Microsoft');
for (const [name, path, compatibility] of selections) {
  const source = join(upstream, path), destination = resolve('coding-agent-node/skills', name);
  if (existsSync(destination)) {
    const previous=existingCatalog.find(c=>c.publisher==='Microsoft'&&c.name===name);
    if(!process.argv.includes('--refresh')||previous?.provenance.commit!==commit)
      throw new Error(`Destination already exists: ${name}; review updates explicitly`);
    // Refresh only unchanged files owned by the previous pinned import. No deletion.
    for(const file of [{path:'SKILL.md',sha256:previous.provenance.sha256},...previous.resources])
      if(hash(readFileSync(join(destination,file.path)))!==file.sha256)throw new Error(`Locally modified vendor file: ${name}/${file.path}`);
  }
  mkdirSync(destination, { recursive:true });
  const files = [];
  function copy(directory, relative='') {
    for (const entry of readdirSync(directory)) {
      const file = join(directory, entry), local = relative ? `${relative}/${entry}` : entry;
      const stat = lstatSync(file);
      if (stat.isSymbolicLink()) throw new Error(`Symlink requires review: ${local}`);
      if (stat.isDirectory()) { mkdirSync(join(destination,local),{recursive:true}); copy(file, local); }
      else { const bytes=execFileSync('git',['-C',upstream,'cat-file','blob',`${commit}:${path}/${local}`]); writeFileSync(join(destination,local),bytes); files.push({path:local,sha256:hash(bytes),size:bytes.length}); }
    }
  }
  copy(source);
  const license=execFileSync('git',['-C',upstream,'cat-file','blob',`${commit}:LICENSE`]); writeFileSync(join(destination,'LICENSE.microsoft'),license);
  files.push({path:'LICENSE.microsoft',sha256:hash(license),size:license.length});
  const original=readFileSync(join(destination,'SKILL.md'),'utf8'), parsed=parseSkillConfigFromMarkdown(original,name);
  if(parsed.name!==name||!parsed.description||!parsed.instructions)throw new Error('Invalid original Skill');
  catalog.push({id:`builtin:microsoft:${name}`,name,description:parsed.description,
    version:`upstream-${commit.slice(0,12)}`,origin:'SYSTEM_BUILTIN',category:'Microsoft',publisher:'Microsoft',
    nativeOriginal:true,compatibility,
    provenance:{repository:'https://github.com/microsoft/skills',directory:path,commit,license:'MIT',importedAt:'2026-10-31',sha256:hash(Buffer.from(original))},
    resources:files.filter(file=>file.path!=='SKILL.md')});
}
writeFileSync(catalogPath,JSON.stringify(catalog,null,2)+'\n');
console.log(`Vendored ${selections.length} original Microsoft Skill packages at ${commit}`);

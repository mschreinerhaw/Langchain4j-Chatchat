import * as core from '@cline/core';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const hash=(bytes,algorithm='sha256',encoding='hex')=>createHash(algorithm).update(bytes).digest(encoding);
const comparison=resolve(process.argv[2] ?? '../target/cline-skills-sdk-comparison');
const output=process.argv[3];
const entries=[];
for(const name of ['agents','core','sdk']){
  const registry=await fetch('https://registry.npmjs.org/@cline%2f'+name).then(r=>{if(!r.ok)throw Error('Registry '+r.status);return r.json();});
  const installed=JSON.parse(await readFile(new URL('../node_modules/@cline/'+name+'/package.json',import.meta.url),'utf8'));
  const current=registry.versions[installed.version],latestVersion=registry['dist-tags'].latest,latest=registry.versions[latestVersion];
  const latestTypes=await readFile(join(comparison,name,'package','dist','index.d.ts'));
  const archive=await readFile(join(comparison,name+'-'+latestVersion+'.tgz'));
  const [algorithm,expected]=latest.dist.integrity.split('-');
  if(hash(archive,algorithm,'base64')!==expected)throw Error('Published archive integrity mismatch');
  entries.push({package:'@cline/'+name,installedVersion:installed.version,installedGitHead:current.gitHead,
    latestStable:latestVersion,latestPublishedAt:registry.time[latestVersion],latestGitHead:latest.gitHead,
    tarball:latest.dist.tarball,integrity:latest.dist.integrity,downloadIntegrityVerified:true,
    installedTypesSha256:hash(await readFile(new URL('../node_modules/@cline/'+name+'/dist/index.d.ts',import.meta.url))),
    latestTypesSha256:hash(latestTypes)});
}
const latestCoreTypes=await readFile(join(comparison,'core','package','dist','index.d.ts'),'utf8');
const apiNames=['loadAgentPluginPackages','createSkillsConfigDefinition','createUserInstructionConfigService','createDefaultTools','createDefaultExecutors','ClineCore'];
const publicApis=apiNames.map(name=>({name,installedRuntimeExport:typeof core[name],latestStableDeclared:latestCoreTypes.includes(name)}));
const service=core.createUserInstructionConfigService({skills:{directories:[fileURLToPath(new URL('../skills',import.meta.url))],
  includePluginSkills:false,pluginPaths:[],agentPluginSkills:[]},rules:{directories:[]},workflows:{directories:[]}});
let extensionProbe;
try {
  await service.start();
  const extension=service.createExtension({includeRules:false,includeSkills:true,includeWorkflows:false,registerSkillsTool:false});
  const registry=core.createContributionRegistry({extensions:[extension]});await registry.initialize();
  const snapshot=registry.getRegistrySnapshot(),executor=service.createSkillsExecutor();
  const tool=core.createDefaultTools({executors:{skills:executor},enableSkills:true,enableReadFiles:false,
    enableBash:false,enableSearch:false,enableWebFetch:false,enableEditor:false,enableApplyPatch:false,
    enableAskQuestion:false,enableSubmitAndExit:false}).find(t=>t.name==='skills');
  const purpose=executor.configuredSkills.find(s=>s.description)?.description;
  extensionProbe={scope:'Installed official service extension and unmodified native tool; no model or body invocation',
    discoveredSkills:service.listRecords('skill').length,capabilities:extension.manifest.capabilities,
    hookNames:Object.keys(extension.hooks??{}),registeredCommands:snapshot.commands.length,
    registeredRules:snapshot.rules.length,registeredMessageBuilders:snapshot.messageBuilder.length,
    purposeInNativeToolDescription:!!purpose&&tool.description.includes(purpose),
    purposeInNativeSkillParameter:!!purpose&&tool.inputSchema.properties.skill.description.includes(purpose)};
} finally {service.stop();}
const result={capturedAt:new Date().toISOString(),registry:'https://registry.npmjs.org',packages:entries,publicApis,
  extensionProbe,
  latestStableEvidence:'PUBLISHED_TARBALL_INTEGRITY_AND_PUBLIC_TYPES_NOT_RUNTIME_UPGRADE',
  chosenVersion:'0.0.93',reason:'The installed version provides the tested native discovery, instruction service, Skills executor and tool construction APIs; no ClineCore migration or SDK upgrade required.'};
const text=JSON.stringify(result,null,2)+'\n';if(output)await writeFile(output,text);else process.stdout.write(text);

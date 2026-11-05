/** Reproducible worker delivery bundle; fails before upload if regression inputs are missing. */
import { existsSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, relative, dirname, isAbsolute } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const root=fileURLToPath(new URL('../',import.meta.url));
const output=resolve(root,process.argv[2] ?? 'target/native-skills-worker.tar.gz');
const destination=relative(root,output);
if(destination.startsWith('..')||isAbsolute(destination))throw Error('Worker bundle must stay in this workspace');
const paths=['src','dist','skills','test','fixtures','integration','scripts','web','package.json','package-lock.json','tsconfig.json','tsconfig.web.json']
  .map(p=>'coding-agent-node/'+p).concat('chatchat-common/src/main/resources/runtime');
const required=['coding-agent-node/src/native-skills.js','coding-agent-node/dist/native-skills.js',
  'coding-agent-node/test/native-skills.test.js','chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py',
  'chatchat-common/src/main/resources/runtime/report-block.schema.json'];
for(const path of [...paths,...required])if(!existsSync(resolve(root,path)))throw Error('Missing bundle input: '+path);
for(const name of ['skills.js','skill-disclosure.js'])if(existsSync(resolve(root,'coding-agent-node/dist',name)))throw Error('Retired loader remains: '+name);
mkdirSync(dirname(output),{recursive:true});
execFileSync('tar',['-czf',output,...paths],{cwd:root,stdio:['ignore','pipe','pipe']});
const entries=new Set(execFileSync('tar',['-tzf',output],{encoding:'utf8'}).trim().split(/\r?\n/));
for(const path of required)if(!entries.has(path))throw Error('Required regression input missing from archive: '+path);
const proof={sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),
  archiveSha256:createHash('sha256').update(readFileSync(output)).digest('hex'),requiredEntries:required,entries:entries.size};
writeFileSync(output+'.json',JSON.stringify(proof,null,2)+'\n');console.log(JSON.stringify(proof));

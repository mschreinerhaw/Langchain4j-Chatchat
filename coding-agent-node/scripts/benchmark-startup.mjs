/** Fresh-process measurements. File bytes are measured, not provider token usage. */
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
const [output,count='10']=process.argv.slice(2);
if(!output)throw Error('Usage: benchmark-startup.mjs output.json [samples]');
const child=`import fs from 'node:fs';import {syncBuiltinESMExports} from 'node:module';
let bodyReads=0,referenceReads=0,readBytes=0;const read=fs.readFileSync;
fs.readFileSync=function(path,...args){const value=read.call(this,path,...args),name=String(path).replaceAll('\\\\','/');
 if(name.includes('/skills/')&&!name.endsWith('/catalog.json')){if(name.endsWith('SKILL.md'))bodyReads++;else referenceReads++;readBytes+=Buffer.byteLength(value);}return value;};
syncBuiltinESMExports();const start=performance.now();const {taskSkills}=await import('./src/skills.js');const imported=performance.now();
const view=taskSkills();const indexed=performance.now();const discovery={bodyReads,referenceReads,readBytes};
view.readBuiltin('builtin:python-data-analysis');const disclosed=performance.now();
console.log(JSON.stringify({importMs:imported-start,catalogMs:indexed-imported,disclosureMs:disclosed-indexed,
 catalogBytes:Buffer.byteLength(JSON.stringify(view.catalog)),discovery,afterDisclosure:{bodyReads,referenceReads,readBytes}}));`;
const samples=[];
for(let i=0;i<Number(count);i++){
 const result=spawnSync(process.execPath,['--input-type=module','-e',child],{encoding:'utf8'});
 if(result.status!==0)throw Error(result.stderr);samples.push(JSON.parse(result.stdout));
}
const quantile=(values,q)=>[...values].sort((a,b)=>a-b)[Math.max(0,Math.ceil(values.length*q)-1)];
const report={schemaVersion:'coding.startup.local.v1',sourceCommit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),
 scope:'FRESH_NODE_PROCESS_WARM_OS_CACHE_NOT_PROVIDER_LATENCY',nodeVersion:process.version,samples,
 percentiles:Object.fromEntries(['importMs','catalogMs','disclosureMs'].map(key=>[key,Object.fromEntries([.5,.95,.99].map(q=>['p'+q*100,quantile(samples.map(s=>s[key]),q)]))]))};
writeFileSync(output,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({sourceCommit:report.sourceCommit,percentiles:report.percentiles,discovery:samples[0].discovery}));

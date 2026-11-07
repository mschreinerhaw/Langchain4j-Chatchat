/** Reviewed class/dist overlay. Never changes runtime settings, model configuration or SDK packages. */
import {mkdir,readFile,writeFile,readdir,copyFile,cp,mkdtemp} from 'node:fs/promises';
import {resolve,dirname,basename} from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const root=process.cwd(),sha=b=>createHash('sha256').update(b).digest('hex');
const sources={
 'chatchat-common':[
  'com/chatchat/common/runtime/coding/CodingRuntimeContext',
  'com/chatchat/common/runtime/coding/CodingTaskContract',
  'com/chatchat/common/runtime/coding/NativeCodeWorkspace'],
 'chatchat-agents':['com/chatchat/agents/runtime/coding/DurableCodingTaskService'],
 'chatchat-api':[
  'com/chatchat/api/runtime/coding/cline/ClineCodingAdapter',
  'com/chatchat/api/runtime/coding/cline/ClineRuntimeModelBridge',
  'com/chatchat/api/runtime/coding/cline/ClineModelCallbackController',
  'com/chatchat/api/runtime/coding/nativecode/NativeCodingMcpService',
  'com/chatchat/api/runtime/coding/nativecode/NativeCodingTaskService',
  'com/chatchat/api/runtime/coding/nativecode/NativeCodingWorkspaceService'],
};
await mkdir(resolve(root,'target'),{recursive:true});
const stage=await mkdtemp(resolve(root,'target/p1-mcp-overlay-')),manifest={
 schemaVersion:'runtime_os.p1.overlay.v1',sourceCommit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),
 sdkVersion:'0.0.93',classes:[],sources:[],dist:[]};
for(const [module,families]of Object.entries(sources))for(const family of families){
 const source=module+'/src/main/java/'+family+'.java';manifest.sources.push({path:source,sha256:sha(await readFile(resolve(root,source))),
  gitBlobSha256:sha(execFileSync('git',['show','HEAD:'+source]))});
 const directory=resolve(root,module,'target/classes',dirname(family)),base=basename(family);
 const names=(await readdir(directory)).filter(n=>n===base+'.class'||n.startsWith(base+'$')&&n.endsWith('.class'));
 if(!names.includes(base+'.class'))throw Error('Missing compiled class '+source);
 for(const name of names){const path=module+'/'+dirname(family)+'/'+name,target=resolve(stage,'classes',path);
  await mkdir(dirname(target),{recursive:true});await copyFile(resolve(directory,name),target);
  manifest.classes.push({path,sha256:sha(await readFile(target))});}
}
async function dist(directory,prefix=''){
 for(const entry of await readdir(directory,{withFileTypes:true})){
  const name=prefix+entry.name,path=resolve(directory,entry.name);
  if(entry.isDirectory())await dist(path,name+'/');
  else if(entry.isFile())manifest.dist.push({path:name,sha256:sha(await readFile(path))});
  else throw Error('Worker links/special files are forbidden');
 }
}
await dist(resolve(root,'coding-agent-node/dist'));
await cp(resolve(root,'coding-agent-node/dist'),resolve(stage,'dist'),{recursive:true});
const experiment='p1-observed-bootstrap.mjs';
await mkdir(resolve(stage,'experiments'),{recursive:true});
await copyFile(resolve(root,'coding-agent-node/experiments',experiment),resolve(stage,'experiments',experiment));
manifest.experiments=[{path:experiment,sha256:sha(await readFile(resolve(stage,'experiments',experiment)))}];
await writeFile(resolve(stage,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
const archive=stage+'.tgz';execFileSync('tar',['-czf',archive,'-C',stage,'.']);
console.log(JSON.stringify({archive,sha256:sha(await readFile(archive)),sourceCommit:manifest.sourceCommit,classes:manifest.classes.length,distFiles:manifest.dist.length}));

/** SDK reference cohort only: default executors on a private, single-task container tmpfs.
 * This module is never imported by the product. It keeps Runtime admission,
 * immutable versions and Sandbox execution so model/budget/verification stay comparable.
 */
import {createDefaultTools,createDefaultExecutors,CommandExitError} from '@cline/sdk';
import {mkdir,writeFile,readFile,readdir,rm,lstat} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {createHash} from 'node:crypto';
import {codeManifest} from '../dist/native-workspace.js';

const root='/workspace/code';
let active=false;
const sha=value=>createHash('sha256').update(value).digest('hex');
export async function createNativeCodeWorkspace(options){
  if(process.env.RUNTIME_ACCEPTANCE_NATIVE_REFERENCE!=='SDK_0093'||active)throw Error('Single-task SDK reference container required');
  if((await readdir(root)).length)throw Error('Reference project tmpfs must start empty');
  active=true;
  let files={...options.files},manifest=options.restoredManifest,uncertain=false;
  const ordinals=new Map();
  const binding=sha(JSON.stringify([options.scope.tenantId,options.scope.userId,options.scope.runId]));
  manifest??=codeManifest(options.taskId,files,0,null,binding);
  async function clear(){for(const name of await readdir(root))await rm(join(root,name),{recursive:true,force:true});}
  async function materialize(){for(const[path,source]of Object.entries(files)){
    const target=join(root,path);await mkdir(dirname(target),{recursive:true});await writeFile(target,source);
  }}
  async function inventory(){
    const values={};
    async function walk(directory,prefix=''){
      for(const name of await readdir(directory)){
        const target=join(directory,name),path=prefix+name,stat=await lstat(target);
        if(stat.isSymbolicLink())throw Error('Reference snapshot cannot publish a link');
        if(stat.isDirectory())await walk(target,path+'/');
        else if(stat.isFile())values[path]=await readFile(target,'utf8');
        else throw Error('Reference snapshot cannot publish a special file');
      }
    }
    await walk(root);return Object.fromEntries(Object.entries(values).sort(([a],[b])=>a<b?-1:a>b?1:0));
  }
  async function authorize(action){if(uncertain)throw Error('WORKSPACE_RELOAD_REQUIRED');await options.authorize({action,taskId:options.taskId,manifest});}
  await materialize();
  const native=createDefaultExecutors({fileRead:{maxFileSizeBytes:256000},search:{maxResults:100}});
  const edit=name=>async(input,cwd,context)=>{
    await authorize('EDIT');
    const result=await native[name](input,cwd,context),next=await inventory();
    if(JSON.stringify(next)!==JSON.stringify(files)){
      const nextManifest=codeManifest(options.taskId,next,manifest.version+1,manifest.sha256,binding);
      try{const saved=await options.commit({kind:'EDIT',taskId:options.taskId,manifest:nextManifest,files:next,operationId:context.toolCallId,
        nativeDiff:{tool:name==='editor'?'editor':'apply_patch',intentHash:sha(JSON.stringify(input)),result}});
        if(saved.manifestHash!==nextManifest.sha256)throw Error('Reference durable edit acknowledgment differs');}
      catch(error){uncertain=true;throw error;}
      files=next;manifest=nextManifest;
    }
    return result;
  };
  const executors={...native,editor:edit('editor'),applyPatch:edit('applyPatch'),
    readFile:async(input,context)=>{await authorize('READ');return native.readFile(input,context);},
    search:async(query,cwd,context)=>{await authorize('SEARCH');return native.search(query,cwd,context);},
    bash:async(command,_cwd,context)=>{
      await authorize('EXECUTE');
      const actual=await inventory();
      if(codeManifest(options.taskId,actual,manifest.version,manifest.parent,binding).sha256!==manifest.sha256)throw Error('Reference files changed without an archived version');
      const ordinal=(ordinals.get(context.toolCallId)??0)+1;ordinals.set(context.toolCallId,ordinal);
      const operationId=context.toolCallId+':command:'+ordinal;
      const frozen=await options.commit({kind:'EXECUTION_SNAPSHOT',taskId:options.taskId,manifest,files:actual,operationId});
      if(frozen.manifestHash!==manifest.sha256)throw Error('Reference frozen snapshot acknowledgment differs');
      const receipt=await options.runCommand({taskId:options.taskId,manifest,files:actual,command,operationId,signal:context.signal??options.signal});
      if(receipt.manifestHash!==manifest.sha256||receipt.executorStopped!==true||!receipt.stopProofRef||receipt.resultAvailable===false)throw Error('Reference execution has no matching stopped result');
      if(receipt.exitCode!==0)throw new CommandExitError(receipt.exitCode,JSON.stringify(receipt));
      return JSON.stringify(receipt);
    }};
  const tools=createDefaultTools({cwd:root,shell:'bash',executors,enableReadFiles:true,enableSearch:true,
    enableEditor:options.strategy==='editor',enableApplyPatch:options.strategy==='patch',enableBash:true,
    enableWebFetch:false,enableSkills:false,enableAskQuestion:false,enableSubmitAndExit:false});
  return{tools,root,readFileExecutor:executors.readFile,snapshot:()=>({manifest,files:{...files}}),requiresReload:()=>uncertain,
    async refresh(snapshot){await clear();files={...snapshot.files};manifest=snapshot.manifest;await materialize();uncertain=false;},
    async dispose(){try{await clear();}finally{active=false;}}};
}

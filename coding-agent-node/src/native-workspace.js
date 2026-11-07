/** Task-scoped native SDK workspace; requires a governed host to use it.
 * SDK owns reading/search/edit algorithms. Host owns authorization, immutable
 * versions and execution. A materialized directory is a disposable cache.
 */
import { createDefaultTools, createDefaultExecutors, createEditorExecutor,
  createApplyPatchExecutor, computePatchChanges, CommandExitError, PATCH_MARKERS } from '@cline/sdk';
import { mkdtemp, mkdir, writeFile, readFile, readdir, lstat, realpath, rename, rm } from 'node:fs/promises';
import { resolve, join, dirname, relative, isAbsolute } from 'node:path';
import { createHash,randomUUID } from 'node:crypto';
import {codingValueHash} from './coding-hash.js';

const sha = value => createHash('sha256').update(value).digest('hex');
function codePath(value) {
  if (typeof value !== 'string' || value.length > 160 || value.includes('\\')
      || !/^[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*$/.test(value)
      || value.split('/').some(part=>['.','..','.git'].includes(part.toLowerCase())||part.endsWith('.')
        ||/^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$/i.test(part)))
    throw new TypeError('CODE_PATH_DENIED');
  return value;
}
function normalizeFiles(files) {
  if (!files || typeof files !== 'object' || Array.isArray(files)) throw new TypeError('Invalid code files');
  const entries = Object.entries(files).sort(([a],[b]) => a<b?-1:a>b?1:0);
  if (entries.length > 32) throw new TypeError('Code file count exceeds contract');
  if(new Set(entries.map(([path])=>path.toLowerCase())).size!==entries.length)throw new TypeError('Case-colliding code paths');
  let bytes = 0;
  for (const [path,source] of entries) {
    codePath(path);
    if (typeof source !== 'string' || source.includes('\0')||Buffer.from(source,'utf8').toString('utf8')!==source) throw new TypeError('Invalid UTF-8 text source');
    bytes += Buffer.byteLength(source);
  }
  if (bytes > 256000) throw new TypeError('Code bytes exceed contract');
  return Object.fromEntries(entries);
}
export function codeManifest(taskId, files, version = 0, parent = null, scopeBinding = null) {
  if(!Number.isSafeInteger(version)||version<0||version>64||version===0&&parent!==null
      ||version>0&&(typeof parent!=='string'||! /^[a-f0-9]{64}$/.test(parent)))throw new TypeError('Invalid code lineage');
  files = normalizeFiles(files);
  const body = { schemaVersion:'runtime_os.native_code_tree.v1',hashAlgorithm:'coding-canonical-json-v1', taskId, scopeBinding, version, parent,
    files:Object.entries(files).map(([path,source]) => ({path,sha256:sha(source),bytes:Buffer.byteLength(source)})) };
  const digest=codingValueHash(body);
  body.files=Object.freeze(body.files.map(Object.freeze));
  return Object.freeze({...body,sha256:digest});
}

export async function createNativeCodeWorkspace({ base, taskId, scope, files, restoredManifest,
  strategy = 'editor', authorize, commit, execute, runCommand, lookup, signal, executionMode = 'none' }) {
  if (!/^[a-zA-Z0-9_-]{1,128}$/.test(taskId) || !['editor','patch'].includes(strategy)
      || ![scope?.tenantId,scope?.userId,scope?.runId].every(v=>typeof v==='string'&&v.length>0&&v.length<=128)
      || !['none','python','commands'].includes(executionMode)
      || typeof authorize !== 'function' || typeof commit !== 'function'
      || executionMode==='python'&&typeof execute!=='function'
      || executionMode==='commands'&&typeof runCommand!=='function')
    throw new TypeError('A scoped host and execution bridge are required');
  files = normalizeFiles(files);
  const scopeBinding=sha(JSON.stringify([scope.tenantId,scope.userId,scope.runId]));
  let manifest = codeManifest(taskId,files,restoredManifest?.version ?? 0,restoredManifest?.parent ?? null,scopeBinding);
  if (restoredManifest && (manifest.sha256!==restoredManifest.sha256
      ||codingValueHash(Object.fromEntries(Object.entries(restoredManifest).filter(([key])=>key!=='sha256')))!==manifest.sha256
      ||Object.keys(manifest).sort().join()!==Object.keys(restoredManifest).sort().join()))
    throw new TypeError('RESTORE_CODE_HASH_MISMATCH');
  base = await realpath(base);
  const root = await mkdtemp(join(base,'runtime-code-'+sha(taskId).slice(0,16)+'-'));
  const owned = new Set([root]);
  let disposed = false, uncertain=false,queue = Promise.resolve();
  const commandIndexes=new Map();
  async function check(action) {
    if (disposed) throw new Error('Workspace disposed');
    if (signal?.aborted) throw signal.reason;
    try{await authorize({taskId,action,manifest});}catch(error){uncertain=true;throw error;}
    if (signal?.aborted) throw signal.reason;
  }
  async function materialize(directory, contents) {
    for (const [path,source] of Object.entries(contents)) {
      const target=join(directory,codePath(path));
      await mkdir(dirname(target),{recursive:true,mode:0o700});
      await writeFile(target,source,{mode:0o600});
    }
  }
  async function verifyTree(directory, expected) {
    if ((await lstat(directory)).isSymbolicLink() || await realpath(directory) !== directory)
      throw new TypeError('CODE_WORKSPACE_REDIRECTED');
    const found={};
    async function visit(folder) {
      for (const entry of await readdir(folder,{withFileTypes:true})) {
        const target=join(folder,entry.name), stat=await lstat(target);
        if (stat.isSymbolicLink() || stat.nlink > 1 && !stat.isDirectory()) throw new TypeError('CODE_LINK_DENIED');
        if (stat.isDirectory()) await visit(target);
        else if (stat.isFile()) found[codePath(relative(directory,target).split('\\').join('/'))]=await readFile(target,'utf8');
        else throw new TypeError('CODE_FILE_TYPE_DENIED');
      }
    }
    await visit(directory);
    const normalized=normalizeFiles(found);
    if (expected && JSON.stringify(normalized)!==JSON.stringify(normalizeFiles(expected))) throw new TypeError('CODE_CACHE_CHANGED');
    return normalized;
  }
  async function cleanup(directory) {
    if (!owned.has(directory) || dirname(directory)!==base) throw new Error('Invalid workspace cleanup');
    await rm(directory,{recursive:true,force:true});owned.delete(directory);
  }
  async function publishCache(staging){
    const retired=join(base,'runtime-code-retired-'+randomUUID());owned.add(retired);
    try{
      try{await rename(root,retired);}catch(error){if(error.code!=='ENOENT')throw error;}
      await rename(staging,root);owned.delete(staging);
    }catch(error){uncertain=true;throw error;}
    finally{await cleanup(retired);}
  }
  function serialized(action) {
    const next=queue.then(action);queue=next.catch(()=>{});return next;
  }
  function relativeCodePath(value) {
    if (typeof value !== 'string') throw new TypeError('CODE_PATH_DENIED');
    if(value.startsWith('/workspace/code/'))return codePath(value.slice('/workspace/code/'.length));
    if (isAbsolute(value)) {
      const rel=relative(root,resolve(value));
      if (!rel || rel.startsWith('..') || isAbsolute(rel)) throw new TypeError('CODE_PATH_DENIED');
      return codePath(rel.split('\\').join('/'));
    }
    return codePath(value);
  }
  // Validate ALL native patch headers BEFORE its parser opens any source file.
  // No new patch/edit algorithm is introduced. Canonical SDK patch form only.
  function checkPatch(input) {
    if (typeof input !== 'string' || input.split(/\r?\n/,1)[0]!==PATCH_MARKERS.BEGIN
        || input.trimEnd().split(/\r?\n/).at(-1)!==PATCH_MARKERS.END || Buffer.byteLength(input)>256000)
      throw new TypeError('Canonical SDK patch required');
    let headers=0;
    for (const line of input.split(/\r?\n/)) for (const marker of [PATCH_MARKERS.ADD,PATCH_MARKERS.UPDATE,PATCH_MARKERS.DELETE,PATCH_MARKERS.MOVE])
      if (line.startsWith(marker)) {codePath(line.slice(marker.length));headers++;}
    if (!headers) throw new TypeError('Patch has no file paths');
  }
  async function durable(kind,nextManifest,nextFiles,operationId,nativeDiff) {
    const frozen=Object.freeze({...nextFiles});
    let proof;
    try{proof=await commit({taskId,kind,manifest:nextManifest,files:frozen,operationId,
      ...(nativeDiff?{nativeDiff}: {})});}catch(error){if(kind==='EDIT')uncertain=true;throw error;}
    if (proof?.manifestHash!==nextManifest.sha256) {if(kind==='EDIT')uncertain=true;throw new TypeError('HOST_CODE_COMMIT_NOT_VERIFIED');}
    return frozen;
  }
  try {
    await check('INITIALIZE');await materialize(root,files);
    if (!restoredManifest) await durable('INITIAL',manifest,files,'INITIAL');
  } catch(error) {await cleanup(root);throw error;}
  const native=createDefaultExecutors({fileRead:{maxFileSizeBytes:256000},search:{maxResults:100}});
  const edit=createEditorExecutor({restrictToCwd:true,maxDiffLines:16384});const patch=createApplyPatchExecutor({restrictToCwd:true});
  async function mutate(input,context) {
    await check('EDIT');
    if(context.signal?.aborted)throw context.signal.reason;
    if(!context.toolCallId)throw new TypeError('Native edit requires a tool operation identity');
    const intentHash=sha(JSON.stringify(input));
    const cached=await lookup?.({taskId,operationId:context.toolCallId,intentHash});
    if(cached){
      if(cached.intentHash!==intentHash || typeof cached.result!=='string')throw new TypeError('EDIT_REPLAY_IDENTITY_MISMATCH');
      return cached.result;
    }
    await verifyTree(root,files);
    if (strategy==='patch') checkPatch(input.input);
    else input={...input,path:relativeCodePath(input.path)};
    const staging=await mkdtemp(join(base,'runtime-code-stage-'));owned.add(staging);
    try {
      await materialize(staging,files);
      const patchChanges=strategy==='patch'?await computePatchChanges(input.input,staging,{restrictToCwd:true}):null;
      const result=await (strategy==='editor'?edit(input,staging,context):patch(input,staging,context));
      const next=await verifyTree(staging);
      if (JSON.stringify(next)===JSON.stringify(files)) throw new TypeError('Code did not change');
      const nextManifest=codeManifest(taskId,next,manifest.version+1,manifest.sha256,scopeBinding);
      const nativeResult=result.split(staging).join('/workspace/code');
      if(context.signal?.aborted)throw context.signal.reason;
      await check('COMMIT');await durable('EDIT',nextManifest,next,context.toolCallId,
        {tool:strategy==='editor'?'editor':'apply_patch',intentHash,
          result:nativeResult,...(patchChanges?{changes:patchChanges.changes,fuzz:patchChanges.fuzz}:{})});
      // The durable host version is authoritative even if cache materialization fails.
      files=next;manifest=nextManifest;
      await publishCache(staging);
      return nativeResult.length<=16000?nativeResult:nativeResult.slice(0,8000)+'\n[Full native diff archived]\n'+nativeResult.slice(-8000);
    } finally {if (owned.has(staging)) await cleanup(staging);}
  }
  const executors={
    readFile:(request,context)=>serialized(async()=>{await check('READ');await verifyTree(root,files);
      return native.readFile({...request,path:join(root,relativeCodePath(request.path))},context);}),
    search:(query,_cwd,context)=>serialized(async()=>{await check('SEARCH');await verifyTree(root,files);return (await native.search(query,root,context)).split(root).join('/workspace/code');}),
    [strategy==='editor'?'editor':'applyPatch']:(input,_cwd,context)=>serialized(()=>mutate(input,context)),
  };
  if(executionMode==='commands')executors.bash=(command,_cwd,context)=>serialized(async()=>{
    await check('EXECUTE');await verifyTree(root,files);
    if(!context.toolCallId)throw new TypeError('Native command requires a tool operation identity');
    const ordinal=(commandIndexes.get(context.toolCallId)??0)+1;commandIndexes.set(context.toolCallId,ordinal);
    const operationId=context.toolCallId+':command:'+ordinal;
    const snapshot=await durable('EXECUTION_SNAPSHOT',manifest,files,operationId);
    const receipt=await runCommand({taskId,manifest,files:snapshot,command,operationId,signal:context.signal??signal});
    if(receipt?.manifestHash!==manifest.sha256 || receipt.executorStopped!==true
        || typeof receipt.stopProofRef!=='string' || !receipt.stopProofRef
        || !Number.isInteger(receipt.exitCode) || typeof receipt.stdout!=='string' || typeof receipt.stderr!=='string')
      throw new TypeError('EXECUTION_STOP_OR_VERSION_NOT_VERIFIED');
    if(receipt.resultAvailable===false)throw new TypeError('NATIVE_COMMAND_RESULT_UNAVAILABLE: '+JSON.stringify(receipt));
    const output=JSON.stringify(receipt);
    if(receipt.exitCode!==0)throw new CommandExitError(receipt.exitCode,output);
    return output;
  });
  const tools=createDefaultTools({cwd:'/workspace/code',shell:'bash',executors,enableReadFiles:true,enableSearch:true,
    enableEditor:strategy==='editor',enableApplyPatch:strategy==='patch',enableBash:executionMode==='commands',
    enableWebFetch:false,enableSkills:false,enableAskQuestion:false,enableSubmitAndExit:false});
  if(executionMode==='python')tools.push({name:'python_execute',description:'Execute the current code files in the authorized Python Sandbox. Source versions are managed by Runtime.',
    inputSchema:{type:'object',properties:{},additionalProperties:false},
    execute:(_input,context)=>serialized(async()=>{await check('EXECUTE');await verifyTree(root,files);
      const snapshot=await durable('EXECUTION_SNAPSHOT',manifest,files);await check('EXECUTE');
      const receipt=await execute({taskId,manifest,files:snapshot,signal:context.signal??signal});
      if (receipt?.manifestHash!==manifest.sha256) throw new TypeError('EXECUTION_SNAPSHOT_HASH_MISMATCH');
      return receipt;})});
  return {tools,root,readFileExecutor:executors.readFile,snapshot:()=>({manifest,files:{...files}}),requiresReload:()=>uncertain,
    refresh(snapshot){return serialized(async()=>{
      const nextFiles=normalizeFiles(snapshot.files),next=codeManifest(taskId,nextFiles,snapshot.manifest.version,snapshot.manifest.parent,scopeBinding);
      if(next.sha256!==snapshot.manifest.sha256||next.version<manifest.version
          ||codingValueHash(Object.fromEntries(Object.entries(snapshot.manifest).filter(([key])=>key!=='sha256')))!==next.sha256)
        throw new TypeError('HOST_CODE_VERSION_REGRESSION');
      const staging=await mkdtemp(join(base,'runtime-code-refresh-'));owned.add(staging);
      try{
        await materialize(staging,nextFiles);await publishCache(staging);
        files=nextFiles;manifest=next;uncertain=false;
      }finally{if(owned.has(staging))await cleanup(staging);}
    });},
    async dispose(){await queue;disposed=true;for(const directory of [...owned])await cleanup(directory);}};
}

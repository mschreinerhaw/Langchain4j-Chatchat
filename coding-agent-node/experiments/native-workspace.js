/** Experimental only: no runner import or production flag enables this module.
 * SDK owns reading/search/edit algorithms. Host owns authorization, immutable
 * versions and execution. A materialized directory is a disposable cache.
 */
import { createDefaultTools, createDefaultExecutors, createEditorExecutor,
  createApplyPatchExecutor, PATCH_MARKERS } from '@cline/sdk';
import { mkdtemp, mkdir, writeFile, readFile, readdir, lstat, realpath, rename, rm } from 'node:fs/promises';
import { resolve, join, dirname, relative, isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';

const sha = value => createHash('sha256').update(value).digest('hex');
function codePath(value) {
  if (typeof value !== 'string' || value.length > 160 || value.includes('\\')
      || !/^[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*\.py$/.test(value))
    throw new TypeError('CODE_PATH_DENIED');
  return value;
}
function normalizeFiles(files) {
  if (!files || typeof files !== 'object' || Array.isArray(files)) throw new TypeError('Invalid code files');
  const entries = Object.entries(files).sort(([a],[b]) => a.localeCompare(b));
  if (!entries.length || entries.length > 32) throw new TypeError('Code file count exceeds contract');
  let bytes = 0;
  for (const [path,source] of entries) {
    codePath(path);
    if (typeof source !== 'string') throw new TypeError('Invalid source');
    bytes += Buffer.byteLength(source);
  }
  if (bytes > 256000) throw new TypeError('Code bytes exceed contract');
  return Object.fromEntries(entries);
}
export function codeManifest(taskId, files, version = 0, parent = null) {
  files = normalizeFiles(files);
  const body = { schemaVersion:'runtime_os.native_code_tree.v1', taskId, version, parent,
    files:Object.entries(files).map(([path,source]) => ({path,sha256:sha(source),bytes:Buffer.byteLength(source)})) };
  const digest=sha(JSON.stringify(body));
  body.files=Object.freeze(body.files.map(Object.freeze));
  return Object.freeze({...body,sha256:digest});
}

export async function createNativeCodeWorkspace({ base, taskId, files, restoredManifest,
  strategy = 'editor', authorize, commit, execute, signal }) {
  if (!/^[a-zA-Z0-9_-]{1,128}$/.test(taskId) || !['editor','patch'].includes(strategy)
      || typeof authorize !== 'function' || typeof commit !== 'function' || typeof execute !== 'function')
    throw new TypeError('A scoped host and execution bridge are required');
  files = normalizeFiles(files);
  let manifest = codeManifest(taskId,files,restoredManifest?.version ?? 0,restoredManifest?.parent ?? null);
  if (restoredManifest && JSON.stringify(manifest) !== JSON.stringify(restoredManifest))
    throw new TypeError('RESTORE_CODE_HASH_MISMATCH');
  base = await realpath(base);
  const root = await mkdtemp(join(base,'runtime-code-'+sha(taskId).slice(0,16)+'-'));
  const owned = new Set([root]);
  let disposed = false, queue = Promise.resolve();
  async function check(action) {
    if (disposed) throw new Error('Workspace disposed');
    if (signal?.aborted) throw signal.reason;
    await authorize({taskId,action,manifest});
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
  function serialized(action) {
    const next=queue.then(action);queue=next.catch(()=>{});return next;
  }
  function relativeCodePath(value) {
    if (typeof value !== 'string') throw new TypeError('CODE_PATH_DENIED');
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
    if (typeof input !== 'string' || !input.startsWith(PATCH_MARKERS.BEGIN+'\n')
        || input.trimEnd().split('\n').at(-1)!==PATCH_MARKERS.END || Buffer.byteLength(input)>256000)
      throw new TypeError('Canonical SDK patch required');
    let headers=0;
    for (const line of input.split('\n')) for (const marker of [PATCH_MARKERS.ADD,PATCH_MARKERS.UPDATE,PATCH_MARKERS.DELETE,PATCH_MARKERS.MOVE])
      if (line.startsWith(marker)) {codePath(line.slice(marker.length));headers++;}
    if (!headers) throw new TypeError('Patch has no file paths');
  }
  async function durable(kind,nextManifest,nextFiles) {
    const frozen=Object.freeze({...nextFiles});
    const proof=await commit({taskId,kind,manifest:nextManifest,files:frozen});
    if (proof?.manifestHash!==nextManifest.sha256) throw new TypeError('HOST_CODE_COMMIT_NOT_VERIFIED');
    return frozen;
  }
  try {
    await check('INITIALIZE');await materialize(root,files);
    if (!restoredManifest) await durable('INITIAL',manifest,files);
  } catch(error) {await cleanup(root);throw error;}
  const native=createDefaultExecutors({fileRead:{maxFileSizeBytes:256000},search:{maxResults:100}});
  const edit=createEditorExecutor({restrictToCwd:true});const patch=createApplyPatchExecutor({restrictToCwd:true});
  async function mutate(input,context) {
    await check('EDIT');await verifyTree(root,files);
    if (strategy==='patch') checkPatch(input.input);
    else input={...input,path:relativeCodePath(input.path)};
    const staging=await mkdtemp(join(base,'runtime-code-stage-'));owned.add(staging);
    try {
      await materialize(staging,files);
      const result=await (strategy==='editor'?edit(input,staging,context):patch(input,staging,context));
      const next=await verifyTree(staging);
      if (JSON.stringify(next)===JSON.stringify(files)) throw new TypeError('Code did not change');
      const nextManifest=codeManifest(taskId,next,manifest.version+1,manifest.sha256);
      await check('COMMIT');await durable('EDIT',nextManifest,next);
      // The durable host version is authoritative even if cache materialization fails.
      files=next;manifest=nextManifest;
      const retired=join(base,'runtime-code-retired-'+sha(root).slice(0,16));owned.add(retired);
      await rename(root,retired);await rename(staging,root);owned.delete(staging);await cleanup(retired);
      return result.split(staging).join('workspace');
    } finally {if (owned.has(staging)) await cleanup(staging);}
  }
  const executors={
    readFile:(request,context)=>serialized(async()=>{await check('READ');await verifyTree(root,files);
      return native.readFile({...request,path:join(root,relativeCodePath(request.path))},context);}),
    search:(query,_cwd,context)=>serialized(async()=>{await check('SEARCH');await verifyTree(root,files);return native.search(query,root,context);}),
    [strategy==='editor'?'editor':'applyPatch']:(input,_cwd,context)=>serialized(()=>mutate(input,context)),
  };
  const tools=createDefaultTools({cwd:root,executors,enableReadFiles:true,enableSearch:true,
    enableEditor:strategy==='editor',enableApplyPatch:strategy==='patch',enableBash:false,
    enableWebFetch:false,enableSkills:false,enableAskQuestion:false,enableSubmitAndExit:false});
  tools.push({name:'python_execute',description:'Execute the current code files in the authorized Python Sandbox. Source versions are managed by Runtime.',
    inputSchema:{type:'object',properties:{},additionalProperties:false},
    execute:(_input,context)=>serialized(async()=>{await check('EXECUTE');await verifyTree(root,files);
      const snapshot=await durable('EXECUTION_SNAPSHOT',manifest,files);await check('EXECUTE');
      const receipt=await execute({taskId,manifest,files:snapshot,signal:context.signal??signal});
      if (receipt?.manifestHash!==manifest.sha256) throw new TypeError('EXECUTION_SNAPSHOT_HASH_MISMATCH');
      return receipt;})});
  return {tools,root,snapshot:()=>({manifest,files:{...files}}),
    async dispose(){await queue;disposed=true;for(const directory of [...owned])await cleanup(directory);}};
}

import {createHash} from 'node:crypto';
import {lstat, mkdir, open, readdir, realpath} from 'node:fs/promises';
import {constants} from 'node:fs';
import {basename, dirname, isAbsolute, join, relative, resolve, sep} from 'node:path';

const EVIDENCE = '.runtime-tool-results';
const SUMMARIES = new Set(['analysis_summary.json','data_quality.json','execution_manifest.json']);
const SUMMARY_BYTES = 4096;
const MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
const MAX_ARCHIVE_FILES = 512;
const METADATA_PATH = /^input\/data\/[0-9]+\/data-supply-manifest\.json$/;
const READ_TOOLS = new Set(['read_file','read_files']);
const NATIVE_CODE_TOOLS = new Set(['editor','apply_patch','skills']);
const COMMAND_TOOLS = new Set(['run_commands','execute_command','python_execute','execute_python']);
const isWithin = (root, path) => {
 const rel = relative(root,path);
 return rel === '' || (rel !== '..' && !rel.startsWith('..'+sep) && !isAbsolute(rel));
};

function readPaths(input) {
 if (typeof input === 'string') return [input];
 if (Array.isArray(input)) return input.flatMap(readPaths);
 if (input && typeof input === 'object') {
  for (const key of ['files','file_paths','paths','path','file_path','filePath']) {
   if (Object.hasOwn(input,key)) return readPaths(input[key]);
  }
 }
 return [];
}

function diagnostics(output) {
 const results = Array.isArray(output) ? output : [output];
 return results.slice(0,8).map(result => {
  const text = [result?.error,result?.result].filter(value=>typeof value==='string').join('\n');
  const location = [...text.matchAll(/File "([^"\r\n]{1,256})", line (\d{1,9})/g)].at(-1);
  const category = [...text.matchAll(/^([A-Za-z][A-Za-z0-9_]{0,63}(?:Error|Exception)|KeyboardInterrupt):/gm)].at(-1);
  const exit = text.match(/Command exited with code (-?\d{1,4})/);
  const missingModule = text.match(/^ModuleNotFoundError: No module named ['"]([A-Za-z_][A-Za-z0-9_.]{0,127})['"]/m);
  return {
   ...(typeof result?.success === 'boolean'?{success:result.success}:{}),
   ...(exit?{exitCode:Number(exit[1])}:{}),
   ...(category?{errorType:category[1]}:{}),
   ...(missingModule?{module:missingModule[1]}:{}),
   ...(location?{file:basename(location[1]).slice(0,128),line:Number(location[2])}:{}),
  };
 });
}

export function createAnalysisToolHooks(workspace, metadataPath, prior = {}) {
 if (!METADATA_PATH.test(metadataPath)) throw new Error('Invalid analysis metadata path');
 const root = resolve(workspace), metadata = join(root,metadataPath);
 const evidence = join(root,EVIDENCE);
 const protectedDirectories = ['input','downloads','output',EVIDENCE].map(name=>join(root,name));
 let archiveQueue = Promise.resolve();

 async function permits(path) {
  const requested = resolve(root,path);
  if (!isWithin(root,requested)) return false;
  let actual;
  try { actual = await realpath(requested); }
  catch (error) { return error.code === 'ENOENT' && !protectedDirectories.some(dir=>isWithin(dir,requested)); }
  if (!isWithin(root,actual)) return false;
  if (requested === metadata && actual === metadata) return true;
  if (requested === join(root,'input','manifest.json') && requested === actual) return true;
  if (isWithin(join(root,'output'),actual)) {
   if (requested !== actual || !SUMMARIES.has(basename(actual))) return false;
   const stat = await lstat(actual);
   return stat.isFile() && stat.size <= SUMMARY_BYTES;
  }
  return !protectedDirectories.some(dir=>isWithin(dir,actual)||isWithin(dir,requested));
 }

 function archive(bytes, sha256) {
  const run = archiveQueue.then(async()=>{
   try { await mkdir(evidence,{mode:0o700}); }
   catch(error) { if(error.code!=='EEXIST') throw error; }
   const stat = await lstat(evidence);
   if (!stat.isDirectory() || stat.isSymbolicLink() || await realpath(evidence)!==evidence)
    throw new Error('Invalid tool evidence directory');
   const path = join(evidence,sha256+'.json');
   const entries=await readdir(evidence,{withFileTypes:true});
   if(entries.length>MAX_ARCHIVE_FILES) return null;
   if(!entries.some(entry=>entry.name===basename(path))){
    if(entries.length>=MAX_ARCHIVE_FILES) return null;
    let total=bytes.length;
    for(const entry of entries){
     if(!entry.isFile()||!/^([a-f0-9]{64})\.json$/.test(entry.name))throw new Error('Invalid tool evidence entry');
     total+=(await lstat(join(evidence,entry.name))).size;
     if(total>MAX_ARCHIVE_BYTES)return null;
    }
   }
   try {
    const file = await open(path,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|(constants.O_NOFOLLOW??0),0o600);
    try { await file.writeFile(bytes); } finally { await file.close(); }
   } catch (error) {
    if(error.code!=='EEXIST') throw error;
    const file = await open(path,constants.O_RDONLY|(constants.O_NOFOLLOW??0));
    try {
     const info=await file.stat();
     if(!info.isFile()||info.size!==bytes.length||!bytes.equals(await file.readFile())) throw new Error('Tool evidence differs');
    } finally { await file.close(); }
   }
   return relative(root,path).split(sep).join('/');
  });
  archiveQueue=run.then(()=>{},()=>{});
  return run;
 }

 return {
  ...prior,
  async beforeTool(context) {
   try {
    const previous = await prior.beforeTool?.(context);
    if (previous?.skip || previous?.stop || !READ_TOOLS.has(context.toolCall.toolName)) return previous;
    const paths=readPaths(previous?.input??context.input);
    if (paths.length && (await Promise.all(paths.map(permits))).every(Boolean)) return previous;
    return {...previous,skip:true,reason:'Read approved inventory or compact output summaries separately. Use Python over complete input files or evidence references; report counts, source identifiers, and record or line positions.'};
   } catch {
    return {skip:true,reason:'ANALYSIS_TOOL_POLICY_CHECK_FAILED: inspect the workspace or retry with a verified source path.'};
   }
  },
  async afterTool(context) {
   const name=context.toolCall.toolName;
   const command=COMMAND_TOOLS.has(name), search=name==='search_codebase';
   let previous, result=context.result, bytes, policyError=null;
   try {
    previous=await prior.afterTool?.(context);
    result=previous?.result??context.result;
    if (NATIVE_CODE_TOOLS.has(name)) return previous;
    if (READ_TOOLS.has(name)) {
     const paths=readPaths(context.input);
     if (paths.length && (await Promise.all(paths.map(permits))).every(Boolean)) return previous;
     policyError='ANALYSIS_TOOL_READ_NOT_ADMITTED';
    }
    const visible=Buffer.from(JSON.stringify(result.output??null));
    bytes=result===context.result?visible:Buffer.from(JSON.stringify(context.result.output??null));
    if (!policyError && !command && !search && Math.max(bytes.length,visible.length)<=SUMMARY_BYTES) return previous;
   } catch {
    policyError='ANALYSIS_TOOL_POLICY_FAILED';
    try { bytes=Buffer.from(JSON.stringify(context.result.output??null)); }
    catch { return {result:{isError:true,output:{schemaVersion:'runtime.analysis.tool-receipt.v1',
     outcome:'ERROR',modelView:'REFERENCE_ONLY',omitted:true,code:'ANALYSIS_TOOL_RESULT_NOT_SERIALIZABLE',
     evidence:{status:'UNAVAILABLE'},guidance:'Use the original input and Runtime execution evidence to recover.'}}}; }
   }
   const sha256=createHash('sha256').update(bytes).digest('hex');
   let path, status;
   try { path=await archive(bytes,sha256); status=path?'AVAILABLE':'RESOURCE_LIMIT'; }
   catch { status='WRITE_FAILED'; }
   const code=policyError??(status!=='AVAILABLE'?'ANALYSIS_TOOL_EVIDENCE_'+status:null);
   return {...previous,result:{...result,...(code?{isError:true}:{}),output:{
    schemaVersion:'runtime.analysis.tool-receipt.v1', tool:name,
    outcome:code||result.isError?'ERROR':'RETURNED', modelView:'REFERENCE_ONLY', omitted:true,
    ...(code?{code}:{}),
    evidence:{status,path:path??null,sha256,bytes:bytes.length,scope:'NATIVE_TOOL_RESULT',
     originalSourceCompleteness:'NOT_ASSERTED'},
    ...(command?{diagnostics:diagnostics(context.result.output),processStreams:'See separately recorded Runtime command evidence; native output may already be bounded.'}:{}),
    guidance:'Use Python to inspect evidence and complete inputs. Write selected metrics or diagnostic locations to output/analysis_summary.json (up to 4096 bytes); keep details in artifacts.',
   }}};
  },
 };
}

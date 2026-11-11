// Transport only: signed URLs are capabilities supplied by Runtime, never model
// tools or prompt content. This module contains no code-generation/repair loop.
import {open,mkdir,rename,rm,lstat} from 'node:fs/promises';
import {constants} from 'node:fs';
import {join,resolve,relative,isAbsolute} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {fileDigest,deliveryPath} from './delivery-manifest.mjs';
const CHUNK=8*1024*1024;
function metadata(x){if(!Number.isSafeInteger(x.size)||x.size<0||!/^([a-f0-9]{64})$/.test(x.sha256))throw Error('Invalid Silo transfer metadata');}
function url(value){const u=new URL(value);if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.hash)throw Error('Invalid Silo capability URL');return u;}
async function retry(operation,signal){
 let last;for(let n=0;n<3;n++){signal?.throwIfAborted();try{return await operation();}catch(e){last=e;signal?.throwIfAborted();if(n<2)await new Promise(r=>setTimeout(r,100*2**n));}}
 throw last;
}
async function safeTarget(root,path){
 if(typeof path!=='string'||!path.startsWith('input/'))throw Error('Input must be in input/');
 const parts=path.split('/');if(parts.some(p=>!p||p==='.'||p==='..')||path.includes('\\'))throw Error('Invalid input path');
 let target=resolve(root);await mkdir(target,{recursive:true});
 for(const p of parts.slice(0,-1)){target=join(target,p);await mkdir(target,{recursive:true});if(!(await lstat(target)).isDirectory()||(await lstat(target)).isSymbolicLink())throw Error('Unsafe input directory');}
 const result=join(target,parts.at(-1)),rel=relative(resolve(root),result);if(rel.startsWith('..')||isAbsolute(rel))throw Error('Input outside workspace');return result;
}
export async function downloadInput(workspace,input,{signal,fetcher=fetch}={}){
 metadata(input);if(!input.objectVersion)throw Error('Immutable Silo input version required');
 const capability=url(input.url);
 if(capability.searchParams.get('versionId')!==input.objectVersion)throw Error('Input capability version mismatch');
 const target=await safeTarget(workspace,input.path),temp=target+'.download-'+randomUUID();
 const handle=await open(temp,'wx',0o600);
 try{
  if(input.size===0)await retry(async()=>{
   const response=await fetcher(capability,{redirect:'error',signal});
   if(response.status!==200){await response.body?.cancel();throw Error('Empty Silo input version unavailable');}
   for await(const chunk of response.body??[])if(chunk.length)throw Error('Silo empty input contains bytes');
  },signal);
  for(let offset=0;offset<input.size;offset+=CHUNK){
   const end=Math.min(offset+CHUNK,input.size)-1;
   const bytes=await retry(async()=>{
    const response=await fetcher(capability,{headers:{Range:`bytes=${offset}-${end}`},redirect:'error',signal});
    if(response.status!==206||response.headers.get('content-range')!==`bytes ${offset}-${end}/${input.size}`){await response.body?.cancel();throw Error('Silo range response does not match immutable input');}
    // Cap reads before allocation; never trust Content-Length from transport.
    const chunks=[];let size=0;for await(const chunk of response.body){size+=chunk.length;if(size>end-offset+1)throw Error('Oversized Silo range');chunks.push(chunk);}
    if(size!==end-offset+1)throw Error('Incomplete Silo range');return Buffer.concat(chunks,size);
   },signal);
   await handle.writeFile(bytes);
  }
  await handle.sync();await handle.close();
  const actual=await fileDigest(temp);if(actual.size!==input.size||actual.sha256!==input.sha256)throw Error('Silo input SHA-256 mismatch');
  signal?.throwIfAborted();await rename(temp,target);
  return {path:input.path,...actual,objectVersion:input.objectVersion};
 }catch(e){await handle.close().catch(()=>{});await rm(temp,{force:true});throw e;}
}
export async function uploadParts(workspace,artifact,plan,{signal,fetcher=fetch}={}){
 metadata(artifact);const path=deliveryPath(artifact.path),root=resolve(workspace),target=join(root,path);
 let cursor=root;for(const part of path.split('/')){cursor=join(cursor,part);if((await lstat(cursor)).isSymbolicLink())throw Error('Symlink output denied');}
 if(!Array.isArray(plan.parts)||plan.parts.length!==Math.max(1,Math.ceil(artifact.size/CHUNK)))throw Error('Invalid Silo upload plan');
 const file=await open(target,constants.O_RDONLY|constants.O_NOFOLLOW),hash=createHash('sha256'),parts=[];
 try{
  for(let index=0;index<plan.parts.length;index++){
   const length=Math.min(CHUNK,Math.max(0,artifact.size-index*CHUNK)),data=Buffer.alloc(length);
   let used=0;while(used<length){const {bytesRead}=await file.read(data,used,length-used,index*CHUNK+used);if(!bytesRead)throw Error('Output truncated before upload');used+=bytesRead;}
   hash.update(data);const sha256=createHash('sha256').update(data).digest('hex');
   const version=await retry(async()=>{
    const response=await fetcher(url(plan.parts[index].url),{method:'PUT',body:data,redirect:'error',signal,headers:{'content-type':'application/octet-stream'}});
    await response.body?.cancel();if(!response.ok)throw Error('Silo part upload failed: HTTP '+response.status);
    const version=response.headers.get('x-amz-version-id');if(!version||version==='null')throw Error('Silo versioning required');return version;
   },signal);
   parts.push({index,size:length,sha256,objectVersion:version});
  }
  if((await file.stat()).size!==artifact.size||hash.digest('hex')!==artifact.sha256)throw Error('Output differs from declared SHA-256');
  signal?.throwIfAborted();return {path,parts,size:artifact.size,sha256:artifact.sha256};
 }finally{await file.close();}
}
export const transferChunkBytes=CHUNK;

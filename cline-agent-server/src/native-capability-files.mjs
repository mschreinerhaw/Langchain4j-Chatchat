import {mkdir,writeFile,lstat,open} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {constants} from 'node:fs';
import {createHash} from 'node:crypto';
import {taskSkillPackages,imageSkillBytes,safeResourcePath} from '../../coding-agent-node/src/skill-distribution.js';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function parents(root,path){
 // The workspace is the trusted Executor anchor. Check every new component,
 // including .agents, before following it (not just the package directory).
 if((await lstat(root)).isSymbolicLink())throw Error('Capability anchor symlink forbidden');
 let current=root;
 for(const part of dirname(path).split('/').filter(p=>p!=='.')){
  current=join(current,part);
  try{await mkdir(current);}catch(e){if(e.code!=='EEXIST')throw e;}
  const stat=await lstat(current);if(stat.isSymbolicLink()||!stat.isDirectory())throw Error('Capability directory symlink forbidden');
 }
}
async function downloadDirectory(workspace){
 if(process.platform!=='linux'){await parents(workspace,'downloads/file');return {path:join(workspace,'downloads'),close:async()=>{}};}
 let root;
 try{
  root=await open(workspace,constants.O_RDONLY|constants.O_DIRECTORY|constants.O_NOFOLLOW);
  const path='/proc/self/fd/'+root.fd+'/downloads';
  try{await mkdir(path);}catch(e){if(e.code!=='EEXIST')throw e;}
  // Pin the directory inode across writes; Core can rename its own directories
  // but cannot redirect the root broker's write through an ancestor symlink.
  const directory=await open(path,constants.O_RDONLY|constants.O_DIRECTORY|constants.O_NOFOLLOW);
  return {path:'/proc/self/fd/'+directory.fd,close:()=>directory.close()};
 }catch(e){if(['ELOOP','ENOTDIR'].includes(e.code))throw Error('Capability directory symlink forbidden');throw e;}
 finally{await root?.close();}
}
export async function stageNativeSkills(workspace,packages=[],observe=()=>{}){
 const root=workspace;
 for(const pkg of taskSkillPackages({}).map(p=>({id:p.card.id,name:p.card.name,description:p.card.description,version:p.card.version,source:'image',files:[{path:'SKILL.md',bytes:imageSkillBytes(p)},...p.resources.map(r=>({path:r.path,bytes:imageSkillBytes(p,r.path)}))]})).concat(packages.map(p=>({...p,source:'agent'})))){
  const name=('runtime-'+pkg.source+'-'+hash(Buffer.from(pkg.id)).slice(0,12)+'-'+String(pkg.name).toLowerCase().replace(/[^a-z0-9]+/g,'-')).slice(0,64).replace(/-+$/,'');
  const folder='.agents/skills/'+name;let size=0;const files=[];
  for(const f of pkg.files){safeResourcePath(f.path);let bytes=f.bytes??Buffer.from(f.content,f.encoding==='base64'?'base64':'utf8');size+=bytes.length;if(size>4*1024*1024)throw Error('Skill package byte budget exceeded');if(f.sha256&&hash(bytes)!==f.sha256)throw Error('Skill source hash mismatch');
   const sourceSha256=hash(bytes);
   if(f.path==='SKILL.md'){
    const original=bytes.toString('utf8').replace(/\r\n/g,'\n');
    const front=original.match(/^---\n([\s\S]*?)\n---(?:\n|$)/);
    if(front){
     // Preserve native metadata/flags and all resource links. Only namespace
     // the discovery identity; original bytes remain pinned by source hash.
     let header=front[1];
     header=/^name:.*$/m.test(header)?header.replace(/^name:.*$/m,'name: '+name):'name: '+name+'\n'+header;
     bytes=Buffer.from('---\n'+header+'\n---\n'+original.slice(front[0].length));
    }else bytes=Buffer.from('---\nname: '+name+'\ndescription: '+JSON.stringify(pkg.description||pkg.name)+'\n---\n'+original);
   }
   files.push({path:f.path,sourceSha256,stagedSha256:hash(bytes),size:bytes.length});
   await parents(root,folder+'/'+f.path);await writeFile(join(root,folder,f.path),bytes,{flag:'wx',mode:f.path.startsWith('scripts/')?0o555:0o444});
  }
  observe('skills.discovered',{skillId:pkg.id,name,version:pkg.version,source:pkg.source,bytes:size,files,discovery:'CLINE_NATIVE_PROJECT_SKILLS'});
 }
}
export async function stageNativeDownload(workspace,value,{uid}={}){
 if(value.status!=='OK')return value;
 if(typeof value.content!=='string'||value.content.length>1500000)throw Error('Download byte budget exceeded');
 const bytes=Buffer.from(value.content,'base64');if(bytes.length!==value.size||hash(bytes)!==value.sha256)throw Error('Downloaded file integrity mismatch');
 const type=value.content_type.split(';')[0].toLowerCase(),ext={'application/pdf':'pdf','text/csv':'csv','application/csv':'csv','application/json':'json','application/ld+json':'json','text/html':'html','application/xhtml+xml':'html','text/plain':'txt'}[type];if(!ext)throw Error('Downloaded file type not authorized');
 const path='downloads/'+value.sha256+'.'+ext,directory=await downloadDirectory(workspace),target=join(directory.path,value.sha256+'.'+ext);
 let file;
 try{
  try{file=await open(target,constants.O_CREAT|constants.O_EXCL|constants.O_RDWR|(constants.O_NOFOLLOW??0),0o600);await file.writeFile(bytes);}
  catch(e){if(e.code!=='EEXIST')throw e;file=await open(target,constants.O_RDONLY|(constants.O_NOFOLLOW??0));if(!(await file.stat()).isFile()||hash(await file.readFile())!==value.sha256)throw Error('Download path changed');}
  // Operate on the verified inode, never a subsequently substituted symlink.
  if(Number.isInteger(uid))await file.chown(uid,uid);
 }finally{await file?.close();await directory.close();}
 const {content,encoding,...receipt}=value;return {...receipt,path:'/workspace/'+path,source:'AUTHORIZED_PUBLIC_DOWNLOAD'};
}

// A completed HTTP exchange is not proof of a successful MCP tool receipt.
export function runtimeMcpReply(bytes,status){
 const value=JSON.parse(bytes.toString());
 if(status<200||status>=300||!value||value.jsonrpc!=='2.0'||!Object.hasOwn(value,'result'))
  throw Object.assign(Error('Runtime authority did not return an MCP result'),{status:status>=400?status:502});
 return value;
}

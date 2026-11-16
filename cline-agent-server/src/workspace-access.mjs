import {spawnSync} from 'node:child_process';

// Bind mounts hide image ownership. Probe as Core, before starting any model work.
export function verifyWorkspaceAccess(workspace,{uid,gid}={}) {
 const script=`const fs=require('node:fs'),p=require('node:path');
 const root=process.argv[1];let probe;
 try {fs.accessSync(root,fs.constants.R_OK|fs.constants.W_OK|fs.constants.X_OK);
 probe=fs.mkdtempSync(p.join(root,'.runtime-access-'));
 const file=p.join(probe,'probe');fs.writeFileSync(file,'ok',{flag:'wx',mode:0o600});
 if(fs.readFileSync(file,'utf8')!=='ok')throw Error('Read verification failed');
 fs.renameSync(file,p.join(probe,'renamed'));}
 finally {if(probe)fs.rmSync(probe,{recursive:true});}`;
 const result=spawnSync(process.execPath,['-e',script,workspace],{
  ...(uid!==undefined?{uid,gid}:{}),encoding:'utf8',timeout:10000,maxBuffer:16384,
 });
 if(result.error||result.status!==0)throw Error(`WORKSPACE_ACCESS_DENIED: Core uid=${uid??process.getuid?.()} cannot read/write workspace; check bind mount owner and parent traversal permissions. ${result.error?.code??result.stderr?.slice(0,2000)}`);
}

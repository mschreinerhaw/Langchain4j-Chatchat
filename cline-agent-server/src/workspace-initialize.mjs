import {lstatSync,readdirSync,chownSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';

// Called by the trusted supervisor before Core starts. /workspace/input is a
// separate read-only mount; never chown/chmod it or loosen its permissions.
export function initializeWorkspace(workspace,{uid,gid}={}) {
 if(uid===undefined)return;
 if(process.getuid?.()!==0||!Number.isSafeInteger(uid)||uid<1000||gid!==uid)throw Error('Invalid workspace initialization identity');
 const root=resolve(workspace),entries=[];
 if(root==='/')throw Error('Invalid workspace initialization root');
 function scan(path,relative=''){
  const s=lstatSync(path);
  if(s.isSymbolicLink()||(!s.isDirectory()&&!s.isFile())||(s.isFile()&&s.nlink!==1))throw Error('Unsafe workspace initialization entry');
  if(relative==='input')return;
  entries.push({path,directory:s.isDirectory(),mode:s.isDirectory()?0o700:(relative.startsWith('.agents/')?s.mode&0o777:(s.mode&0o777)|0o600)});
  if(s.isDirectory())for(const name of readdirSync(path))scan(join(path,name),relative?relative+'/'+name:name);
 }
 // Validate the complete tree before changing ownership. No Core runs yet.
 scan(root);
 for(const e of entries)chownSync(e.path,uid,gid);
 // chmod as the owner: the supervisor needs CHOWN, not extra FOWNER powers.
 const script=`const fs=require('node:fs');for(const e of JSON.parse(fs.readFileSync(0,'utf8')))fs.chmodSync(e.path,e.mode);`;
 const result=spawnSync(process.execPath,['-e',script],{uid,gid,input:JSON.stringify(entries),encoding:'utf8',timeout:30000,maxBuffer:16384});
 if(result.error||result.status!==0)throw Error('Workspace initialization failed: '+(result.error?.message??result.stderr));
 return {uid,gid,entries:entries.length};
}

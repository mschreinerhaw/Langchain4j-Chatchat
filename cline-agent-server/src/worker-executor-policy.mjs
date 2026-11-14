import {createHash,createHmac} from 'node:crypto';
import {lstatSync,realpathSync} from 'node:fs';
import {join,resolve,sep,posix} from 'node:path';
import {verifyContract,contractHash} from './task-contract.mjs';
import {workerContainerSpec} from './docker-engine.mjs';

export const reject=(message,status=403)=>Object.assign(Error(message),{status});
export const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function workerRuntime(cfg){
 if(cfg.workerRuntime===undefined||cfg.workerRuntime==='cline-standalone')return {uid:1000,home:'/home/cline',python:false};
 if(cfg.workerRuntime==='cline-python311')return {uid:10001,supervisorUid:0,home:'/home/runner',python:true};
 throw reject('Unknown fixed Worker runtime profile');
}
export function exact(value,keys){if(!value||Array.isArray(value)||typeof value!=='object'||Object.keys(value).some(k=>!keys.includes(k)))throw reject('Unexpected request fields');}
export function taskGrant(token,cfg,allowExpired=false,reconciliationOnly=false){
 const c=verifyContract(token,cfg.signingKey,Date.now(),{allowExpired});const t=Object.hasOwn(cfg.tenants,c.tenantId)?cfg.tenants[c.tenantId]:null;
 // Only the authenticated revoke capability uses this path. A retired user,
 // model or tool must not prevent stopping an already owned process. Exact
 // Executor/container ownership is still verified before any Docker action.
 if(reconciliationOnly)return c;
 if(c.version===2&&(!workerRuntime(cfg).python||cfg.enableNativeSilo!==true))throw reject('Native Silo profile is not enabled on this Executor');
 if(!t||!(t.users.includes(c.userId)||c.version===2&&t.dynamicUsers===true)||!Object.hasOwn(t.workspaces,c.workspaceRef)||!(c.runtimeModel?cfg.dynamicModels===true&&t.dynamicModels===true:t.models.includes(c.modelProfile))||!c.toolPermissions.every(x=>t.tools.includes(x))||!c.dataPermissions.every(x=>t.datasets.includes(x)))throw reject('Executor task policy denied');
 return c;
}
export function identity(c,cfg){const owner=createHash('sha256').update(JSON.stringify([c.tenantId,c.userId,c.taskId,c.sessionId])).digest('hex');return {name:'cline-p331-'+owner.slice(0,40),owner,contractHash:contractHash(c)};}
export function validateCreate(body){
 exact(body,['workerId','resourceProfile','apiToken','brokerToken','editTool']);
 if(!uuid.test(body.workerId)||body.resourceProfile!=='standard'||!['editor','apply_patch'].includes(body.editTool)||![body.apiToken,body.brokerToken].every(x=>typeof x==='string'&&/^[a-f0-9]{64}$/.test(x)))throw reject('Invalid fixed Worker capability request');
}
export function safeTree(path){const stat=lstatSync(path);if(stat.isSymbolicLink())throw reject('Symbolic links forbidden in mount anchors/templates');return stat;}
export function mountAnchor(root,id){if(!uuid.test(id))throw reject('Invalid Worker ID');safeTree(root);const resolved=realpathSync(root);if(resolved!==resolve(root))throw reject('Untrusted anchor root');return join(resolved,id);}
export function assertAnchors(root,id){const anchor=mountAnchor(root,id);safeTree(anchor);for(const name of ['workspace','state','bridge','config.json'])safeTree(join(anchor,name));if(realpathSync(anchor)!==anchor||!anchor.startsWith(realpathSync(root)+sep))throw reject('Mount anchor outside Executor root');}
export function buildSpec(c,body,cfg){
 validateCreate(body);const i=identity(c,cfg);const ownership=createHmac('sha256',cfg.ownershipKey).update(i.owner+'\0'+body.workerId).digest('hex');
 const spec=workerContainerSpec({image:cfg.workerImage,root:posix.join(cfg.hostWorkersRoot,body.workerId),workerId:body.workerId,sessionId:c.sessionId,tenantId:c.tenantId,memoryBytes:cfg.resources.memoryBytes,cpuSet:cfg.resources.cpuSet});
 const runtime=workerRuntime(cfg);
 spec.Env.push('CLINE_PROFESSIONAL_REPORTS='+(process.env.CLINE_PROFESSIONAL_REPORTS==='true'?'true':'false'));
 if(runtime.python){
  // The trusted Host owns evidence/configuration; the official Core and every
  // Shell/Python child are explicitly dropped to runtime.uid before execution.
  spec.User='0:0';
  spec.HostConfig.CapAdd=['SETUID','SETGID','CHOWN','KILL','DAC_OVERRIDE'];
  spec.HostConfig.Init=true; // Reap detached Python children while the task runs.
  spec.Env=spec.Env.filter(value=>!value.startsWith('HOME='));
  spec.Env.push('HOME='+runtime.home,'SHELL=/bin/bash','CLINE_PYTHON_RUNTIME=1','PYTHONDONTWRITEBYTECODE=1','MPLCONFIGDIR=/tmp/matplotlib');
  spec.HostConfig.Binds.push(posix.join(cfg.hostWorkersRoot,body.workerId,'workspace/input')+':/workspace/input:ro');
 }
 spec.HostConfig.NanoCpus=cfg.resources.nanoCpus;spec.HostConfig.PidsLimit=cfg.resources.pidsLimit;
 const normalized={workerId:body.workerId,resourceProfile:body.resourceProfile,apiToken:body.apiToken,brokerToken:body.brokerToken,editTool:body.editTool};
 spec.Labels={...spec.Labels,'cline.executor':cfg.instanceId,'cline.owner':i.owner,'cline.contract':i.contractHash,'cline.ownership':ownership,'cline.request':createHash('sha256').update(JSON.stringify(normalized)).digest('hex')};
 return {spec,...i};
}
export function verifyOwned(info,c,id,cfg){const i=identity(c,cfg),l=info.Config.Labels??{},expected=createHmac('sha256',cfg.ownershipKey).update(i.owner+'\0'+id).digest('hex');if(l['cline.executor']!==cfg.instanceId||l['cline.owner']!==i.owner||l['cline.contract']!==i.contractHash||l['cline.workerId']!==id||l['cline.ownership']!==expected)throw reject('Worker not found in task scope',404);return info;}

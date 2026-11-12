import {createHmac,timingSafeEqual,createHash} from 'node:crypto';
const identifier=/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,95}$/;
export const contractHash=c=>createHash('sha256').update(JSON.stringify(c)).digest('hex');
export function signContract(contract,key){const body=Buffer.from(JSON.stringify(contract)).toString('base64url');return body+'.'+createHmac('sha256',key).update(body).digest('base64url');}
export const runtimeModelProfile=m=>'runtime-'+createHash('sha256').update(m.logicalName+'\0'+m.providerModel).digest('hex');
export function verifyContract(token,key,now=Date.now(),{allowExpired=false}={}){
 const fail=()=>{throw Object.assign(Error('Invalid or expired task identity'),{status:401});};
 if(typeof token!=='string'||token.length>16384)return fail();
 const parts=token.split('.');if(parts.length!==2)return fail();
 const expected=createHmac('sha256',key).update(parts[0]).digest(),actual=Buffer.from(parts[1],'base64url');
 if(actual.length!==expected.length||!timingSafeEqual(actual,expected))return fail();
 let c;try{c=JSON.parse(Buffer.from(parts[0],'base64url').toString());}catch{return fail();}
 if(![1,2].includes(c.version)||!['tenantId','userId','taskId','sessionId','workspaceRef','modelProfile'].every(k=>typeof c[k]==='string'&&identifier.test(c[k])))return fail();
 if(c.version===2){
  if(c.runtimeModel!==undefined){const m=c.runtimeModel;if(!m||Array.isArray(m)||Object.keys(m).sort().join(',')!=='logicalName,providerModel'||!['logicalName','providerModel'].every(k=>typeof m[k]==='string'&&m[k].length>0&&m[k].length<=256&&!/[\x00-\x1f]/.test(m[k]))||c.modelProfile!==runtimeModelProfile(m))return fail();}
  if(c.runtimeTools!==undefined&&(!Array.isArray(c.runtimeTools)||!c.runtimeTools.every(t=>['web_search','web_fetch','web_download','document_search'].includes(t))))return fail();
  const b=c.deliveryBinding;
  if(c.runtimeProfile!=='native-python-silo-v1'||!b||Array.isArray(b)||typeof b!=='object'
     ||Object.keys(b).sort().join(',')!=='inputSnapshotVersion,runId,taskId,tenantId'
     ||b.taskId!==c.taskId||b.tenantId!==c.tenantId||typeof b.runId!=='string'||!identifier.test(b.runId)
     ||typeof b.inputSnapshotVersion!=='string'||!b.inputSnapshotVersion||b.inputSnapshotVersion.length>512
     ||/[\x00-\x1f]/.test(b.inputSnapshotVersion))return fail();
 }else if(c.runtimeProfile!==undefined||c.deliveryBinding!==undefined||c.runtimeModel!==undefined)return fail();
 if(!Number.isSafeInteger(c.expiresAt)||!allowExpired&&c.expiresAt<=now||c.expiresAt>now+86400000)return fail();
 if(!Array.isArray(c.toolPermissions)||!c.toolPermissions.every(x=>typeof x==='string'&&x.length<128)||!Array.isArray(c.dataPermissions)||!c.dataPermissions.every(x=>typeof x==='string'&&identifier.test(x)))return fail();
 if(c.networkPolicy!=='broker-only'||!c.budgetPolicy||c.budgetPolicy.status!=='DEFERRED_STAGE_2_REVIEW')return fail();
 return c;
}
export function sameOwner(a,b){return a.tenantId===b.tenantId&&a.userId===b.userId&&a.taskId===b.taskId&&a.sessionId===b.sessionId;}
export function holdsCapacity(record){
 if(record.workerState==='STOPPED')return false;
 if(record.workerState==='RECOVERABLE')return !record.stopProof;
 if(record.workerState==='START_FAILED')return !!record.provisioningAttempted&&!record.stopProof;
 return true;
}
export function authorizeSession(contract,record,allowRevoked=false){
 if(!record||!sameOwner(contract,record.contract))throw Object.assign(Error('Session not found'),{status:404});
 if(record.contractHash!==contractHash(contract))throw Object.assign(Error('Task contract changed'),{status:409});
 if(record.revoked&&!allowRevoked)throw Object.assign(Error('Task revoked'),{status:403});
 return record;
}

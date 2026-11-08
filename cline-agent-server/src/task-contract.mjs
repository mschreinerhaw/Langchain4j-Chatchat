import {createHmac,timingSafeEqual,createHash} from 'node:crypto';
const identifier=/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,95}$/;
export const contractHash=c=>createHash('sha256').update(JSON.stringify(c)).digest('hex');
export function signContract(contract,key){const body=Buffer.from(JSON.stringify(contract)).toString('base64url');return body+'.'+createHmac('sha256',key).update(body).digest('base64url');}
export function verifyContract(token,key,now=Date.now()){
 const fail=()=>{throw Object.assign(Error('Invalid or expired task identity'),{status:401});};
 if(typeof token!=='string'||token.length>16384)return fail();
 const parts=token.split('.');if(parts.length!==2)return fail();
 const expected=createHmac('sha256',key).update(parts[0]).digest(),actual=Buffer.from(parts[1],'base64url');
 if(actual.length!==expected.length||!timingSafeEqual(actual,expected))return fail();
 let c;try{c=JSON.parse(Buffer.from(parts[0],'base64url').toString());}catch{return fail();}
 if(c.version!==1||!['tenantId','userId','taskId','sessionId','workspaceRef','modelProfile'].every(k=>typeof c[k]==='string'&&identifier.test(c[k])))return fail();
 if(!Number.isSafeInteger(c.expiresAt)||c.expiresAt<=now||c.expiresAt>now+86400000)return fail();
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

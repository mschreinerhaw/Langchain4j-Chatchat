import {verifyContract} from './task-contract.mjs';
export function issuerKey(config,tenant){
 if(!config.issuerKeys)return config.signingKey;
 const value=Object.hasOwn(config.issuerKeys,tenant)?config.issuerKeys[tenant]:null;
 if(typeof value!=='string'||value.length<32)throw Object.assign(Error('Tenant issuer is not configured'),{status:401});
 return value;
}
export function verifyIssuedContract(token,config,options={}){
 if(typeof token!=='string'||token.length>16384)throw Object.assign(Error('Invalid task grant'),{status:401});
 let tenant;try{tenant=JSON.parse(Buffer.from(token.split('.')[0],'base64url').toString('utf8')).tenantId;}catch{throw Object.assign(Error('Invalid task grant'),{status:401});}
 // Unverified content selects a key only. All identity/permissions/paths are
 // accepted exclusively after verification using that tenant's own key.
 return verifyContract(token,issuerKey(config,tenant),Date.now(),options);
}

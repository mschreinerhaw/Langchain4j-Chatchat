import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {authorizeSession,contractHash,signContract} from '../src/task-contract.mjs';
import {verifyIssuedContract} from '../src/task-issuer.mjs';
import {storageGrant} from '../src/s3-task-transport.mjs';
const grant={version:1,tenantId:'alpha',userId:'alice',sessionId:'session',taskId:'task',workspaceRef:'project',modelProfile:'model',toolPermissions:['read_files'],dataPermissions:[],networkPolicy:'broker-only',budgetPolicy:{status:'DEFERRED_STAGE_2_REVIEW'},expiresAt:Date.now()+3600000};
test('analysis admission survives persisted binding and rejects session or profile substitution',()=>{
 const key='a'.repeat(64),config={signingKey:'r'.repeat(64),issuerKeys:{alpha:key}};
 const schema={...grant,version:2,runtimeProfile:'native-python-silo-v1',
  deliveryBinding:{tenantId:'alpha',taskId:'task',runId:'run',inputSnapshotVersion:'v1'},s3:{}};
 const analysisMetadataPath='input/data/1/data-supply-manifest.json';
 const admitted=verifyIssuedContract(signContract({...schema,analysisMetadataPath},key),config);
 const binding=JSON.parse(JSON.stringify({contract:admitted,contractHash:contractHash(admitted)}));
 assert.equal(authorizeSession(admitted,binding).contract.analysisMetadataPath,analysisMetadataPath);
 assert.throws(()=>authorizeSession({...admitted,sessionId:'another'},binding),{status:404});
 assert.throws(()=>authorizeSession(schema,binding),{status:409});
 assert.throws(()=>authorizeSession({...admitted,analysisMetadataPath:'input/data/2/data-supply-manifest.json'},binding),{status:409});
 assert.throws(()=>authorizeSession(admitted,{...binding,revoked:true}),{status:403});
 assert.equal(verifyIssuedContract(signContract(schema,key),config).analysisMetadataPath,undefined);
 assert.throws(()=>verifyIssuedContract(signContract({...grant,analysisMetadataPath},key),config),{status:401});
});
test('Native task document search is explicit and does not grant arbitrary MCP tools',()=>{
 const config={signingKey:'r'.repeat(64),issuerKeys:{alpha:'a'.repeat(64)}};
 const c={...grant,version:2,runtimeProfile:'native-python-silo-v1',deliveryBinding:{tenantId:'alpha',taskId:'task',runId:'run',inputSnapshotVersion:'v1'},runtimeTools:['document_search']};
 assert.deepEqual(verifyIssuedContract(signContract(c,config.issuerKeys.alpha),config).runtimeTools,['document_search']);
 assert.throws(()=>verifyIssuedContract(signContract({...c,runtimeTools:['document_admin']},config.issuerKeys.alpha),config));
});
test('a tenant issuer cannot sign another tenant or fall back to the Executor root key',()=>{
 const config={signingKey:'r'.repeat(64),issuerKeys:{alpha:'a'.repeat(64),beta:'b'.repeat(64)}};
 assert.equal(verifyIssuedContract(signContract(grant,config.issuerKeys.alpha),config).tenantId,'alpha');
 for(const key of [config.issuerKeys.beta,config.signingKey])assert.throws(()=>verifyIssuedContract(signContract(grant,key),config));
 assert.throws(()=>verifyIssuedContract(signContract({...grant,tenantId:'gamma'},config.signingKey),config));
});
test('formal S3 task scopes bind tenant, owner, task, run and the same transport epoch',()=>{
 const hash=s=>createHash('sha256').update(s).digest('hex'),epoch='12345678-1234-4234-8234-123456789abc';
 const root='/runtime-assets/tenants/'+hash('alpha')+'/users/'+hash('alpha\0alice')+'/tasks/coding-node/'+hash('task\0run')+'/'+epoch;
 const config={scopeLayout:'tenant-task-v1',bucket:'runtime-assets',allowedOrigins:['http://silo:9000']};
 const c={...grant,version:2,deliveryBinding:{runId:'run'},s3:{origin:'http://silo:9000',inputPrefix:root+'/input/',outputPrefix:root+'/output/',inputManifestSha256:'a'.repeat(64)}};
 assert.equal(storageGrant(c,config),c.s3);
 for(const changed of [{tenantId:'beta'},{userId:'bob'},{taskId:'other'},{deliveryBinding:{runId:'other'}},{s3:{...c.s3,outputPrefix:root.replace(epoch,'00000000-0000-4000-8000-000000000000')+'/output/'}}])assert.throws(()=>storageGrant({...c,...changed},config),{status:403});
});

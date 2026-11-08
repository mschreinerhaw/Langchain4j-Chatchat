import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const root=new URL('../../docs/testing/evidence/cline-server-p331-20261108/',import.meta.url);
const json=p=>JSON.parse(readFileSync(new URL(p,root),'utf8'));
test('P331 evidence bytes remain equal to their recorded hashes',()=>{
 for(const f of json('SHA256SUMS.json')){assert(!f.path.includes('..')&&!f.path.startsWith('/')&&!f.path.includes('\\'));const b=readFileSync(new URL(f.path,root));assert.equal(b.length,f.bytes,f.path);assert.equal(createHash('sha256').update(b).digest('hex'),f.sha256,f.path);}
});
test('P331 review never turns the real resource failure into a production pass',()=>{
 const matrix=json('runs/live-results.json');assert.equal(matrix.length,20);assert(matrix.filter(x=>x.blocking).every(x=>x.pass));assert.equal(matrix.find(x=>x.case==='per-worker-cgroup-hard-limits').pass,false);
 const gateway=json('docker/cline-p331-gateway.json');assert.equal(gateway.Config.User,'1000:1000');assert(!gateway.Mounts.some(x=>x.Source.includes('docker.sock')));assert(json('runs/startup-guards.json').every(x=>x.pass));assert.equal(json('collection.json').productionReady,false);assert.equal(json('collection.json').pythonRuntimeMigrated,false);assert.equal(json('runs/native-summary.json').pass,true);assert(json('worker-states.json').every(x=>!x.state.Running));assert.deepEqual(json('runs/shared-before.json'),json('runs/shared-after.json'));assert.equal(json('source/official-runtime-verification.json').status,'PASS');
});

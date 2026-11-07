/** Retain original failed and passing component logs without rewriting their bytes. */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {gzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
const root='docs/testing/evidence/native-p1/mcp/components',sha=b=>createHash('sha256').update(b).digest('hex');
await mkdir(root,{recursive:true});
const logs=['p1-mcp-compile.log','p1-mcp-compile-r2.log','p1-mcp-compile-r3.log',
 'p1-mcp-node-tests.log','p1-mcp-node-tests-r2.log','p1-mcp-node-tests-r3.log',
 'p1-mcp-node-full.tap','p1-mcp-node-full-r2.tap','p1-mcp-node-full-r3.tap','p1-mcp-node-full-r4.tap',
 'p1-mcp-java-tests.log',...Array.from({length:6},(_,i)=>`p1-mcp-java-tests-r${i+2}.log`)];
const manifest=[];
for(const name of logs){const bytes=await readFile('target/'+name),gzip=gzipSync(bytes);await writeFile(root+'/'+name+'.gz',gzip);
 manifest.push({path:name+'.gz',sha256:sha(gzip),originalSha256:sha(bytes),originalBytes:bytes.length});}
const decode=b=>b[0]===0xff&&b[1]===0xfe?b.subarray(2).toString('utf16le'):b.toString('utf8');
const java=decode(await readFile('target/p1-mcp-java-tests-r7.log')),node=decode(await readFile('target/p1-mcp-node-full-r4.tap'));
if(!java.includes('BUILD SUCCESS'))throw Error('The final Java run did not pass');
const metric=name=>Number(node.match(new RegExp('^# '+name+' (\\d+)$','m'))?.[1]??NaN);
if(metric('fail')!==0||metric('pass')<322)throw Error('The final Node run did not pass');
const suites=[];
for(const [module,names]of Object.entries({'chatchat-common':['com.chatchat.common.runtime.coding.NativeCodeWorkspaceTest'],
 'chatchat-api':['com.chatchat.api.runtime.coding.nativecode.NativeCodingMcpServiceTest','com.chatchat.api.runtime.coding.nativecode.NativeCodingWorkspaceServiceTest',
 'com.chatchat.api.runtime.coding.cline.ClineCodingAdapterDispatchTest','com.chatchat.api.runtime.coding.cline.ClineCodingAdapterRecoveryTest',
 'com.chatchat.api.runtime.coding.cline.ClineRuntimeModelBridgeTest','com.chatchat.api.runtime.coding.cline.ClineModelCallbackControllerTest']}))
 for(const name of names){
  const xml=(await readFile(module+'/target/surefire-reports/TEST-'+name+'.xml','utf8')).match(/<testsuite\s+[^>]*>/)?.[0];
  const a=Object.fromEntries([...xml.matchAll(/([\w:-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]]));
  const suite={name,tests:Number(a.tests),failures:Number(a.failures),errors:Number(a.errors),skipped:Number(a.skipped)};
  if(suite.failures||suite.errors)throw Error('Failing final suite '+name);suites.push(suite);
 }
await writeFile(root+'/manifest.json',JSON.stringify({status:'COMPONENTS_ONLY_NOT_PRODUCT_ACCEPTANCE',sdkVersion:'0.0.93',nodeVersion:'22.18.0',
 node:{tests:metric('tests'),passed:metric('pass'),failed:metric('fail'),skipped:metric('skipped')},
 java:{tests:suites.reduce((n,s)=>n+s.tests,0),suites},logs:manifest},null,2)+'\n');
console.log(JSON.stringify({nodePassed:metric('pass'),javaTests:suites.reduce((n,s)=>n+s.tests,0),rawLogsPreserved:manifest.length,productAcceptance:false}));

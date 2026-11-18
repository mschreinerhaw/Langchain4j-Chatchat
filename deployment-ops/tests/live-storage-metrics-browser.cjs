// Read-only real API verification of selected storage, Docker metrics and native executors.
const fs=require('fs'),assert=require('assert/strict'),{chromium}=require(process.env.PLAYWRIGHT_MODULE);
(async()=>{
 const input=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL,out=process.env.OPS_TEST_ARTIFACTS;fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']}),page=await browser.newPage();
 async function get(path){const r=await page.request.get(base+'/api'+path);assert.equal(r.status(),200,path);return r.json();}
 try{
  const login=await page.request.post(base+'/api/login',{data:{username:'admin',password:input.password},headers:{'X-Ops-Request':'1'}});assert.equal(login.status(),200);
  const platform=await get('/platform/health');assert.equal(platform.database,'UP');assert.equal(platform.executorImplementation,'JDK');assert.equal(platform.dataDirectory,input.dataDirectory);assert.equal(platform.runtimeDirectory,input.runtimeDirectory);assert.equal(platform.storageMode,'directory');assert.equal(platform.mountPoint,'/');
  const inventory=await get('/inventory'),containers=inventory.flatMap(n=>n.data?.containers||[]),own=containers.filter(c=>c.Labels?.['com.docker.compose.project']==='ops-env-'+input.environment);assert.equal(own.length,24);assert(own.every(c=>c.State==='running'));
  const service=own.find(c=>c.Labels?.['com.docker.compose.service']==='api'),inspect=await get('/nodes/1/containers/'+service.Id+'/inspect');assert(inspect.HostConfig.Memory>0);
  const samples=[];for(let n=0;n<2;n++){samples.push(await get('/nodes/1/containers/'+service.Id+'/stats'));await page.waitForTimeout(1100);}
  const metric=samples[1];assert.equal(metric.scope,'container');assert(metric.memoryBytes>0);assert.equal(metric.memoryLimitBytes,inspect.HostConfig.Memory);assert(Number.isFinite(metric.cpuPercent)&&metric.cpuPercent>=0);for(const name of ['networkRxBytes','networkTxBytes','blockReadBytes','blockWriteBytes'])assert(Number.isFinite(metric[name])&&metric[name]>=0,name);
  const task=await get('/nodes/1/installations/'+input.task);assert.equal(task.active,true);assert.equal(task.supportsPartialStop,true);const native=await get('/nodes/1/installations/'+input.task+'/health');assert.equal(native.installationVerification.status,'PASSED');assert(native.installationVerification.nativeCoreVerified&&native.installationVerification.pythonRuntimeVerified);assert.equal(native.executors.length,3);assert(native.executors.every(p=>p.status==='HEALTHY'&&p.resourceEnforced));
  const result={passed:true,checks:['selected system-disk storage and embedded H2 remain active','24 real native components running','real Docker CPU, memory, network and block counters; memory limit matches inspect','three real Unix executor probes enforce resources; Core and Python installation verified'],platform,container:service.Id,samples,native};fs.writeFileSync(out+'/result.json',JSON.stringify(result,null,2));console.log(JSON.stringify({passed:true,checks:result.checks}));
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});

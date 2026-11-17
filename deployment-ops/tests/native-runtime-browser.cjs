// Runs beside the offline media against the installed native platform. No mocks.
const fs=require('fs'),assert=require('assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'/app/node_modules/playwright');
(async()=>{
 const base=process.env.OPS_QA_URL||'http://127.0.0.1:19240',out=process.env.OPS_TEST_ARTIFACTS||'/evidence';
 const env=process.env.OPS_RUNTIME_ENV||'runtime-native-v1',media=process.env.OPS_RUNTIME_MEDIA||'/media/runtime.ops.zip';
 fs.mkdirSync(out,{recursive:true});const checks=[],errors=[];
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||undefined,headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1600,height:1000},locale:'zh-CN',timezoneId:'Asia/Shanghai'});
 page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(30000);
 async function capture(name){await page.screenshot({path:out+'/'+name+'.png',fullPage:true});}
 async function api(route){const r=await page.request.get(base+route);assert.equal(r.status(),200,route);return r.json();}
 let job;
 try{
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(process.env.OPS_ADMIN_PASSWORD);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  await page.waitForTimeout(2500);const inventory=await api('/api/inventory');
  const before=inventory.flatMap(n=>n.data?.containers||[]).filter(c=>c.Labels?.['deployment-ops.environment']!==env&&c.Labels?.['com.docker.compose.project']!=='ops-env-'+env).map(c=>({id:c.Id,state:c.State}));fs.writeFileSync(out+'/containers-before.json',JSON.stringify(before,null,2));checks.push('native platform authenticated');
  const existing=await api('/api/nodes/1/packages');
  if(!existing.some(p=>p.digest===process.env.OPS_MEDIA_SHA256)){
  await page.goto(base+'/#/images');await page.getByRole('button',{name:'离线导入',exact:true}).click();const upload=page.locator('form').filter({hasText:'上传并验证应用包'});
  await upload.locator('input[type=file]').setInputFiles(media);await upload.getByLabel('发行方 SHA-256',{exact:true}).fill(process.env.OPS_MEDIA_SHA256);
  await upload.getByRole('button',{name:'上传并验证应用包',exact:true}).click();console.log('Uploading signed offline package through Vue');
  await page.getByText('签名与离线镜像验证通过 100%',{exact:true}).waitFor({timeout:1800000});await capture('offline-import-verified');checks.push('Vue resumable offline package upload and real signature/image verification');
  }else checks.push('reviewed signed offline package already verified on target with matching SHA256');
  if(!process.env.OPS_RUNTIME_JOB){
  await page.goto(base+'/#/deploy');await page.getByLabel('应用环境名称').fill(env);await page.locator('label').filter({hasText:'目标服务器'}).locator('select').selectOption('1');await page.getByRole('button',{name:'检查主机并继续',exact:true}).click();
  const catalog=await api('/api/nodes/1/packages'),release=catalog.find(p=>p.name==='runtime-os'&&p.digest===process.env.OPS_MEDIA_SHA256);assert(release,'Reviewed native Runtime OS release missing');
  await page.locator('label').filter({hasText:'选择可信离线发行包'}).locator('select').selectOption(release.digest);await page.getByRole('button',{name:'配置应用参数',exact:true}).click();
  await page.locator('#parameter-serverAddress').fill('192.168.195.221');await page.locator('#parameter-httpPort').fill('19400');await page.locator('#parameter-mcpPort').fill('19401');await page.locator('#parameter-osAdminPassword').fill(process.env.OPS_RUNTIME_PASSWORD);
  const preflight=page.waitForResponse(r=>r.url().endsWith('/installations/preflight'),{timeout:180000});await page.getByRole('button',{name:'运行部署预检',exact:true}).click();const reviewed=await preflight;assert.equal(reviewed.status(),200);const review=await reviewed.json();assert.equal(review.ok,true,JSON.stringify(review));await page.getByLabel('输入环境名称确认执行',{exact:true}).fill(env);await capture('deployment-preflight');
  const response=page.waitForResponse(r=>r.url().endsWith('/installations')&&r.request().method()==='POST');await page.getByRole('button',{name:'确认部署 / 升级',exact:true}).click();const started=await response;assert.equal(started.status(),200);job=await started.json();checks.push('deployment submitted through real Vue wizard');console.log('Runtime deployment task '+job.id);
  }else{job=await api('/api/nodes/1/installations/'+process.env.OPS_RUNTIME_JOB);await page.goto(base+'/#/tasks/deployment/1/'+job.id);checks.push('observed retained task after explicit recovery');}
  for(let attempt=0;attempt<600;attempt++){
   try{job=await api('/api/nodes/1/installations/'+job.id);}catch(error){if(!process.env.OPS_RESTART_ACCEPTANCE||attempt>590)throw error;await page.waitForTimeout(3000);continue;}
   fs.writeFileSync(out+'/deployment-task.json',JSON.stringify(job,null,2));
   if(attempt%12===0)console.log('Runtime task: '+job.status+' '+job.message);
   if(job.status==='succeeded')break;
   if(job.status==='interrupted'&&process.env.OPS_RESTART_ACCEPTANCE){
    const installer=job.runtimeContainer;await page.goto(base+'/#/tasks/deployment/1/'+job.id);await page.getByRole('button',{name:'恢复执行',exact:true}).click();await page.getByLabel('输入环境名称确认',{exact:true}).fill(env);const resumed=page.waitForResponse(r=>r.url().endsWith('/'+job.id+'/resume'),{timeout:180000});await page.getByRole('button',{name:'确认恢复执行',exact:true}).click();assert.equal((await resumed).status(),200);const current=await api('/api/nodes/1/installations/'+job.id);assert.equal(current.runtimeContainer,installer);checks.push('explicit GUI recovery retained the installer after native platform restart');await capture('inflight-recovery');continue;
   }
   if(!['queued','running','starting','rolling_back'].includes(job.status))throw Error('Deployment failed: '+job.status+' '+job.message);
   await page.waitForTimeout(3000);
  }
  assert.equal(job.status,'succeeded');checks.push('source-built native Temporal Runtime OS successfully installed and verified');await capture('deployment-success');
  await page.goto(base+'/#/applications');await page.waitForTimeout(2500);await capture('managed-runtime-application');const after=await api('/api/inventory');const containers=after.flatMap(n=>n.data?.containers||[]);
  for(const prior of before){const current=containers.find(c=>c.Id===prior.id);assert(current,'Existing container removed: '+prior.id);assert.equal(current.State,prior.state,'Existing container state changed');}
  assert.deepEqual(errors,[]);fs.writeFileSync(out+'/result.json',JSON.stringify({passed:true,checks,job:job.id,errors},null,2));console.log(JSON.stringify({passed:true,checks,job:job.id}));
 }catch(error){await capture('failure');fs.writeFileSync(out+'/result.json',JSON.stringify({passed:false,checks,job:job?.id,error:error.stack,errors},null,2));throw error;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

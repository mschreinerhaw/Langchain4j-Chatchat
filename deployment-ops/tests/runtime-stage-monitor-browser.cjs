// Observe the actual adopted environment; no test endpoints or fabricated metrics.
const fs=require('fs'),assert=require('assert/strict'),{chromium}=require(process.env.PLAYWRIGHT_MODULE);
(async()=>{
 const base=process.env.OPS_QA_URL,out=process.env.OPS_TEST_ARTIFACTS,environment=process.env.OPS_RUNTIME_ENV;
 fs.mkdirSync(out,{recursive:true});const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1600,height:1000},locale:'zh-CN'}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 async function get(route){const r=await page.request.get(base+route);assert.equal(r.status(),200,route);return r.json();}
 try{
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(process.env.OPS_ADMIN_PASSWORD);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  await page.goto(base+'/#/applications/1/'+encodeURIComponent(environment));await page.getByRole('heading',{name:'Coding / Python 执行',exact:false}).waitFor();await page.screenshot({path:out+'/application.png',fullPage:true});
  const response=page.waitForResponse(r=>r.url().endsWith('/health')&&r.url().includes('/installations/'));await page.getByRole('link',{name:'资源监控',exact:true}).click();const health=await(await response).json();assert.equal(health.executors.length,3);assert(health.executors.every(e=>e.status==='HEALTHY'&&e.resourceEnforced));
  await page.getByRole('heading',{name:'Coding Executor 1',exact:false}).waitFor();await page.screenshot({path:out+'/executors.png',fullPage:true});
  const task=await get('/api/nodes/1/installations/'+process.env.OPS_RUNTIME_JOB);assert.equal(task.status,'succeeded');assert.equal(task.version,'1.3');assert(task.active);
  fs.writeFileSync(out+'/health.json',JSON.stringify(health,null,2));assert.deepEqual(errors,[]);
  const result={passed:true,task:task.id,version:task.version,checks:['real modular application detail','three real Unix-socket executor health responses','resourceEnforced=true for every executor','active persisted managed release 1.3'],errors};fs.writeFileSync(out+'/result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }catch(e){await page.screenshot({path:out+'/failure.png',fullPage:true});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

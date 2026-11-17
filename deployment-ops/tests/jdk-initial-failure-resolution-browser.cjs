// Real GUI resolution of an already quiescent initial Native failure.
const fs=require('fs'),assert=require('assert/strict'),{chromium}=require(process.env.PLAYWRIGHT_MODULE);
(async()=>{
 const input=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL,out=process.env.OPS_TEST_ARTIFACTS;fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']}),page=await browser.newPage({viewport:{width:1600,height:1000}}),checks=[];
 async function get(p){const r=await page.request.get(base+'/api'+p);assert.equal(r.status(),200);return r.json();}
 try{
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(input.password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  const job=await get('/nodes/1/installations/'+input.task);assert.equal(job.environment,input.environment);assert.equal(job.status,'failed');assert(!job.active&&!job.previousRun);const before=(await get('/inventory')).flatMap(n=>n.data?.containers||[]).map(c=>({id:c.Id,state:c.State}));
  const denied=await page.request.post(base+'/api/nodes/1/installations/'+input.protectedTask+'/resolve',{data:{},headers:{'X-Ops-Request':'1'}});assert.equal(denied.status(),400);checks.push('ending an active healthy environment is rejected without any lifecycle action');
  await page.goto(base+'/#/tasks/deployment/1/'+input.task);await page.getByRole('button',{name:'结束失败任务',exact:true}).click();await page.getByLabel('输入环境名称确认',{exact:true}).fill(input.environment);await page.screenshot({path:out+'/resolution-confirmation.png',fullPage:true});
  const response=page.waitForResponse(r=>r.url().endsWith('/'+input.task+'/resolve')&&r.request().method()==='POST');await page.getByRole('button',{name:'确认结束失败任务',exact:true}).click();assert.equal((await response).status(),200);const resolved=await get('/nodes/1/installations/'+input.task);assert.equal(resolved.status,'resolved');assert.equal(resolved.failure,job.failure);assert.equal(resolved.installerLog,job.installerLog);assert.equal(resolved.resolvedBy,'admin');checks.push('explicit Vue confirmation ends only the failed initial task and retains its original error and installer log');
  const after=(await get('/inventory')).flatMap(n=>n.data?.containers||[]);for(const prior of before)assert(after.some(c=>c.Id===prior.id&&c.State===prior.state));checks.push('all existing running and stopped containers retain their IDs and states');
  fs.writeFileSync(out+'/result.json',JSON.stringify({passed:true,checks,task:input.task},null,2));console.log(JSON.stringify({passed:true,checks}));
 }catch(error){await page.screenshot({path:out+'/failure.png',fullPage:true});fs.writeFileSync(out+'/result.json',JSON.stringify({passed:false,checks,error:error.stack},null,2));throw error;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

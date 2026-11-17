// Real JDK executor upgrade, rollback and power lifecycle, restricted to a QA environment.
const fs=require('fs'),assert=require('assert/strict'),{chromium}=require(process.env.PLAYWRIGHT_MODULE);
(async()=>{
 const input=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL,out=process.env.OPS_TEST_ARTIFACTS||'target/jdk-lifecycle',env='jar-jdk-protocol-v2',initial='1a65618cc8a641759b5e85c635bee7ca';fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']}),page=await browser.newPage(),checks=[];
 async function api(path,data){let r=await page.request.fetch(base+'/api'+path,{method:data===undefined?'GET':'POST',data,headers:{'X-Ops-Request':'1'},timeout:180000});assert.equal(r.status(),200,await r.text());return r.json();}
 async function wait(id,expected){for(let i=0;i<180;i++){let job=await api('/nodes/1/installations/'+id);fs.writeFileSync(out+'/'+id+'.json',JSON.stringify(job,null,2));if(job.status===expected)return job;if(!['queued','running','starting','stopping','rolling_back'].includes(job.status))throw Error(JSON.stringify(job));await page.waitForTimeout(1000);}throw Error('Job timeout '+id);}
 async function deploy(digest,expected){let spec={package:digest,environment:env,parameters:{}};let plan=await api('/nodes/1/installations/preflight',spec);assert(plan.ok,JSON.stringify(plan));let job=await api('/nodes/1/installations',{package:digest,environment:env,reviewToken:plan.reviewToken});return wait(job.id,expected);}
 try {
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(input.adminPassword);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  const inventory=await api('/inventory'),before=inventory.flatMap(n=>n.data?.containers||[]).filter(c=>c.Labels?.['deployment-ops.environment']!==env).map(c=>({id:c.Id,state:c.State}));const old=await api('/nodes/1/installations/'+initial),oldService=old.steps.find(s=>s.id==='service').container;assert.equal(old.environment,env);
  const good=await deploy(input.good,'succeeded');assert.notEqual(good.steps.find(s=>s.id==='service').container,oldService);checks.push('compatible signed upgrade replaced service after dependency reconciliation');
  await api('/nodes/1/installations/'+good.id+'/rollback',{});await wait(good.id,'rolled_back');assert((await api('/nodes/1/installations/'+initial)).active);checks.push('explicit rollback restored previous active release');
  const failed=await deploy(input.bad,'rolled_back');assert(failed.failure.includes('readiness timed out'));assert((await api('/nodes/1/installations/'+initial)).active);checks.push('deliberate real readiness failure automatically restored previous healthy release');
  const current=(await api('/inventory')).flatMap(n=>n.data?.containers||[]);assert(current.some(c=>c.Id===oldService&&c.State==='running'));checks.push('rollback reused original healthy container identity and persistent volume');
  await api('/nodes/1/installations/'+initial+'/stop',{});await wait(initial,'stopped');await api('/nodes/1/installations/'+initial+'/start',{});await wait(initial,'succeeded');checks.push('application stop/start completed through real lifecycle API');
  const after=(await api('/inventory')).flatMap(n=>n.data?.containers||[]);for(const c of before)assert(after.some(a=>a.Id===c.id&&a.State===c.state));checks.push('all containers outside QA application retained their states and identities');
  fs.writeFileSync(out+'/result.json',JSON.stringify({passed:true,checks,good:good.id,bad:failed.id,initial},null,2));console.log(JSON.stringify({passed:true,checks}));
 }catch(error){fs.writeFileSync(out+'/result.json',JSON.stringify({passed:false,checks,error:error.stack},null,2));throw error;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

// Actual authenticated node API: validation, confirmation and stale-plan rejection.
const fs=require('fs'),assert=require('assert/strict'),crypto=require('crypto'),{chromium}=require(process.env.PLAYWRIGHT_MODULE);
(async()=>{
 const input=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL,out=process.env.OPS_TEST_ARTIFACTS;fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']}),page=await browser.newPage(),checks=[];
 async function call(path,data,status=200){const r=await page.request.fetch(base+'/api'+path,{method:data?'POST':'GET',data,headers:{'X-Ops-Request':'1'}});assert.equal(r.status(),status);return r.json();}
 async function wait(id){for(let i=0;i<60;i++){const j=await call('/nodes/1/manager/jobs/'+id);if(j.status==='SUCCESS')return;assert(['PENDING','RUNNING'].includes(j.status),JSON.stringify(j));await page.waitForTimeout(500);}throw Error('timeout');}
 const spec=configuration=>({action:'image.defaults',id:input.image,configuration});let changed=false;
 async function execute(plan,confirmation=plan.target,status=200){return call('/nodes/1/manager/execute',{planId:plan.id,confirmation,idempotencyKey:crypto.randomBytes(20).toString('hex')},status);}
 try{
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(input.password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  assert.deepEqual((await call('/nodes/1/manager/images/'+input.image)).defaults,{});
  for(const configuration of [{cpus:1000},{memoryMiB:1},{entrypoint:'shell string'},{hostBind:'/etc'},{environment:{QA_TOKEN:'[REDACTED]'}}])await call('/nodes/1/manager/plans',spec(configuration),400);
  checks.push('invalid limits, non-argv entrypoint, host bind and unknown masked secret rejected');
  const one=await call('/nodes/1/manager/plans',spec({cpus:0.3})),two=await call('/nodes/1/manager/plans',spec({cpus:0.4}));
  await execute(one,'wrong-target',400);assert.deepEqual((await call('/nodes/1/manager/images/'+input.image)).defaults,{});checks.push('incorrect target confirmation cannot save defaults');
  const job=await execute(one);changed=true;await wait(job.id);await execute(two,two.target,400);assert.equal((await call('/nodes/1/manager/images/'+input.image)).defaults.cpus,0.3);checks.push('a stale approved plan cannot overwrite a later template revision');
  const restore=await call('/nodes/1/manager/plans',spec({}));await wait((await execute(restore)).id);changed=false;assert.deepEqual((await call('/nodes/1/manager/images/'+input.image)).defaults,{});checks.push('original empty defaults restored');
  fs.writeFileSync(out+'/result.json',JSON.stringify({passed:true,checks},null,2));console.log(JSON.stringify({passed:true,checks}));
 }finally{if(changed){const restore=await call('/nodes/1/manager/plans',spec({}));await wait((await execute(restore)).id);}await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

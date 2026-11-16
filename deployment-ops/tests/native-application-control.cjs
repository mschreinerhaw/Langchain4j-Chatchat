// Scoped stop/start acceptance for the QA environment created by the deployment wizard.
const fs=require('fs'),assert=require('assert/strict');const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
(async()=>{
 const input=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL||'http://192.168.195.221:19240',environment=process.env.OPS_RUNTIME_ENV||'runtime-native-v1',id=process.env.OPS_RUNTIME_JOB||'2a6d29cebc8d4bffbd92fbff740c5223',out=process.env.OPS_TEST_ARTIFACTS||'target/native-application-control';fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']}),page=await browser.newPage({viewport:{width:1600,height:1000}}),checks=[];
 try{
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(input.password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  for(const [action,label,expected] of [['stop','停止应用','stopped'],['start','启动应用','succeeded'],['stop','停止应用','stopped']]){
   await page.goto(base+'/#/tasks/deployment/1/'+id);await page.getByRole('button',{name:label,exact:true}).click();await page.getByLabel('输入环境名称确认',{exact:true}).fill(environment);const response=page.waitForResponse(r=>r.url().endsWith('/'+id+'/'+action),{timeout:180000});await page.getByRole('button',{name:'确认'+label,exact:true}).click();assert.equal((await response).status(),200);
   let job;for(let n=0;n<450;n++){const r=await page.request.get(base+'/api/nodes/1/installations/'+id);assert.equal(r.status(),200);job=await r.json();if(job.status===expected)break;if(!['stopping','starting','queued','running'].includes(job.status))throw Error(job.status+' '+job.message);await page.waitForTimeout(2000);}assert.equal(job.status,expected);checks.push('confirmed scoped '+action+' completed as '+expected);await page.screenshot({path:out+'/'+checks.length+'-'+action+'.png',fullPage:true});
  }
  fs.writeFileSync(out+'/result.json',JSON.stringify({passed:true,environment,checks,finalState:'stopped'},null,2));console.log(JSON.stringify({passed:true,checks,finalState:'stopped'}));
 }catch(e){await page.screenshot({path:out+'/failure.png',fullPage:true});fs.writeFileSync(out+'/result.json',JSON.stringify({passed:false,checks,error:e.stack},null,2));throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

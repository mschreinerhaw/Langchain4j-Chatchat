// Actual H2 backup and post-CLI-restore verification; never stops business containers.
const fs=require('fs'),assert=require('assert/strict'),crypto=require('crypto'),{chromium}=require(process.env.PLAYWRIGHT_MODULE);
(async()=>{
 const input=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL,out=process.env.OPS_TEST_ARTIFACTS||'target/jdk-backup';fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']}),context=await browser.newContext(),page=await context.newPage();
 async function api(path,data){const r=await page.request.fetch(base+'/api'+path,{method:data===undefined?'GET':'POST',data,headers:{'X-Ops-Request':'1'},timeout:180000});assert.equal(r.status(),200,await r.text());return r.json();}
 try {
  if(input.phase==='verify'){
   await context.addCookies(JSON.parse(fs.readFileSync(out+'/old-cookies.json','utf8')));const old=await page.request.get(base+'/api/platform/health');assert.equal(old.status(),401,'Restored database must invalidate old sessions');await context.clearCookies();
  }
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(input.adminPassword);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/#/overview');
  if(input.phase==='create'){
   await api('/entries',{name:'jdk-backup-proof',url:'http://127.0.0.1:19270'});const jobs=await api('/nodes/1/installations');const backup=await api('/backups',{});const response=await page.request.get(base+'/api/backups/'+backup.name+'/download');assert.equal(response.status(),200);const bytes=await response.body();assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),backup.sha256);fs.writeFileSync(out+'/'+backup.name,bytes);
   fs.writeFileSync(out+'/before.json',JSON.stringify({backup,jobs:jobs.map(j=>({id:j.id,status:j.status,updatedAt:j.updatedAt}))},null,2));fs.writeFileSync(out+'/old-cookies.json',JSON.stringify(await context.cookies()));await api('/entries',{name:'jdk-after-backup-marker',url:'http://127.0.0.1:19270'});console.log(JSON.stringify({backup,passed:true,phase:'created'}));
  }else{
   const before=JSON.parse(fs.readFileSync(out+'/before.json','utf8')),entries=await api('/entries'),jobs=await api('/nodes/1/installations');assert(entries.some(e=>e.name==='jdk-backup-proof'));assert(!entries.some(e=>e.name==='jdk-after-backup-marker'));for(const prior of before.jobs){let current=jobs.find(j=>j.id===prior.id);assert(current);assert.equal(current.status,prior.status);assert.equal(current.updatedAt,prior.updatedAt);}
   const result={passed:true,checks:['complete backup downloaded with matching SHA256','management H2 reverted to backup records','execution H2 deployment records restored exactly','old sessions invalidated; restored administrator can log in'],backup:before.backup};fs.writeFileSync(out+'/result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
  }
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});

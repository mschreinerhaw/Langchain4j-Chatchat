// Real browser against the installed native Spring Boot service. No API mocking.
const fs=require('fs'),path=require('path');
const {strict:assert}=require('assert');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
(async()=>{
 const secret=JSON.parse(fs.readFileSync(0,'utf8')),base=process.env.OPS_QA_URL||'http://192.168.195.221:19240';
 const directory=process.env.OPS_TEST_ARTIFACTS||'target/native-browser';fs.mkdirSync(directory,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true,args:['--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1600,height:1000}}),errors=[],checks=[];
 page.on('pageerror',error=>errors.push(error.message));
 async function screenshot(name){await page.screenshot({path:path.join(directory,name+'.png'),fullPage:true});}
 async function navigate(route){await page.goto(base+'/#'+route);await page.waitForSelector('h1');await page.waitForTimeout(1800);}
 try{
  await page.goto(base+'/#/login');await page.getByLabel('账号',{exact:true}).fill(secret.username||'admin');await page.getByLabel('密码',{exact:true}).fill(secret.password);await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.waitForURL('**/#/overview');await page.waitForResponse(r=>r.url().endsWith('/api/inventory')&&r.status()===200,{timeout:30000});await page.waitForTimeout(1200);
  await screenshot('overview');assert(await page.locator('main').innerText());checks.push('native login and real inventory');
  for(const [route,name] of [['/applications','applications'],['/hosts','hosts'],['/images','images'],['/tasks','tasks'],['/alerts','alerts'],['/settings','system-management'],['/audit','audit']]){
   await navigate(route);assert(!await page.locator('[role=alert]').count(),name+' displays request error');assert(!/\?{4,}/.test(await page.locator('main').innerText()),name+' encoding corruption');await screenshot(name);checks.push(name+' loaded real API');
  }
  await navigate('/applications');const details=page.getByRole('link',{name:'进入应用',exact:true});
  if(await details.count()){
   await details.first().click();await page.waitForTimeout(1000);await screenshot('application-detail');
   for(const label of ['服务组件','配置','资源监控','部署历史']){await page.getByRole('link',{name:label,exact:true}).click();await page.waitForTimeout(300);}
   checks.push('application component/configuration/monitoring/history routes');
  }
  await navigate('/deploy');await screenshot('deployment-wizard');checks.push('deployment workflow rendered');
  for(const width of [1280,1600]){await page.setViewportSize({width,height:900});await navigate('/overview');assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal page overflow at '+width);await screenshot('overview-'+width);}
  if(secret.lifecycle&&!secret.image){const inventory=await(await page.request.get(base+'/api/inventory')).json();secret.image=inventory.flatMap(n=>n.data?.images||[]).find(image=>image.RepoTags?.includes('redis:7.4-alpine'))?.Id;assert(secret.image,'Offline Redis fixture image missing');}
  if(secret.image){
   const name='ops-vue-qa-'+Date.now();
   async function approve(target=name){const dialog=page.getByRole('dialog');await dialog.waitFor();await dialog.getByRole('textbox').fill(target);const promise=page.waitForResponse(r=>r.url().endsWith('/manager/execute')&&r.request().method()==='POST');await dialog.getByRole('button',{name:'确认并执行',exact:true}).click();const response=await promise;assert.equal(response.status(),200);const job=await response.json();for(let attempt=0;attempt<90;attempt++){const status=await (await page.request.get(base+'/api/nodes/1/manager/jobs/'+job.id)).json();if(status.status==='SUCCESS')return;if(!['RUNNING','PENDING'].includes(status.status))throw Error(JSON.stringify(status));await page.waitForTimeout(400);}throw Error('Operation did not complete');}
   const volume=name+'-data';await navigate('/containers');await page.getByRole('button',{name:'创建数据卷',exact:true}).click();await page.getByLabel('数据卷名称',{exact:true}).fill(volume);await page.getByRole('button',{name:'预览数据卷计划',exact:true}).click();await approve(volume);checks.push('real persistent volume created in selected storage');
   await navigate('/containers');await page.getByRole('button',{name:'＋ 创建容器',exact:true}).click();await page.getByLabel('容器名称',{exact:true}).fill(name);await page.getByLabel('本地镜像',{exact:true}).selectOption(secret.image);await page.getByLabel('启动命令 CMD（每行一个参数）',{exact:true}).fill('sh\n-c\necho native-vue-live-log; sleep 600');await page.getByRole('button',{name:'＋ 添加数据卷映射',exact:true}).click();await page.getByLabel('来源数据卷',{exact:true}).selectOption(volume);await page.getByLabel('容器内目录',{exact:true}).fill('/data');
   await page.getByRole('button',{name:'预览创建计划',exact:true}).click();await approve();checks.push('real container created with approval');
   async function row(){await navigate('/containers');await page.getByRole('textbox',{name:'搜索名称、状态或主机…'}).fill(name);const row=page.locator('tbody tr').filter({hasText:name});await row.waitFor();return row;}
   await (await row()).getByRole('button',{name:'启动',exact:true}).click();await approve();checks.push('real container started');
   await (await row()).getByRole('link',{name:'详情 / 日志',exact:true}).click();await page.locator('.log-viewer').filter({hasText:'native-vue-live-log'}).waitFor({timeout:30000});await screenshot('container-live-log');checks.push('real container metrics and SSE logs');
   await (await row()).getByRole('button',{name:'停止',exact:true}).click();await approve();checks.push('real container stopped');
   await (await row()).getByRole('button',{name:'删除',exact:true}).click();await approve();checks.push('owned stopped container deleted without volume deletion');
   const retained=await(await page.request.get(base+'/api/nodes/1/manager/volumes')).json();const record=retained.Volumes.find(v=>v.Name===volume);assert(record,'Persistent volume must survive container deletion');assert(record.Options.device.startsWith((secret.platformRoot||'/mnt/disk01')+'/'),'Volume must use the selected platform storage');checks.push('persistent volume retained in selected storage');
  }
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(directory,'result.json'),JSON.stringify({passed:true,checks,errors},null,2));console.log(JSON.stringify({passed:true,checks},null,2));
 }catch(error){await screenshot('failure');fs.writeFileSync(path.join(directory,'result.json'),JSON.stringify({passed:false,checks,errors,error:error.stack},null,2));throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});

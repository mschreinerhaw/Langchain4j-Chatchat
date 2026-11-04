import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root='target/monitor-403-release',origin='http://192.168.195.221:18100';
const auth=JSON.parse(readFileSync(root+'/browser-auth.private.json','utf8')),facts=JSON.parse(readFileSync(root+'/monitor-live-verification.json','utf8')),source=JSON.parse(readFileSync(root+'/source-artifacts.json','utf8'));
mkdirSync(root+'/browser',{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server']});
const report={sourceCommit:source.sourceCommit,taskId:facts.taskId,runId:facts.runId,status:'RUNNING',pageErrors:[],failedRequests:[],codingRequests:[],assets:[],injectedTaskData:false};
try {
 const page=await browser.newPage({viewport:{width:1500,height:1100}});
 page.on('pageerror',e=>report.pageErrors.push(e.message));
 page.on('response',r=>{const u=new URL(r.url());if(u.pathname.includes('/api/v1/')&&r.status()>=400)report.failedRequests.push({path:u.pathname,status:r.status()});if(u.pathname.includes('/runtime/runs/'+facts.runId+'/coding'))report.codingRequests.push({path:u.pathname,status:r.status(),cacheControl:r.headers()['cache-control']});});
 await page.addInitScript(({session,task})=>{localStorage.setItem('chatchat.auth.session',JSON.stringify(session));sessionStorage.setItem('chatchat.runtime.selectedTaskId',task);},{session:auth,task:facts.taskId});
 await page.goto(origin+'/index.html#/w/zhice/tasks',{waitUntil:'domcontentloaded'});
 await page.locator('.coding-monitor').waitFor({timeout:60000});
 await page.locator('.coding-monitor > summary').click();
 const panel=page.locator('[data-testid="coding-operational-status"]');await panel.waitFor({timeout:30000});
 await page.waitForTimeout(8000);
 const text=await page.locator('.coding-monitor').innerText();assert.match(text,/1 个委派任务/);assert.match(text,/COMPLETED/);assert.ok(!text.includes('0 个委派任务')&&!text.includes('该运行暂无 Coding 委派记录')&&!text.includes('请求失败'));
 assert.ok(report.codingRequests.length>=1);assert.ok(report.codingRequests.every(r=>r.path.endsWith('/coding/status')&&r.status===200&&r.cacheControl?.includes('no-store')));assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.failedRequests,[]);
 for(const prefix of ['index.html','assets/TasksView-','assets/CodingTaskMonitor-']) {
  const entry=Object.keys(source.apiStaticFiles).find(n=>n.startsWith('BOOT-INF/classes/static/'+prefix)&&(prefix==='index.html'||n.endsWith('.js')));assert.ok(entry,prefix);
  const url=origin+entry.replace('BOOT-INF/classes/static','');const response=await page.request.get(url);assert.equal(response.status(),200);const hash=createHash('sha256').update(await response.body()).digest('hex');assert.equal(hash,source.apiStaticFiles[entry]);report.assets.push({path:new URL(url).pathname,sha256:hash});
 }
 await page.locator('.coding-monitor').scrollIntoViewIfNeeded();await page.locator('.coding-monitor').screenshot({path:root+'/browser/original-task-monitor.png'});
 report.status='PASSED';report.realDelegateCount=1;report.realDelegateState='COMPLETED';report.parentStateUnchanged=facts.parentStatus;report.observationMs=8000;report.repeatedForbiddenRequests=0;report.privateDetailsRequested=false;
} catch(error) {report.status='FAILED';report.error=String(error.message);throw error;}
finally {writeFileSync(root+'/browser/verification.json',JSON.stringify(report,null,2));await browser.close();console.log(JSON.stringify(report));}

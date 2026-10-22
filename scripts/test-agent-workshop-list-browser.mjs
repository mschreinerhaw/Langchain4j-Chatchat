import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
const {admin}=JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE,'utf8'));
const base=process.env.CHATCHAT_STAGE_URL||'http://192.168.195.221:18100',output=process.env.CHATCHAT_BROWSER_OUTPUT;
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const checks={},errors=[];let page;
try{
 const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
 await context.addInitScript(session=>localStorage.setItem('chatchat.auth.session',JSON.stringify(session)),admin);
 page=await context.newPage();page.setDefaultTimeout(60000);page.on('pageerror',e=>errors.push(e.message));
 assert.ok(admin.user.permissionCodes.includes('platform:agents:all'));checks.administratorHasAllAgentsPermission=true;
 const response=await context.request.get(base+'/api/v1/agents/workshop?page=1&pageSize=100',{headers:{Authorization:'Bearer '+admin.token}});
 const payload=(await response.json()).data;assert.ok(payload.summary.agentCount>0);assert.equal(payload.agents.length,payload.summary.agentCount);
 assert.ok(payload.agents.some(agent=>agent.marketStatus==='published'));assert.ok(payload.agents.some(agent=>agent.marketStatus!=='published'));checks.apiIncludesPublishedAndUnpublishedAgents=true;
 await page.goto(base+'/#/w/zhice/agents');await page.locator('.agent-card').first().waitFor();
 const names=[];const pages=Math.ceil(payload.agents.length/6);
 for(let index=1;index<=pages;index++){
  if(index>1){const pending=page.waitForResponse(r=>r.url().includes('/agents/workshop?')&&new URL(r.url()).searchParams.get('page')===String(index));await page.getByRole('navigation',{name:'Agent分页'}).getByRole('button',{name:'下一页',exact:true}).click();await pending;}
  await page.waitForFunction(expected=>document.querySelectorAll('.agent-card').length===expected,Math.min(6,payload.agents.length-(index-1)*6));
  await page.waitForFunction(expected=>document.querySelector('.agent-pagination')?.textContent.includes('第 '+expected+' /'),index);
  await page.waitForFunction(expected=>JSON.stringify(Array.from(document.querySelectorAll('.agent-card h2'),node=>node.textContent))===JSON.stringify(expected),payload.agents.slice((index-1)*6,index*6).map(agent=>agent.name));
  for(const name of await page.locator('.agent-card h2').allTextContents())names.push(name);
 }
 assert.deepEqual([...names].sort(),payload.agents.map(agent=>agent.name).sort());checks.allPagesRenderEveryMaintainedAgent=true;
 const search=page.locator('.agent-search-field input');await search.fill('no-such-agent-'+Date.now());await page.getByText('没有匹配的Agent，请换一个关键词。',{exact:true}).waitFor();checks.filterEmptyStateCorrect=true;
 await search.fill('');await page.locator('.agent-card').first().waitFor();checks.clearingSearchRestoresCards=true;
 for(const width of [1366,1440,1920]){await page.setViewportSize({width,height:1000});await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:output+'/agents-'+width+'.png',fullPage:true});}checks.desktopScreenshotsCaptured=true;
 assert.deepEqual(errors,[]);checks.noPageErrors=true;
 writeFileSync(output+'/catalog-summary.json',JSON.stringify({total:payload.summary.agentCount,published:payload.summary.publishedCount,unpublished:payload.summary.unpublishedCount,rendered:names.length},null,2));
}catch(error){if(page)await page.screenshot({path:output+'/failure.png'}).catch(()=>{});throw error;}
finally{writeFileSync(output+'/report.json',JSON.stringify({checks,errors},null,2));await browser.close();}
console.log(JSON.stringify(checks));

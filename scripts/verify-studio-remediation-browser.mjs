import {chromium} from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';

const base=process.env.STUDIO_VERIFY_BASE || 'http://192.168.195.221:18100';
const auth=JSON.parse(readFileSync(process.env.STUDIO_VERIFY_AUTH,'utf8')).data;
const output=process.env.STUDIO_VERIFY_OUT || 'target/studio-remediation-browser';
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
const context=await browser.newContext({viewport:{width:1600,height:1000}});
await context.addInitScript(a=>localStorage.setItem('chatchat.auth.session',JSON.stringify(a)),auth);
const page=await context.newPage(),errors=[],report={mockedRequests:false,layouts:[]};
page.on('pageerror',e=>errors.push(e.message));
try {
  await page.goto(base+'/#/w/data-science/sqlStudio');
  await page.locator('.sql-ide-shell').waitFor();
  const toggle=page.getByRole('button',{name:'切换工程文件',exact:true});
  const assistant=page.getByRole('button',{name:'切换 AI 工程助手',exact:true});
  if(await assistant.getAttribute('aria-pressed')==='true')await assistant.click();
  for(const width of [1600,1280,1066]){
    await page.setViewportSize({width,height:900});
    for(let i=0;i<3;i++){
      if(await toggle.getAttribute('aria-pressed')!=='true')await toggle.click();
      await page.waitForTimeout(150);
      const open=await page.locator('.sql-ide-main').boundingBox();
      await toggle.click();await page.waitForTimeout(150);
      assert.equal(await page.locator('.sql-explorer-resizer').count(),0);
      assert.equal(await page.locator('.sql-resource-sidebar').isVisible(),false);
      const closed=await page.locator('.sql-ide-main').boundingBox(),shell=await page.locator('.sql-ide-shell').boundingBox();
      assert.ok(closed.width>open.width+100);
      assert.ok(Math.abs(closed.x-shell.x)<2);
      const hidden=await page.locator('.sql-editor-hidden').evaluateAll(nodes=>nodes.every(n=>getComputedStyle(n).display==='none'));
      assert.equal(hidden,true);
      report.layouts.push({width,cycle:i,expandedWidth:open.width,collapsedWidth:closed.width,hiddenEditorsHaveNoBox:hidden});
    }
    await page.screenshot({path:output+'/sql-collapsed-'+width+'.png'});
  }
  await page.goto(base+'/#/w/data-science/dataScienceDevelop');
  await page.waitForTimeout(1500);
  // The router's Python entry is also accessible from the workspace navigation.
  if(!(await page.locator('.python-studio').count())){
    const nav=page.getByText('Python 开发',{exact:true});
    if(await nav.count())await nav.first().click();
  }
  await page.locator('.python-studio,.ds-develop-pane .ds-empty').first().waitFor();
  assert.equal(await page.getByText('复用已有 Python 代码进行分析',{exact:false}).count(),0);
  assert.equal(await page.locator('.python-reuse-panel').count(),0);
  await page.screenshot({path:output+'/python-studio.png'});
  report.pythonBannerRemoved=true;report.errors=errors;
  assert.deepEqual(errors,[]);
  writeFileSync(output+'/verification.json',JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
} catch(error) {
  await page.screenshot({path:output+'/failure.png'});
  writeFileSync(output+'/failure.json',JSON.stringify({...report,errors,error:error.message},null,2));
  throw error;
} finally {await browser.close();}

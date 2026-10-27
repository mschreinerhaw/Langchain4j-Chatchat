import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
const output=path.resolve('../../docs/runtime-workbench-20261027');
await mkdir(output,{recursive:true});
const server=await createServer({server:{host:'127.0.0.1',port:0},logLevel:'error'});
let browser;
try {
  await server.listen();
  browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  const page=await browser.newPage();const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/tests/execution-workbench.html`,{timeout:120000,waitUntil:'domcontentloaded'});
  await page.waitForFunction(()=>typeof window.setWorkbenchFixture==='function');
  const fixture=process.env.WORKBENCH_FIXTURE?JSON.parse(await readFile(process.env.WORKBENCH_FIXTURE,'utf8')):
    {id:'visual-fixture',role:'assistant',streaming:true,status:'RUNNING',taskTitle:'客户交易与资产分析',taskId:'visual-fixture',steps:[
      {sequence:1,type:'TASK_STARTED',timestamp:Date.now()-150000},
      {sequence:2,type:'PLAN',status:'COMPLETED'},
      {sequence:3,type:'TOOL_CALL',eventId:'data',timestamp:Date.now()-140000},
      {sequence:4,type:'TOOL_RESULT',parentEventId:'data',timestamp:Date.now()-130000},
      {sequence:5,type:'RUNTIME_OBSERVATION',timestamp:Date.now()-120000,payload:{metadata:{
        eventKind:'ANALYSIS_OPERATION',taskId:'coding',operationId:'compute',eventState:'RUNNING',executorConfirmed:true,timeBasis:'OBSERVED_STATE'}}}
    ]};
  await page.evaluate(message=>window.setWorkbenchFixture(message),fixture);
  await page.locator('.task-workbench').waitFor();
  const checks=[];
  for(const [label,width]of [['desktop',1280],['mobile',390]]) {
    await page.setViewportSize({width,height:1000});
    const panel=page.locator('.task-workbench');
    const toggle=panel.locator('.runtime-collapse-button');
    if(await toggle.count() && await toggle.getAttribute('aria-expanded')==='false')await toggle.click();
    await page.screenshot({path:path.join(output,`${process.env.WORKBENCH_FIXTURE?'real-events':'visual'}-${label}.png`),fullPage:true});
    const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
    if(overflow)throw Error(`${label}: horizontal overflow`);
    if(await panel.locator('.workbench-phase-strip li').count()!==5)throw Error('Missing stable phases');
    checks.push({label,width,horizontalOverflow:overflow,phases:5});
  }
  await page.locator('.workbench-diagnostics summary').focus();await page.keyboard.press('Enter');
  if(!await page.locator('.workbench-diagnostics').evaluate(el=>el.open))throw Error('Keyboard expansion failed');
  if(errors.length)throw Error(errors.join('\n'));
  await writeFile(path.join(output,process.env.WORKBENCH_FIXTURE?'real-events-browser.json':'browser.json'),JSON.stringify({source:process.env.WORKBENCH_FIXTURE?'backend-events':'visual-fixture',checks,keyboardDetails:true,pageErrors:errors},null,2));
  console.log(JSON.stringify({checks,pageErrors:errors}));
} finally {await browser?.close();await server.close();}

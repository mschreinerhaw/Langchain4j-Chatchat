/* Real browser stability/trace acceptance. Credentials stay in an ignored session file.
 * SQL_UI_AUTH_FILE, SQL_UI_STATE_FILE, SQL_UI_URL, SQL_UI_EVIDENCE required.
 * SQL_UI_BASELINE=1 records old UI behavior instead of requiring new lazy navigation.
 * 1/1.25/1.5 use equivalent viewport + DPR emulation; native browser zoom is not asserted.
 */
const {chromium}=require('../chatchat-api/web-app/node_modules/playwright-core');
const fs=require('node:fs'),assert=require('node:assert/strict');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn){const end=Date.now()+180000;while(Date.now()<end){if(await fn())return;await delay(100);}throw Error('Real page condition timed out');}
async function choose(page,label,value){const input=page.getByRole('combobox',{name:label,exact:true});await until(()=>input.isEnabled());await input.click();
 for(const option of await page.getByRole('listbox',{name:label,exact:true}).getByRole('option').elementHandles())if(await option.getAttribute('data-value')===value){await option.click();return;}
 throw Error('Authorized option unavailable: '+label);
}
(async()=>{
 const auth=JSON.parse(fs.readFileSync(process.env.SQL_UI_AUTH_FILE)).data,state=JSON.parse(fs.readFileSync(process.env.SQL_UI_STATE_FILE));
 const out=process.env.SQL_UI_EVIDENCE,baseline=process.env.SQL_UI_BASELINE==='1';fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({executablePath:process.env.SQL_UI_EDGE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--no-proxy-server']});
 const report={baseline,mockedRequests:false,zoomMethod:'Equivalent CSS viewport and device-pixel-ratio emulation, not native browser toolbar zoom',scenarios:[]};
 let fixture;const fixtureName='stability-tabs-'+Date.now()+'.sql';
 const apiRoot=new URL(process.env.SQL_UI_URL).origin+'/api/v1/data-science/sql/projects/'+encodeURIComponent(state.projectId);
 const headers={Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-science'};
 try{for(const scale of process.env.SQL_UI_SCALE?[Number(process.env.SQL_UI_SCALE)]:process.env.SQL_UI_SINGLE_SCALE==='1'?[1]:[1,1.25,1.5]){
  const context=await browser.newContext({viewport:{width:Math.floor(1600/scale),height:Math.floor(1000/scale)},deviceScaleFactor:scale,permissions:['clipboard-read','clipboard-write']});
  if(!fixture){const response=await context.request.put(apiRoot+'/files',{headers,data:{path:fixtureName,kind:'SQL',content:'SELECT 2 AS studio_stability;',expectedVersion:0}});assert.equal(response.ok(),true);fixture=(await response.json()).data;}
  await context.addInitScript(a=>{localStorage.setItem('chatchat.auth.session',JSON.stringify(a));window.__sqlStability={shifts:[],longTasks:[]};
   for(const [type,key] of [['layout-shift','shifts'],['longtask','longTasks']])try{new PerformanceObserver(list=>{for(const e of list.getEntries())window.__sqlStability[key].push({at:e.startTime,duration:e.duration,value:e.value||0,recentInput:e.hadRecentInput||false});}).observe({type,buffered:true});}catch{}
  },auth);
  const page=await context.newPage(),errors=[],requests=[];let navigations=0;
  page.on('pageerror',e=>errors.push(e.message));page.on('framenavigated',f=>{if(f===page.mainFrame())navigations++;});
  page.on('request',r=>{if(r.url().includes('/api/'))requests.push({path:new URL(r.url()).pathname,method:r.method(),operation:r.method()==='POST'&&r.url().endsWith('/operations')?r.postDataJSON().arguments:null});});
  const cdp=await context.newCDPSession(page);await cdp.send('Performance.enable');
  await cdp.send('Tracing.start',{categories:'devtools.timeline,blink.user_timing',transferMode:'ReturnAsStream'});
  await page.goto(process.env.SQL_UI_URL);await page.getByLabel('SQL 工程',{exact:true}).selectOption(state.projectId);
  await page.getByRole('button',{name:state.fileName||'publication.sql',exact:false}).first().click();
  const editor=page.locator('.sql-monaco-host .monaco-editor');await editor.waitFor();
  const identity=await page.locator('.sql-monaco-host').elementHandle(),shell=await page.locator('.sql-ide-shell').elementHandle();
  if(!baseline)await until(async()=>!!await page.locator('.sql-monaco-host').getAttribute('data-monaco-editor-id'));
  const editorId=await page.locator('.sql-monaco-host').getAttribute('data-monaco-editor-id');
  const identityAt=async label=>{if(process.env.SQL_UI_SINGLE_SCALE==='1')console.log(label,await identity.evaluate(el=>({connected:el.isConnected,same:el===document.querySelector('.sql-monaco-host .monaco-editor'),count:document.querySelectorAll('.sql-monaco-host .monaco-editor').length})),await shell.evaluate(el=>el===document.querySelector('.sql-ide-shell')));};
  await identityAt('initial');
  const nav=page.getByLabel('展开或折叠数据源导航',{exact:true});
  if(!baseline){
   assert.equal(await nav.getAttribute('aria-expanded'),'false');
   await choose(page,'选择数据源',state.source);await choose(page,'选择数据库或 Schema',state.namespace);
   await until(()=>page.getByLabel('刷新数据库上下文',{exact:true}).isEnabled());
   assert.equal(requests.filter(r=>r.operation?.operation==='SEARCH'&&r.operation.pageSize>1).length,0);
   await nav.click();await until(async()=>await page.locator('[role=treeitem]').count()>0);
   const count=requests.filter(r=>r.operation?.operation==='SEARCH'&&r.operation.pageSize>1).length;
   await nav.click();await nav.click();await delay(100);
   assert.equal(requests.filter(r=>r.operation?.operation==='SEARCH'&&r.operation.pageSize>1).length,count);
   await nav.click();
  }
  const metrics=async()=>Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(x=>[x.name,x.value]));
  await identityAt('metadata');
  const start=await metrics(),navigationStart=navigations;
  async function source(){await editor.click();await page.keyboard.press('Control+KeyA');await page.keyboard.press('Control+KeyC');return (await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n');}
  const original=await source(),draft='select 1 as stability_value;\n'+Array.from({length:60},(_,i)=>'-- controlled line '+i).join('\n');
  await page.keyboard.insertText(draft);
  await page.keyboard.press('ArrowRight');await page.keyboard.type('\n-- typing regression',{delay:15});
  const before=await source();
  await page.getByRole('button',{name:fixtureName,exact:false}).first().click();assert.equal((await source()).trim(),'SELECT 2 AS studio_stability;');
  await page.locator('.sql-editor-tabs').getByRole('button',{name:state.fileName||'publication.sql',exact:false}).first().click();assert.equal(await source(),before);
  await page.getByLabel('关闭标签 '+fixtureName,{exact:true}).click();
  await identityAt('tabs');
  await editor.locator('textarea').focus();await page.keyboard.press('Control+End');await page.keyboard.press('ArrowUp');await delay(80);
  const cursorText=()=>page.locator('.sql-ide-status').innerText().then(text=>text.match(/行\s*\d+，列\s*\d+/)?.[0]);
  const slider=editor.locator('.overflow-guard > .monaco-scrollable-element > .scrollbar.vertical > .slider');
  async function settledSlider(){let previous=-1,same=0,value;for(let i=0;i<30;i++){value=parseFloat(await slider.evaluate(el=>el.style.top));same=Math.abs(value-previous)<.1?same+1:0;if(same>=3)return value;previous=value;await delay(50);}throw Error('Editor scrolling did not settle');}
  const cursorBefore=await cursorText(),sliderBefore=await settledSlider();
  if(process.env.SQL_UI_SCALE)await page.evaluate(async()=>{const url=performance.getEntriesByType('resource').find(x=>/\/EditorPreferences-[^/]+\.js$/.test(x.name))?.name;const module=await import(url);const api=Object.values(module).find(value=>value&&typeof value.getEditors==='function');window.__sqlDebugEditor=api.getEditors().find(e=>e.getContainerDomNode()===document.querySelector('.sql-monaco-host'));});
  const actualState=()=>page.evaluate(()=>{const e=window.__sqlDebugEditor;if(!e)return null;const layout=e.getLayoutInfo();return{id:e.getId(),scrollTop:e.getScrollTop(),scrollHeight:e.getScrollHeight(),position:e.getPosition(),visible:e.getVisibleRanges(),layout:{width:layout.width,height:layout.height,contentWidth:layout.contentWidth,horizontalScrollbarHeight:layout.horizontalScrollbarHeight,minimap:layout.minimap},view:e.saveViewState()?.viewState};});
  const actualBefore=process.env.SQL_UI_SCALE?await actualState():null;
  const visibleAnchor=()=>editor.evaluate(el=>{const box=el.querySelector('.overflow-guard').getBoundingClientRect();const line=[...el.querySelectorAll('.view-lines .view-line')].find(x=>x.getBoundingClientRect().bottom>box.top+1);return{text:line?.textContent,offset:line?.getBoundingClientRect().top-box.top,width:box.width,height:box.height};});
  const anchorBefore=await visibleAnchor();
  assert.ok(cursorBefore);assert.ok(sliderBefore>0);
  for(let i=0;i<3;i++){
   await page.getByLabel('切换工程文件',{exact:true}).click();
   if(process.env.SQL_UI_SCALE && i===0)console.log('after-first-toggle',JSON.stringify(await actualState()));
   assert.equal(await page.getByLabel('调整工程文件宽度',{exact:true}).count(),0);
   const tracks=await page.locator('.sql-ide-shell').evaluate(el=>getComputedStyle(el).gridTemplateColumns.split(' '));
   assert.equal(parseFloat(tracks[0]),0);assert.equal(parseFloat(tracks[1]),0);
   await page.screenshot({path:out+'/left-collapsed-'+Math.round(scale*100)+'.png'});
   await page.getByLabel('切换工程文件',{exact:true}).click();
   await page.getByLabel('切换 AI 工程助手',{exact:true}).click();await page.getByLabel('切换 AI 工程助手',{exact:true}).click();
  }
  if(!baseline){await nav.click();await nav.click();assert.equal(await nav.getAttribute('aria-expanded'),'false');}
  for(const [label,delta] of [['调整工程文件宽度',30],['调整 AI 助手宽度',-30]]){
   const grip=page.getByLabel(label,{exact:true});
   for(const distance of [delta,-delta]){const box=await grip.boundingBox();await page.mouse.move(box.x+2,box.y+30);await page.mouse.down();
    for(let step=1;step<=10;step++)await page.mouse.move(box.x+2+distance*step/10,box.y+30);await page.mouse.up();await delay(50);}
  }
  await page.mouse.move(10,10);await delay(200);
  await identityAt('panels');
  const beforeRefocusAnchor=await visibleAnchor();
  await editor.locator('textarea').focus();await delay(80);
  assert.equal(await cursorText(),cursorBefore);
  const sliderAfter=await settledSlider(),anchorAfter=await visibleAnchor();
  if(process.env.SQL_UI_SCALE)console.log(JSON.stringify({scale,anchorBefore,beforeRefocusAnchor,anchorAfter,actualBefore,actualAfter:await actualState()}));
  assert.equal(anchorAfter.text,anchorBefore.text,JSON.stringify({scale,anchorBefore,anchorAfter}));
  assert.ok(Math.abs(anchorAfter.offset-anchorBefore.offset)<3,JSON.stringify({scale,anchorBefore,anchorAfter}));
  assert.equal(await source(),before);assert.equal(await identity.evaluate(el=>el===document.querySelector('.sql-monaco-host')),true);
  assert.equal(await page.locator('.sql-monaco-host').getAttribute('data-monaco-editor-id'),editorId);
  assert.equal(await shell.evaluate(el=>el===document.querySelector('.sql-ide-shell')),true);assert.equal(navigations,navigationStart);
  const extremeWidths=[];
  for(const [label,pane] of [['调整工程文件宽度','.sql-resource-sidebar'],['调整 AI 助手宽度','.sql-ide-assistant']]){
   const grip=page.getByLabel(label,{exact:true}),g=await grip.boundingBox(),current=await page.locator(pane).boundingBox();
   await page.mouse.move(g.x+2,g.y+25);await page.mouse.down();await page.mouse.move(g.x+2+(label==='调整工程文件宽度'?1:-1)*(520-current.width),g.y+25);await page.mouse.up();await delay(200);
   const bounds=await page.locator('.sql-ide-shell').evaluate(el=>({width:el.clientWidth,scrollWidth:el.scrollWidth,editorWidth:el.querySelector('.sql-ide-main').clientWidth}));extremeWidths.push(bounds);
   assert.ok(bounds.scrollWidth<=bounds.width+1,JSON.stringify({scale,extreme:label,...bounds}));assert.ok(bounds.editorWidth>=199);
   await grip.dblclick();await delay(100);
  }
  // Formatting uses the real editor. Restore the original saved source afterwards, without saving it.
  await page.keyboard.insertText('select 1 as stability_value;');
  await page.getByRole('button',{name:'格式化',exact:true}).first().click();await delay(150);
  assert.match(await source(),/select\s+1/i);
  await page.keyboard.insertText(original);assert.equal(await source(),original);
  const end=await metrics(),observations=await page.evaluate(()=>window.__sqlStability);
  const traceDone=new Promise(resolve=>cdp.once('Tracing.tracingComplete',resolve));await cdp.send('Tracing.end');const trace=await traceDone;let raw='';
  for(;;){const chunk=await cdp.send('IO.read',{handle:trace.stream});raw+=chunk.data;if(chunk.eof)break;}await cdp.send('IO.close',{handle:trace.stream});
  fs.writeFileSync(out+'/trace-'+Math.round(scale*100)+'.json',raw);
  const events=JSON.parse(raw).traceEvents,summary={};
  for(const name of ['Layout','UpdateLayoutTree','Paint','PrePaint','RunTask']){const matched=events.filter(x=>x.name===name&&x.ph==='X');summary[name]={count:matched.length,totalMs:matched.reduce((n,x)=>n+(x.dur||0),0)/1000};}
  assert.equal(errors.length,0);
  report.scenarios.push({scale,devicePixelRatio:await page.evaluate(()=>devicePixelRatio),editorPreserved:true,editorId,studioPreserved:true,noPageRefresh:true,draftPreserved:true,fileTabRoundTripPreserved:true,cursorPreserved:true,scrollPreserved:true,visibleAnchorBefore:anchorBefore,visibleAnchorAfter:anchorAfter,scrollbarThumbBefore:sliderBefore,scrollbarThumbAfter:sliderAfter,collapsedTracksZero:true,
   extremeWidths,fullTableDirectoryRequests:requests.filter(r=>r.operation?.operation==='SEARCH'&&r.operation.pageSize>1).length,
   metricsDelta:Object.fromEntries(['LayoutCount','RecalcStyleCount','LayoutDuration','RecalcStyleDuration','TaskDuration'].map(k=>[k,end[k]-start[k]])),traceSummary:summary,observations,errors});
  await context.close();
 }
 fs.writeFileSync(out+'/stability-browser-proof.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{if(fixture){const cleanup=await browser.newContext();const response=await cleanup.request.delete(apiRoot+'/files/'+encodeURIComponent(fixture.id)+'?expectedVersion='+fixture.version,{headers});assert.equal(response.ok(),true);await cleanup.close();}await browser.close();}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});

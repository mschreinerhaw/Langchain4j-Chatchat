// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {describe,it,expect,vi,beforeEach,afterEach} from 'vitest';
import SqlSchemaExplorer from './SqlSchemaExplorer.vue';
import * as api from '../../services/sqlStudioApi';
vi.mock('../../services/sqlStudioApi',()=>({sqlMetadataContext:vi.fn(),sqlMetadataProviders:vi.fn(),sqlMetadataAssets:vi.fn(),createSqlMetadataContext:vi.fn(),sqlMetadataOperation:vi.fn()}));
let app,host,explorer;
const tick=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick();};
const table={database:'catalog',schema:'public',tableName:'orders',tableRef:'public-orders'};
const result=items=>({metadata:{contractVersion:'sql-studio.metadata.v2',status:'SUCCEEDED',snapshotId:'v1',doesNotExecuteSql:true,items,totalMatched:items.length,hasMore:false}});
beforeEach(()=>{
  localStorage.clear();
  vi.resetAllMocks();api.sqlMetadataProviders.mockResolvedValue([{serviceId:'registered',name:'Metadata'}]);
  api.sqlMetadataAssets.mockResolvedValue(result([{assetRef:'a',name:'Orders source'},{assetRef:'b',name:'Other source'}]));
  api.createSqlMetadataContext.mockImplementation(async(_p,input)=>({discoveryId:'d-'+input.bindings[0].assetRef,bindings:[{...input.bindings[0],bindingId:'binding',sourceDialect:'trino'}]}));
  api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>result(args.operation==='LIST_NAMESPACES'?[{database:'catalog',schema:'public'},{database:'catalog',schema:'private'}]:args.operation==='DESCRIBE'?[{...table,columns:[{name:'id',dataType:'bigint'}]}]:[table]));
  host=document.createElement('div');document.body.append(host);
});
afterEach(()=>{app?.unmount();host.remove();});
async function mount(extra={},openNavigation=true){const props=reactive({projectId:'project',identity:{tenantId:'t',userId:'u'},dialect:'trino',...extra});app=createApp({render:()=>h(SqlSchemaExplorer,{...props,ref:value=>explorer=value})});app.mount(host);await tick();if(openNavigation){host.querySelector('.sql-schema-explorer>header button').click();await tick();}return props;}
async function select(index,value,scope=host){
  if(index===0){const input=scope.querySelector('select');input.value=value;input.dispatchEvent(new Event('change',{bubbles:true}));await tick();return;}
  const input=scope.querySelector(`[aria-label="${index===1?'选择数据源':'选择数据库或 Schema'}"]`);
  input.focus();input.click();await tick();
  const list=document.getElementById(input.getAttribute('aria-controls'));
  const option=[...list.querySelectorAll('[role=option]')].find(item=>item.dataset.value===value);
  expect(option,`authorized option ${value}`).toBeDefined();option.click();await tick();
}
describe('Schema Explorer API integration',()=>{
  it('defaults to collapsed and fetches tables only on demand while preserving the shared AI context',async()=>{
    await mount({},false);await select(1,'a');
    expect(host.querySelector('.sql-schema-explorer>header button').getAttribute('aria-expanded')).toBe('false');
    expect(api.sqlMetadataOperation.mock.calls.some(([, , ,args])=>args.operation==='SEARCH'&&args.pageSize>1)).toBe(false);
    expect(explorer.assistantContext()).toBe('d-a');
    await host.querySelector('.sql-schema-explorer>header button').click();await tick();
    expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(1);
    const count=api.sqlMetadataOperation.mock.calls.length;
    await host.querySelector('.sql-schema-explorer>header button').click();await host.querySelector('.sql-schema-explorer>header button').click();await tick();
    expect(api.sqlMetadataOperation.mock.calls.length).toBe(count);
    await host.querySelector('.sql-schema-explorer>header button').click();await select(1,'b');
    expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(0);
    expect(explorer.assistantContext()).toBe('d-b');
  });
  it('uses one live context for the editor and assistant without preselecting tables',async()=>{
    await mount();await select(1,'a');await select(2,JSON.stringify(['catalog','public']));
    expect(explorer.assistantContext()).toBe('d-a');expect(explorer.codingSelection()).toBeNull();
    expect(api.sqlMetadataOperation.mock.calls.some(([, , ,args])=>args.operation==='DESCRIBE')).toBe(false);
    const input=host.querySelector('[aria-label="选择数据源"]');input.focus();input.value='Other';input.dispatchEvent(new Event('input',{bubbles:true}));await tick();
    expect(host.querySelectorAll('[role=option]')).toHaveLength(1);expect(input.dataset.selectedValue).toBe('a');
    input.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));await tick();
    const schema=host.querySelector('[aria-label="选择数据库或 Schema"]');schema.focus();schema.value='private';schema.dispatchEvent(new Event('input',{bubbles:true}));await tick();
    expect(host.querySelectorAll('[role=option]')).toHaveLength(1);expect(schema.dataset.selectedValue).toBe(JSON.stringify(['catalog','public']));
  });
  it('caches an empty directory until explicit refresh or source change',async()=>{
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>result(args.operation==='LIST_NAMESPACES'?[]:[]));
    await mount({},false);await select(1,'a');
    const toggle=host.querySelector('.sql-schema-explorer>header button');toggle.click();await tick();
    const count=api.sqlMetadataOperation.mock.calls.length;toggle.click();toggle.click();await tick();
    expect(api.sqlMetadataOperation.mock.calls.length).toBe(count);
    host.querySelector('[aria-label="刷新数据库上下文"]').click();await tick();
    expect(api.sqlMetadataOperation.mock.calls.length).toBeGreaterThan(count);
  });
  it('revalidates a candidate against the persisted source and exact snapshot before adding engineering input',async()=>{
    await mount({},false);await select(1,'a');
    api.sqlMetadataContext.mockResolvedValue({bindings:[{serviceId:'registered',assetRef:'a',bindingId:'ai-binding'}]});
    const candidate={...table,discoveryId:'ai-discovery',bindingId:'ai-binding',expectedSnapshotId:'v1'};
    expect(await explorer.referenceEvidence(candidate)).toBe(true);
    expect(explorer.codingSelection()).toEqual({sources:[{discoveryId:'ai-discovery',bindingId:'ai-binding',expectedSnapshotId:'v1',tables:[{tableName:'orders',database:'catalog',schema:'public'}]}]});
    expect(api.sqlMetadataOperation).toHaveBeenCalledWith('project','ai-discovery','ai-binding',expect.objectContaining({operation:'DESCRIBE',expectedSnapshotId:'v1'}));
    expect(await explorer.referenceEvidence(candidate)).toBe(true);expect(explorer.codingSelection().sources[0].tables).toHaveLength(1);
  });
  it('rejects a candidate from another datasource before describing it',async()=>{
    await mount({},false);await select(1,'a');api.sqlMetadataOperation.mockClear();
    api.sqlMetadataContext.mockResolvedValue({bindings:[{serviceId:'registered',assetRef:'b',bindingId:'ai-binding'}]});
    expect(await explorer.referenceEvidence({...table,discoveryId:'ai',bindingId:'ai-binding',expectedSnapshotId:'v1'})).toBe(false);
    expect(explorer.codingSelection()).toBeNull();expect(api.sqlMetadataOperation).not.toHaveBeenCalled();
  });
  it('rejects changed evidence and discards a candidate response after switching source',async()=>{
    await mount({},false);await select(1,'a');
    api.sqlMetadataContext.mockResolvedValue({bindings:[{serviceId:'registered',assetRef:'a',bindingId:'ai-binding'}]});
    const candidate={...table,discoveryId:'ai',bindingId:'ai-binding',expectedSnapshotId:'old'};
    expect(await explorer.referenceEvidence(candidate)).toBe(false);expect(explorer.codingSelection()).toBeNull();
    let resolve;api.sqlMetadataContext.mockImplementation(()=>new Promise(done=>resolve=done));
    const pending=explorer.referenceEvidence({...candidate,expectedSnapshotId:'v1'});await tick();await select(1,'b');
    resolve({bindings:[{serviceId:'registered',assetRef:'a',bindingId:'ai-binding'}]});
    expect(await pending).toBe(false);expect(explorer.codingSelection()).toBeNull();
  });
  it('moves the same controls to the assistant and back without recreating its authorization context',async()=>{
    const target=document.createElement('div');document.body.append(target);
    try {
      const props=await mount({controlsTarget:target});
      await select(1,'a',target);
      const count=api.createSqlMetadataContext.mock.calls.length;
      expect(host.querySelector('[aria-label="选择数据源"]')).toBeNull();
      props.controlsTarget=null;await tick();
      expect(host.querySelector('[aria-label="选择数据源"]').dataset.selectedValue).toBe('a');
      expect(api.createSqlMetadataContext).toHaveBeenCalledTimes(count);expect(explorer.assistantContext()).toBe('d-a');
    } finally {target.remove();}
  });
  it('restores each file selection through fresh authorized directories and rejects a revoked source',async()=>{
    const props=await mount({fileId:'first',identity:{tenantId:'t',userId:'u',workspaceId:'data-science'}});
    await select(1,'a');await select(2,JSON.stringify(['catalog','public']));
    props.fileId='second';await tick();expect(explorer.assistantContext()).toBeNull();await select(1,'b');
    props.fileId='first';await tick();expect(explorer.assistantContext()).toBe('d-a');
    expect(host.querySelector('[aria-label="选择数据库或 Schema"]').dataset.selectedValue).toBe(JSON.stringify(['catalog','public']));
    props.fileId='second';await tick();expect(explorer.assistantContext()).toBe('d-b');
    api.sqlMetadataAssets.mockResolvedValue(result([{assetRef:'b',name:'Other source'}]));
    props.fileId='first';await tick();expect(explorer.assistantContext()).toBeNull();
    expect(host.querySelector('[aria-label="选择数据源"]').dataset.selectedValue).toBe('');
  });
  it('does not widen a saved namespace to the whole source when that namespace disappears',async()=>{
    const props=await mount({fileId:'first',identity:{tenantId:'t',userId:'u',workspaceId:'data-science'}});
    await select(1,'a');await select(2,JSON.stringify(['catalog','public']));props.fileId='second';await tick();
    api.sqlMetadataOperation.mockClear();
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>result(args.operation==='LIST_NAMESPACES'?[{database:'catalog',schema:'private'}]:[table]));
    props.fileId='first';await tick();
    expect(explorer.assistantContext()).toBeNull();expect(host.querySelector('[role=alert]').textContent).toContain('已不可用');
    expect(api.sqlMetadataOperation.mock.calls.some(([, , ,args])=>args.operation==='SEARCH'&&args.pageSize!==1)).toBe(false);
  });
  it('does not restore an asset reference under a different metadata service',async()=>{
    const props=await mount({fileId:'first',identity:{tenantId:'t',userId:'u',workspaceId:'data-science'}});
    await select(1,'a');props.fileId='second';await tick();
    api.sqlMetadataProviders.mockResolvedValue([{serviceId:'another-service',name:'Other service'}]);
    api.createSqlMetadataContext.mockClear();props.fileId='first';await tick();
    expect(api.createSqlMetadataContext).not.toHaveBeenCalled();expect(explorer.assistantContext()).toBeNull();
    expect(host.querySelector('[aria-label="选择数据源"]').dataset.selectedValue).toBe('');
  });
  it('lists actual authorized tables and fetches fields only on expansion',async()=>{
    await mount();await select(1,'a');
    expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(1);
    expect(api.sqlMetadataOperation.mock.calls.some(([, , ,args])=>args.operation==='DESCRIBE')).toBe(false);
    host.querySelector('[aria-label="展开 orders"]').click();await tick();
    expect(host.querySelector('[role=group]').textContent).toContain('id');
    expect(host.querySelector('.sql-schema-evidence').textContent).toContain('未进行本轮联机核验');
    expect(api.sqlMetadataOperation.mock.calls.find(([, , ,args])=>args.operation==='DESCRIBE')[3]).toMatchObject({database:'catalog',schema:'public',tableName:'orders'});
  });
  it('binds the selected namespace while keeping the asset-wide namespace directory',async()=>{
    await mount();await select(1,'a');await select(2,JSON.stringify(['catalog','public']));
    expect(api.createSqlMetadataContext.mock.calls.at(-1)[1].bindings[0]).toMatchObject({assetRef:'a',database:'catalog',schema:'public'});
    host.querySelector('[aria-label="选择数据库或 Schema"]').click();await tick();
    expect(host.querySelectorAll('[role=option]')).toHaveLength(3);
  });
  it('rejects a revoked namespace on refresh without silently switching to all authorized databases',async()=>{
    await mount();await select(1,'a');await select(2,JSON.stringify(['catalog','public']));
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>result(args.operation==='LIST_NAMESPACES'?[{database:'catalog',schema:'private'}]:[table]));
    host.querySelector('[aria-label="刷新数据库上下文"]').click();await tick();
    expect(explorer.assistantContext()).toBeNull();expect(host.querySelector('[role=alert]').textContent).toContain('已不可用');
    expect(host.querySelector('[aria-label="选择数据库或 Schema"]').disabled).toBe(true);
  });
  it('locks the shared context during an active assistant request and keeps namespaces disabled without a source',async()=>{
    const props=await mount();expect(host.querySelector('[aria-label="选择数据库或 Schema"]').disabled).toBe(true);
    await select(1,'a');props.locked=true;await tick();
    expect(host.querySelector('[aria-label="选择数据源"]').disabled).toBe(true);
    expect(host.querySelector('[aria-label="选择数据库或 Schema"]').disabled).toBe(true);
    expect(explorer.assistantContext()).toBe('d-a');
  });
  it('clears visible fields and ignores a late description when identity changes',async()=>{
    const props=await mount();await select(1,'a');let release;
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>args.operation==='DESCRIBE'?await new Promise(resolve=>{release=resolve;}):result([table]));
    host.querySelector('[aria-label="展开 orders"]').click();await tick();expect(release).toBeTypeOf('function');
    props.identity={tenantId:'t',userId:'other'};await tick();release(result([{...table,columns:[{name:'old_secret'}]}]));await tick();
    expect(host.textContent).not.toContain('old_secret');expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(0);
  });
  it('discards displayed metadata and denies insertion after revocation',async()=>{
    await mount();await select(1,'a');api.sqlMetadataOperation.mockRejectedValue(new Error('Forbidden'));
    host.querySelector('[aria-label="插入表名 orders"]').click();await tick();
    expect(host.querySelector('[role=alert]').textContent).toBe('Forbidden');expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(0);
  });
  it('retains the binding across parent renders with equivalent identity values',async()=>{
    const props=await mount();await select(1,'a');const calls=api.sqlMetadataProviders.mock.calls.length;
    props.identity={...props.identity};props.dialect='postgresql';await tick();
    expect(api.sqlMetadataProviders).toHaveBeenCalledTimes(calls);
    expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(1);
  });
  it('paginates the server results and keeps the page, fields and scroll position across focus changes',async()=>{
    const second={...table,tableName:'invoices',tableRef:'public-invoices'};
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>{
      if(args.operation==='LIST_NAMESPACES')return result([{database:'catalog',schema:'public'}]);
      if(args.operation==='DESCRIBE')return result([{...second,columns:[{name:'invoice_id',dataType:'bigint'}]}]);
      return {metadata:{...result(args.page===2?[second]:[table]).metadata,totalMatched:51,hasMore:args.page===1}};
    });
    await mount();await select(1,'a');
    const pagination=()=>host.querySelector('[aria-label="元数据分页"]');
    expect(pagination().textContent).toContain('1 / 2 页');
    expect([...pagination().querySelectorAll('button')].find(b=>b.textContent==='上一页').disabled).toBe(true);
    [...pagination().querySelectorAll('button')].find(b=>b.textContent==='下一页').click();await tick();
    expect(host.querySelector('[aria-label="展开 invoices"]')).not.toBeNull();
    expect(pagination().textContent).toContain('2 / 2 页');
    expect([...pagination().querySelectorAll('button')].find(b=>b.textContent==='下一页').disabled).toBe(true);
    host.querySelector('[aria-label="展开 invoices"]').click();await tick();
    const list=host.querySelector('[role=tree]');list.scrollTop=80;
    const contexts=api.createSqlMetadataContext.mock.calls.length;
    const pageReads=()=>api.sqlMetadataOperation.mock.calls.filter(([, , ,a])=>a.operation==='SEARCH'&&a.pageSize===50).length;
    const reads=pageReads();
    window.dispatchEvent(new Event('blur'));await tick();
    expect(host.querySelector('[role=group]').textContent).toContain('invoice_id');
    window.dispatchEvent(new Event('focus'));await tick();
    expect(pagination().textContent).toContain('2 / 2 页');
    expect(host.querySelector('[role=group]').textContent).toContain('invoice_id');expect(list.scrollTop).toBe(80);
    expect(api.createSqlMetadataContext).toHaveBeenCalledTimes(contexts);expect(pageReads()).toBe(reads);
  });
  it('clears retained rows if authorization is revoked while the window is away',async()=>{
    await mount();await select(1,'a');window.dispatchEvent(new Event('blur'));await tick();
    expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(1);
    api.sqlMetadataOperation.mockRejectedValue(new Error('Forbidden'));
    window.dispatchEvent(new Event('focus'));await tick();
    expect(host.querySelector('[role=alert]').textContent).toBe('Forbidden');
    expect(host.querySelectorAll('[role=treeitem]')).toHaveLength(0);
  });
  it('uses the submitted search when paging and returns to page one after a page-size change',async()=>{
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>args.operation==='LIST_NAMESPACES'?result([]):{metadata:{...result([table]).metadata,totalMatched:125,hasMore:true}});
    await mount();await select(1,'a');
    const input=host.querySelector('[aria-label="搜索授权表"]');
    input.value='orders';input.dispatchEvent(new Event('input',{bubbles:true}));
    host.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await tick();
    input.value='unsubmitted';input.dispatchEvent(new Event('input',{bubbles:true}));
    [...host.querySelectorAll('footer button')].find(b=>b.textContent==='下一页').click();await tick();
    const reads=()=>api.sqlMetadataOperation.mock.calls.filter(([, , ,a])=>a.operation==='SEARCH'&&a.expectedSnapshotId);
    expect(reads().at(-1)[3]).toMatchObject({query:'orders',page:2,pageSize:50});
    const size=host.querySelector('[aria-label="每页表数量"]');size.value='20';size.dispatchEvent(new Event('change',{bubbles:true}));await tick();
    expect(reads().at(-1)[3]).toMatchObject({query:'orders',page:1,pageSize:20});
    expect(host.querySelector('footer').textContent).toContain('1 / 7 页');
  });
  it('keeps explicit selections across sources and clears them on identity change',async()=>{
    const props=await mount();await select(1,'a');
    host.querySelector('[aria-label="展开 orders"]').click();await tick();
    expect(explorer.codingSelection()).toBeNull();
    host.querySelector('input[type=checkbox]').click();await tick();
    expect(explorer.codingSelection().sources[0].tables).toEqual([{tableName:'orders',database:'catalog',schema:'public'}]);
    await select(1,'b');expect(host.querySelector('input[type=checkbox]').checked).toBe(false);
    host.querySelector('input[type=checkbox]').click();await tick();
    expect(explorer.codingSelection().sources.map(item=>item.discoveryId)).toEqual(['d-a','d-b']);
    expect(host.querySelector('[aria-label="本次生成选表"]').textContent).toContain('Other source');
    await select(1,'a');expect(host.querySelector('input[type=checkbox]').checked).toBe(true);
    host.querySelector('input[type=checkbox]').click();await tick();
    expect(explorer.codingSelection().sources).toHaveLength(1);
    props.identity={tenantId:'t',userId:'another'};await tick();expect(explorer.codingSelection()).toBeNull();
  });
  it('rejects selections after grants are revoked',async()=>{
    await mount();await select(1,'a');api.sqlMetadataOperation.mockRejectedValue(new Error('Forbidden'));
    host.querySelector('input[type=checkbox]').click();await tick();
    expect(explorer.codingSelection()).toBeNull();expect(host.querySelector('[role=alert]').textContent).toBe('Forbidden');
  });

  it('retains explicit tables across pages independently of expansion',async()=>{
    api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>{
      const item={...table,tableName:args.page===2?'invoices':args.tableName||'orders',tableRef:args.page===2?'invoices':'orders'};
      if(args.operation==='LIST_NAMESPACES')return result([]);
      if(args.operation==='DESCRIBE')return result([{...item,columns:[{name:'id'}]}]);
      return {metadata:{...result([item]).metadata,totalMatched:51,hasMore:args.page===1}};
    });
    await mount();await select(1,'a');host.querySelector('input[type=checkbox]').click();await tick();
    [...host.querySelectorAll('footer button')].find(item=>item.textContent==='下一页').click();await tick();
    host.querySelector('input[type=checkbox]').click();await tick();
    expect(explorer.codingSelection().sources[0].tables.map(item=>item.tableName)).toEqual(['orders','invoices']);
  });

});

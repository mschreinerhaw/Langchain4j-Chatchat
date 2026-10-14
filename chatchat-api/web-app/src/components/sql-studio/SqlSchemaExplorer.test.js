// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {describe,it,expect,vi,beforeEach,afterEach} from 'vitest';
import SqlSchemaExplorer from './SqlSchemaExplorer.vue';
import * as api from '../../services/sqlStudioApi';
vi.mock('../../services/sqlStudioApi',()=>({sqlMetadataProviders:vi.fn(),sqlMetadataAssets:vi.fn(),createSqlMetadataContext:vi.fn(),sqlMetadataOperation:vi.fn()}));
let app,host;
const tick=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick();};
const table={database:'catalog',schema:'public',tableName:'orders',tableRef:'public-orders'};
const result=items=>({metadata:{contractVersion:'sql-studio.metadata.v2',status:'SUCCEEDED',snapshotId:'v1',doesNotExecuteSql:true,items,totalMatched:items.length,hasMore:false}});
beforeEach(()=>{
  vi.resetAllMocks();api.sqlMetadataProviders.mockResolvedValue([{serviceId:'registered',name:'Metadata'}]);
  api.sqlMetadataAssets.mockResolvedValue(result([{assetRef:'a',name:'Orders source'},{assetRef:'b',name:'Other source'}]));
  api.createSqlMetadataContext.mockImplementation(async(_p,input)=>({discoveryId:'d-'+input.bindings[0].assetRef,bindings:[{...input.bindings[0],bindingId:'binding',sourceDialect:'trino'}]}));
  api.sqlMetadataOperation.mockImplementation(async(_p,_d,_b,args)=>result(args.operation==='LIST_NAMESPACES'?[{database:'catalog',schema:'public'},{database:'catalog',schema:'private'}]:args.operation==='DESCRIBE'?[{...table,columns:[{name:'id',dataType:'bigint'}]}]:[table]));
  host=document.createElement('div');document.body.append(host);
});
afterEach(()=>{app?.unmount();host.remove();});
async function mount(){const props=reactive({projectId:'project',identity:{tenantId:'t',userId:'u'},dialect:'trino'});app=createApp({render:()=>h(SqlSchemaExplorer,props)});app.mount(host);await tick();return props;}
async function select(index,value){const input=host.querySelectorAll('select')[index];input.value=value;input.dispatchEvent(new Event('change',{bubbles:true}));await tick();}
describe('Schema Explorer API integration',()=>{
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
    expect(host.querySelectorAll('select')[2].options).toHaveLength(3);
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
});

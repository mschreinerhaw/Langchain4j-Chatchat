import {describe,it,expect,vi} from 'vitest';
import {SqlMetadataSession,StaleMetadataRequest} from './sqlMetadataSession';
const response=(snapshot='v1',items=[])=>({metadata:{contractVersion:'sql-studio.metadata.v2',status:'SUCCEEDED',snapshotId:snapshot,doesNotExecuteSql:true,items}});
const args={operation:'DESCRIBE',database:'catalog',schema:'public',tableName:'orders'};
const scope={identity:'tenant:user',projectId:'p',discoveryId:'d',bindingId:'b',assetRef:'a'};
function setup(invoke=vi.fn(async()=>response())){const invalidated=vi.fn();const session=new SqlMetadataSession(invoke,invalidated);session.bind(scope);return {session,invoke,invalidated};}
describe('metadata authorization and request isolation',()=>{
  it('requires a fresh authorization probe before each cache hit',async()=>{
    const {session,invoke}=setup();await session.read(args);await session.read(args);
    expect(invoke.mock.calls.filter(([,a])=>a.operation==='SEARCH')).toHaveLength(2);
    expect(invoke.mock.calls.filter(([,a])=>a.operation==='DESCRIBE')).toHaveLength(1);
  });
  it('deduplicates simultaneous authority and identical description requests',async()=>{
    const {session,invoke}=setup();await Promise.all([session.read(args),session.read(args),session.read(args)]);
    expect(invoke).toHaveBeenCalledTimes(2);
  });
  it('denies cached results after revocation and clears projections',async()=>{
    const {session,invoke,invalidated}=setup();await session.read(args);invoke.mockRejectedValueOnce(new Error('Forbidden'));
    await expect(session.read(args)).rejects.toThrow('Forbidden');expect(session.cache.size).toBe(0);expect(invalidated).toHaveBeenCalled();
  });
  it('rejects changed schema or field visibility instead of mixing revisions',async()=>{
    const {session,invoke}=setup();await session.read(args);invoke.mockResolvedValue(response('v2'));
    await expect(session.read(args)).rejects.toThrow();expect(session.cache.size).toBe(0);
    await session.read(args);expect(session.snapshot).toBe('v2');
  });
  it('rejects late responses after connection and identity changes',async()=>{
    let release;const invoke=vi.fn(()=>new Promise(resolve=>{release=resolve;}));const {session}=setup(invoke);
    const pending=session.read(args);session.bind({...scope,identity:'tenant:other',assetRef:'new'});release(response());
    await expect(pending).rejects.toBeInstanceOf(StaleMetadataRequest);expect(session.cache.size).toBe(0);expect(session.scope.assetRef).toBe('new');
  });
  it('keeps same-name tables in separate namespace cache entries',async()=>{
    const {session,invoke}=setup();await session.read(args);await session.read({...args,schema:'private'});
    expect(invoke.mock.calls.filter(([,a])=>a.operation==='DESCRIBE').map(([,a])=>a.schema)).toEqual(['public','private']);
  });
  it('rejects unavailable indexes without treating them as verified',async()=>{
    const {session}=setup(vi.fn(async()=>({metadata:{status:'NEEDS_REFRESH'}})));
    await expect(session.read(args)).rejects.toThrow();expect(session.cache.size).toBe(0);
  });
});

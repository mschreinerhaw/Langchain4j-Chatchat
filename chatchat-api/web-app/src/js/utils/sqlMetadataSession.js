export class StaleMetadataRequest extends Error { constructor(){super('数据源上下文已变化');} }

// Completed authority checks are never cached. Cached projections require a fresh authorized content hash.
export class SqlMetadataSession {
  constructor(invoke,onInvalidate=()=>{}) {this.invoke=invoke;this.onInvalidate=onInvalidate;this.epoch=0;this.scope=null;this.cache=new Map();this.pending=new Map();this.snapshot=null;}
  bind(scope) {this.epoch++;this.scope=scope?structuredClone(scope):null;this.cache.clear();this.pending.clear();this.snapshot=null;}
  // Cancel in-flight work without discarding the displayed snapshot. Resuming
  // requires authorize(), which still clears projections on revocation/change.
  suspend() {this.epoch++;this.cache.clear();this.pending.clear();}
  invalidate() {this.epoch++;this.cache.clear();this.pending.clear();this.snapshot=null;this.onInvalidate();}
  assert(epoch) {if(epoch!==this.epoch || !this.scope)throw new StaleMetadataRequest();}
  async raw(args,epoch) {
    this.assert(epoch);
    const response=await this.invoke(this.scope,args);
    this.assert(epoch);
    const result=response?.metadata;
    if(result?.contractVersion!=='sql-studio.metadata.v2' || result.status!=='SUCCEEDED' || !result.snapshotId || result.doesNotExecuteSql!==true)
      throw new Error(result?.status==='NEEDS_REFRESH'?'元数据快照不可用，请通过已有元数据管理刷新后重试。':'元数据结果无效');
    return result;
  }
  dedup(key,action) {
    if(this.pending.has(key))return this.pending.get(key);
    const promise=action().finally(()=>{if(this.pending.get(key)===promise)this.pending.delete(key);});
    this.pending.set(key,promise);return promise;
  }
  async authorize() {
    const epoch=this.epoch;
    try {
      const probe=await this.dedup('authority',()=>this.raw({operation:'SEARCH',page:1,pageSize:1,includeColumns:false},epoch));
      this.assert(epoch);
      if(this.snapshot && this.snapshot!==probe.snapshotId)throw new Error('元数据结构或授权范围已变化，请刷新目录');
      if(this.snapshot!==probe.snapshotId){this.cache.clear();this.snapshot=probe.snapshotId;}
      return {epoch,snapshot:probe.snapshotId};
    } catch(error){if(epoch===this.epoch)this.invalidate();throw error;}
  }
  async read(args) {
    const {epoch,snapshot}=await this.authorize();
    this.assert(epoch);
    const key=JSON.stringify([snapshot,args]);
    if(this.cache.has(key))return this.cache.get(key);
    try {
      const result=await this.dedup(key,()=>this.raw({...args,expectedSnapshotId:snapshot},epoch));
      this.assert(epoch);
      if(result.snapshotId!==snapshot)throw new Error('元数据结构已变化，请刷新目录');
      if(this.cache.size>=64)this.cache.delete(this.cache.keys().next().value);
      this.cache.set(key,result);return result;
    } catch(error){if(epoch===this.epoch)this.invalidate();throw error;}
  }
}

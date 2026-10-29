// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';
import {it,expect} from 'vitest';
import SqlBottomPanel from './SqlBottomPanel.vue';
it('separates static SQL success from business grain and relationship review',async()=>{
 const host=document.createElement('div');document.body.append(host);
 const app=createApp({render:()=>h(SqlBottomPanel,{tab:'validation',generations:[],result:{validation:'静态校验通过',metadataExploration:{codingExploration:{structureReview:{
  structureReviewRequired:true,structuralDiagnostics:[{path:'sql/query.sql',code:'JOIN_FANOUT_AGGREGATION_RISK'}],
  relationships:[{left:'a',right:'t',status:'INFERRED'}],grains:[{alias:'a',tableName:'<img src=x>',status:'UNKNOWN'}]
 }}}}})});
 app.mount(host);await nextTick();
 expect(host.textContent).toContain('汇总可能重复计数');expect(host.textContent).toContain('关联尚未验证');
 expect(host.textContent).toContain('业务粒度未确认');expect(host.textContent).toContain('数据库 SQL 未执行');
 expect(host.querySelector('img')).toBeNull();app.unmount();host.remove();
});
it('keeps per-model diagnostics separate from task elapsed time and renders previews as inert text',async()=>{
 const host=document.createElement('div');document.body.append(host);
 const app=createApp({render:()=>h(SqlBottomPanel,{tab:'diagnostics',generations:[],result:{metadataExploration:{diagnostics:{schemaVersion:'sql-studio.diagnostics.v1',elapsedMs:119800,
  toolMs:30,outputValidationMs:2,repairCount:1,modelResponses:[{modelCall:2,durationMs:1234,responseKind:'FINAL_TEXT',validationCode:'OUTPUT_JSON_TRUNCATED',responsePreview:'<script>bad()</script>'}]}}}})});
 app.mount(host);await nextTick();
 expect(host.textContent).toContain('119800 ms');expect(host.querySelector('summary').textContent).toContain('1234 ms');
 expect(host.querySelector('summary').textContent).toContain('OUTPUT_JSON_TRUNCATED');expect(host.querySelector('pre').textContent).toContain('<script>');expect(host.querySelector('script')).toBeNull();
 expect(host.textContent).toContain('SDK 未提供');app.unmount();host.remove();
});
it('shows the metadata version actually delivered to the existing engineering task',async()=>{
 const host=document.createElement('div');document.body.append(host);
 const app=createApp({render:()=>h(SqlBottomPanel,{tab:'diagnostics',generations:[],result:{generation:{prepared:{taskRequest:{toolInput:{sqlCodingMetadata:{tables:[{
   tableName:'orders',database:'db',discoveryId:'discovery-1',bindingId:'binding-1',snapshotId:'snapshot-1',tableRef:'table-1',columns:[{name:'id'}]
 }]}}}}}}})});
 app.mount(host);await nextTick();const evidence=host.querySelector('[aria-label="工程 SQL 元数据证据"]');
 expect(evidence.textContent).toContain('discovery-1');expect(evidence.textContent).toContain('snapshot-1');
 expect(evidence.textContent).toContain('可见字段 1 个');expect(host.textContent).not.toContain('此任务尚无新版');app.unmount();host.remove();
});

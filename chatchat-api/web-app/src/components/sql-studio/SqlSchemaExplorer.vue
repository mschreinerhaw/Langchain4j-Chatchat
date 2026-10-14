<template>
  <section class="sql-schema-explorer" aria-label="数据库 Schema 浏览器">
    <div class="sql-schema-controls">
    <header><strong>数据库</strong><button type="button" :disabled="busy" @click="refresh">刷新</button></header>
    <label>元数据服务<select v-model="serviceId" :disabled="busy" @change="chooseService"><option value="">选择已注册服务</option><option v-for="item in providers" :key="item.serviceId" :value="item.serviceId">{{ item.name }}</option></select></label>
    <label>数据源<select v-model="assetRef" :disabled="busy||!serviceId" @change="chooseAsset"><option value="">选择授权数据源</option><option v-for="asset in assets" :key="asset.assetRef" :value="asset.assetRef">{{ asset.displayName || asset.name }}</option></select></label>
    <button v-if="assetsMore" type="button" :disabled="busy" @click="loadAssets(true)">更多数据源</button>
    <label>Catalog / Schema<select v-model="selectedNamespace" :disabled="busy||!context" @change="chooseNamespace"><option value="">全部授权命名空间</option><option v-for="item in namespaces" :key="namespaceKey(item)" :value="namespaceKey(item)">{{ [item.database,item.schema].filter((v,i,a)=>v && a.indexOf(v)===i).join(' / ') }}</option></select></label>
    <button v-if="namespacesMore" type="button" :disabled="busy" @click="loadNamespaces(true)">更多命名空间</button>
    <small v-if="sourceDialect">数据源方言：{{ sourceDialect }}<button type="button" @click="$emit('dialect',sourceDialect)">用于编辑器</button></small>
    <form @submit.prevent="searchTables"><input v-model="query" aria-label="搜索授权表" placeholder="搜索表或字段…" :disabled="!context" /><button type="submit" :disabled="busy||!context">搜索</button></form>
    <p v-if="error" class="sql-schema-error" role="alert">{{ error }}</p>
    <p v-if="busy" role="status">正在读取授权元数据…</p>
    <p v-else-if="!providers.length">尚无可用元数据服务。</p>
    <p v-else-if="!context">选择数据源后浏览 Schema。</p>
    </div>
    <div v-if="context" ref="tableList" class="sql-schema-tables" role="tree" aria-label="授权库表" :aria-busy="busy">
      <div v-for="table in tables" :key="table.tableRef" class="sql-schema-table" role="treeitem" :aria-expanded="Boolean(expanded[table.tableRef])">
        <div><button type="button" :aria-label="'展开 '+table.tableName" @click="expand(table)">{{ expanded[table.tableRef]?'▾':'▸' }} {{ table.tableName }}</button><button type="button" :disabled="busy" :aria-label="'插入表名 '+table.tableName" @click="insert(table)">插入</button></div>
        <small>{{ [table.database,table.schema].filter((v,i,a)=>v&&a.indexOf(v)===i).join(' / ') }}<template v-if="table.comment"> · {{ table.comment }}</template></small>
        <ul v-if="expanded[table.tableRef]" role="group"><li v-for="column in fields[table.tableRef] || []" :key="column.name"><button type="button" :disabled="busy" @click="insert(table,column)">{{ column.name }}</button><small>{{ column.columnType || column.dataType }}{{ column.columnKey?' · '+column.columnKey:'' }}</small></li><li v-if="!fields[table.tableRef]?.length">未取得可见字段，不能视为结构已核验。</li></ul>
      </div>
      <p v-if="!busy&&!tables.length&&!error">当前范围没有可展示的表。</p>
    </div>
    <footer v-if="context" class="sql-schema-pagination" aria-label="元数据分页">
      <div class="sql-schema-page-summary"><span>共 {{ total }} 张表</span><label class="sql-schema-page-size">每页<select v-model.number="tablePageSize" aria-label="每页表数量" :disabled="busy" @change="loadTables()"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select>条</label></div>
      <nav aria-label="库表翻页"><button type="button" :disabled="busy||tablePage<=1" @click="loadTables(tablePage-1)">上一页</button><span aria-live="polite">{{ tablePage }} / {{ tablePages }} 页</span><button type="button" :disabled="busy||!tablesMore" @click="loadTables(tablePage+1)">下一页</button></nav>
    </footer>
    <small class="sql-schema-evidence">来源：平台注册元数据 · 未进行本轮联机核验。推断关系和业务口径不作为已确认 Schema。</small>
  </section>
</template>
<script setup>
import {ref,computed,watch,onBeforeUnmount,onActivated,onDeactivated} from 'vue';
import * as api from '../../services/sqlStudioApi';
import {SqlMetadataSession,StaleMetadataRequest} from '../../js/utils/sqlMetadataSession';
import {namespaceKey,qualifiedSqlTable,quoteSqlIdentifier,schemaSuggestions} from '../../js/editor/sqlSchemaCompletion';
const props=defineProps({projectId:String,identity:Object,dialect:{type:String,default:'spark'}});
const emit=defineEmits(['insert','dialect']);
const providers=ref([]),serviceId=ref(''),assets=ref([]),assetRef=ref(''),namespaces=ref([]),selectedNamespace=ref('');
const context=ref(null),sourceDialect=ref(''),tables=ref([]),fields=ref({}),expanded=ref({});
const busy=ref(false),error=ref(''),query=ref(''),total=ref(0),tablesMore=ref(false),tablePage=ref(1),assetsMore=ref(false),namespacesMore=ref(false);
const tablePageSize=ref(50),tableList=ref(null),submittedQuery=ref('');
const tablePages=computed(()=>Math.max(1,Math.ceil(total.value/tablePageSize.value)));
let serial=0,assetPage=1,assetSnapshot,namespacePage=1,active=true;
function clearRows(){tables.value=[];fields.value={};expanded.value={};total.value=0;tablesMore.value=false;tablePage.value=1;}
const session=new SqlMetadataSession((scope,args)=>api.sqlMetadataOperation(scope.projectId,scope.discoveryId,scope.bindingId,args),()=>{clearRows();namespaces.value=[];namespacesMore.value=false;});
const namespaceSession=new SqlMetadataSession((scope,args)=>api.sqlMetadataOperation(scope.projectId,scope.discoveryId,scope.bindingId,args),()=>{namespaces.value=[];namespacesMore.value=false;clearRows();});
function scopeOf(value){return {identity:JSON.stringify(props.identity||{}),projectId:props.projectId,discoveryId:value.discoveryId,...value.bindings[0]};}
function bind(value){context.value=value;const binding=value?.bindings?.[0];sourceDialect.value=binding?.sourceDialect||'';
  session.bind(binding?{identity:JSON.stringify(props.identity||{}),projectId:props.projectId,discoveryId:value.discoveryId,...binding}:null);clearRows();}
async function run(action){const turn=serial;error.value='';busy.value=true;try{await action(turn);}catch(ex){if(turn===serial && !(ex instanceof StaleMetadataRequest)){error.value=ex.message||'元数据读取失败';session.invalidate();}}finally{if(turn===serial)busy.value=false;}}
async function initialize(){serial++;session.bind(null);namespaceSession.bind(null);context.value=null;providers.value=[];serviceId.value='';assets.value=[];assetRef.value='';namespaces.value=[];selectedNamespace.value='';query.value='';submittedQuery.value='';clearRows();
  if(!props.projectId)return;
  await run(async turn=>{const values=await api.sqlMetadataProviders(props.projectId);if(turn!==serial)return;providers.value=values;
    if(values.length===1){serviceId.value=values[0].serviceId;await fetchAssets(turn,false);}});
}
async function fetchAssets(turn,more){const page=more?assetPage+1:1;const result=await api.sqlMetadataAssets(props.projectId,serviceId.value,page,more?assetSnapshot:undefined);if(turn!==serial)return;
  if(result.metadata?.status!=='SUCCEEDED')throw new Error('授权数据源目录不可用');
  assets.value=more?[...assets.value,...result.metadata.items]:result.metadata.items;assetPage=page;assetSnapshot=result.metadata.snapshotId;assetsMore.value=result.metadata.hasMore;
}
async function chooseService(){serial++;bind(null);namespaceSession.bind(null);assetRef.value='';assets.value=[];namespaces.value=[];selectedNamespace.value='';if(serviceId.value)await run(turn=>fetchAssets(turn,false));}
async function loadAssets(more){await run(turn=>fetchAssets(turn,more));}
async function createContext(turn,namespace){const result=await api.createSqlMetadataContext(props.projectId,{purpose:'QUERY',targetDialect:props.dialect,bindings:[{serviceId:serviceId.value,assetRef:assetRef.value,role:'SOURCE',...namespace}]});if(turn!==serial)return false;bind(result);return true;}
async function chooseAsset(){serial++;bind(null);namespaceSession.bind(null);namespaces.value=[];selectedNamespace.value='';query.value='';submittedQuery.value='';if(!assetRef.value)return;
  await run(async turn=>{if(await createContext(turn,{})){namespaceSession.bind(scopeOf(context.value));await fetchNamespaces(turn,false);await fetchTables(turn,1);}});
}
async function fetchNamespaces(turn,more){const page=more?namespacePage+1:1;const result=await namespaceSession.read({operation:'LIST_NAMESPACES',page,pageSize:50,includeColumns:false});if(turn!==serial)return;
  namespaces.value=more?[...namespaces.value,...result.items]:result.items;namespacePage=page;namespacesMore.value=result.hasMore;
}
async function loadNamespaces(more){await run(turn=>fetchNamespaces(turn,more));}
async function chooseNamespace(){const namespace=namespaces.value.find(item=>namespaceKey(item)===selectedNamespace.value)||{};
  serial++;session.bind(null);clearRows();await run(async turn=>{if(await createContext(turn,namespace))await fetchTables(turn,1);});
}
async function fetchTables(turn,page){const args={operation:'SEARCH',page,pageSize:tablePageSize.value,includeColumns:false};if(submittedQuery.value)args.query=submittedQuery.value;
  const result=await session.read(args);if(turn!==serial)return;tables.value=result.items;tablePage.value=page;tablesMore.value=result.hasMore;total.value=result.totalMatched;fields.value={};expanded.value={};
  if(tableList.value)tableList.value.scrollTop=0;
}
async function loadTables(page=1){if(context.value&&!busy.value)await run(turn=>fetchTables(turn,page));}
async function searchTables(){if(busy.value)return;submittedQuery.value=query.value.trim();await loadTables();}
async function expand(table){if(expanded.value[table.tableRef]){expanded.value={...expanded.value,[table.tableRef]:false};return;}
  await run(async turn=>{const args={operation:'DESCRIBE',tableName:table.tableName,page:1,pageSize:1};if(table.database!=null)args.database=table.database;if(table.schema!=null)args.schema=table.schema;
    const result=await session.read(args);if(turn!==serial)return;fields.value={...fields.value,[table.tableRef]:result.items[0]?.columns||[]};expanded.value={...expanded.value,[table.tableRef]:true};});
}
async function refresh(){if(context.value){session.invalidate();namespaceSession.invalidate();await run(async turn=>{await fetchNamespaces(turn,false);await fetchTables(turn,1);});}else if(serviceId.value)await loadAssets(false);else await initialize();}
async function insert(table,column){if(!active || busy.value)return;await run(async turn=>{await session.authorize();if(turn===serial && active)emit('insert',column?quoteSqlIdentifier(column.name,props.dialect):qualifiedSqlTable(table,props.dialect));});}
async function complete(source,position,dialect,cancelled){if(!context.value || !active)return [];const turn=serial;
  try{return await schemaSuggestions({source,position,dialect,session,isCancelled:()=>turn!==serial || !active || cancelled?.()});}
  catch(ex){if(turn===serial && !(ex instanceof StaleMetadataRequest))error.value=ex.message||'元数据需要重新验证';return [];}
}
function suspend(){if(!active)return;serial++;active=false;busy.value=false;session.suspend();namespaceSession.suspend();}
function resume(){if(active)return;active=true;if(context.value)void run(async()=>{await session.authorize();await namespaceSession.authorize();});}
function visibility(){if(document.hidden)suspend();else resume();}
window.addEventListener('blur',suspend);window.addEventListener('focus',resume);document.addEventListener('visibilitychange',visibility);
watch([()=>props.projectId,()=>JSON.stringify(props.identity||{})],initialize,{immediate:true});
onDeactivated(suspend);onActivated(()=>{if(!active)resume();});
onBeforeUnmount(()=>{serial++;session.bind(null);namespaceSession.bind(null);window.removeEventListener('blur',suspend);window.removeEventListener('focus',resume);document.removeEventListener('visibilitychange',visibility);});
defineExpose({complete});
</script>
<style scoped>
.sql-schema-explorer button{white-space:nowrap;flex-shrink:0}
.sql-schema-explorer{display:flex;flex-direction:column;gap:9px;padding:12px;overflow:hidden;min-height:0;min-width:0;box-sizing:border-box;color:#334764;font-size:12px}
.sql-schema-controls{display:flex;flex-direction:column;gap:9px;flex:0 1 auto;min-height:0;max-height:55%;overflow-y:auto}
.sql-schema-controls>*{flex-shrink:0}
.sql-schema-explorer header,.sql-schema-table>div{display:flex;align-items:center;justify-content:space-between;gap:5px}
.sql-schema-explorer label{display:flex;flex-direction:column;gap:4px}
.sql-schema-explorer select,.sql-schema-explorer input{min-width:0;width:100%;box-sizing:border-box;border:1px solid #dce5f2;border-radius:5px;padding:6px;background:white;color:inherit}
.sql-schema-explorer button{font-size:11px;color:#2563eb;background:#f6f9ff;border:1px solid #dce5f2;border-radius:4px;padding:4px 7px;cursor:pointer}
.sql-schema-explorer button:disabled{opacity:.5;cursor:default}
.sql-schema-explorer form{display:flex;gap:5px}
.sql-schema-tables{flex:1 1 0;min-height:0;overflow:auto;overscroll-behavior:contain}
.sql-schema-table{padding:7px 0;border-bottom:1px solid #edf1f7}
.sql-schema-table>div>button:first-child{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;border:0;background:none;flex:1}
.sql-schema-explorer small{color:#7c8ba2;font-size:11px;overflow-wrap:anywhere}
.sql-schema-table ul{list-style:none;padding:4px 0 0 12px;margin:0}
.sql-schema-table li{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:3px 0}
.sql-schema-table li button{border:0;background:none}
.sql-schema-error{color:#b44343}
.sql-schema-pagination{flex:none;display:flex;flex-direction:column;gap:8px;border-top:1px solid #e6edf7;padding-top:8px}
.sql-schema-page-summary,.sql-schema-pagination nav{display:flex;align-items:center;justify-content:space-between;gap:5px;flex-wrap:wrap}
.sql-schema-explorer .sql-schema-page-size{flex-direction:row;align-items:center;white-space:nowrap}
.sql-schema-page-size select{width:auto;padding:3px}
.sql-schema-evidence{flex:none;border-top:1px solid #e6edf7;padding-top:8px;line-height:1.6}
</style>

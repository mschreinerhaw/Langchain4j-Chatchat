const grammars={mysql:'MySQL',postgresql:'PostgreSQL',spark:'SparkSQL',hive:'HiveSQL',trino:'TrinoSQL',ansi:'GenericSQL',oracle:'GenericSQL',tsql:'GenericSQL'};
let modulePromise;
export const namespaceKey=value=>JSON.stringify([value.database??null,value.schema??null]);
export const tableKey=value=>JSON.stringify([value.database??null,value.schema??null,value.tableName]);
export function quoteSqlIdentifier(value,dialect) {
  if(dialect==='tsql')return '['+String(value).replaceAll(']',']]')+']';
  const quote=['mysql','spark','hive'].includes(dialect)?'`':'"';
  return quote+String(value).replaceAll(quote,quote+quote)+quote;
}
export function qualifiedSqlTable(table,dialect) {
  const namespace=[table.database,table.schema].filter((part,index,all)=>part!=null && part!=='' && (index===0 || part!==all[0]));
  const parts=[...namespace,table.tableName];
  return parts.map(part=>quoteSqlIdentifier(part,dialect)).join('.');
}
export async function sqlCompletionContext(source,position,dialect) {
  const name=grammars[dialect];
  if(!name || source.length>200000)return {supported:false,syntax:[],keywords:[],tables:[]};
  modulePromise ||= import('dt-sql-parser');
  const grammar=await modulePromise;const parser=new grammar[name]();
  const suggestions=parser.getSuggestionAtCaretPosition(source,position);
  const entities=parser.getAllEntities(source,position)||[];
  const tokens=parser.getAllTokens(source);
  const tables=entities.filter(entity=>['table','view'].includes(entity.entityContextType) && entity.isAccessible===true).filter(entity=>{
    // This parser version does not expose complete CTE bindings. Do not substitute a same-name physical table.
    if(identifierParts(entity.text).length>1)return true;
    const statement=entity.belongStmt?.rootStmt || entity.belongStmt;
    return !tokens.some(token=>token.channel===0 && token.text?.toUpperCase()==='WITH'
      && token.start>=(statement?.position?.startIndex??0) && token.start<=(statement?.position?.endIndex??source.length));
  });
  return {supported:true,limited:['oracle','tsql'].includes(dialect),syntax:suggestions?.syntax?.map(item=>item.syntaxContextType)||[],keywords:suggestions?.keywords||[],tables};
}
function identifierParts(reference) {
  // Splitting quoted identifiers must preserve dots inside physical names.
  return String(reference).match(/`(?:``|[^`])*`|"(?:""|[^"])*"|\[(?:\]\]|[^\]])*\]|[^.\s]+/g)?.map(value=>{
    if(value[0]==='`')return value.slice(1,-1).replaceAll('``','`');
    if(value[0]==='"')return value.slice(1,-1).replaceAll('""','"');
    if(value[0]==='[')return value.slice(1,-1).replaceAll(']]',']');return value;
  })||[];
}
export function resolveSchemaTable(reference,tables) {
  const parts=identifierParts(reference);
  const candidates=tables.filter(table=>{
    if(parts.at(-1)!==table.tableName)return false;
    if(parts.length===2)return parts[0]===(table.schema??table.database);
    if(parts.length===3)return parts[0]===table.database && parts[1]===table.schema;
    return parts.length===1;
  });
  return candidates.length===1?candidates[0]:null;
}

// Only metadata-backed candidates are returned as table/field items. A parser result is never schema evidence.
export async function schemaSuggestions({source,position,dialect,session,isCancelled=()=>false}) {
  const context=await sqlCompletionContext(source,position,dialect);
  if(isCancelled())return [];
  if(!context.supported)return [];
  const tableContext=context.syntax.some(type=>['table','view','database','catalog'].includes(type));
  const result=context.keywords.map(label=>({label,insertText:label,category:'keyword'}));
  const before=source.split('\n').slice(0,position.lineNumber).map((line,i)=>i===position.lineNumber-1?line.slice(0,position.column-1):line).join('\n');
  if(tableContext) {
    const query=/([\w$]+)$/.exec(before)?.[1];
    const args={operation:'SEARCH',page:1,pageSize:100,includeColumns:false};if(query)args.query=query;
    const catalog=await session.read(args);if(isCancelled())return [];
    const prefix=/(?:`(?:``|[^`])*`|"(?:""|[^"])*"|[\w$]+)(?:\.(?:`(?:``|[^`])*`|"(?:""|[^"])*"|[\w$]+))*\.(?:[\w$]*)$/.exec(before)?.[0];
    const replaceLength=prefix?.length;
    return [...result,...catalog.items.map(table=>({label:table.tableName,insertText:qualifiedSqlTable(table,dialect),replaceLength,category:'table',detail:[table.database,table.schema].filter(Boolean).join('.')+' · 平台注册元数据',tableRef:table.tableRef}))];
  }
  const qualifier=/((?:`[^`]+`|"[^"]+"|[\w$]+))\.([\w$]*)$/.exec(before)?.[1];
  if(qualifier)result.length=0;
  const normalize=value=>String(value||'').replace(/^[`"]|[`"]$/g,'');
  let references=context.tables;
  if(qualifier) references=references.filter(entity=>normalize(entity._alias?.text || entity.text.split('.').at(-1))===normalize(qualifier));
  const seen=new Set();
  for(const reference of references) {
    if(isCancelled())return [];
    const name=String(reference.text).match(/`(?:``|[^`])*`|"(?:""|[^"])*"|[^.\s]+/g)?.at(-1)?.replace(/^[`"]|[`"]$/g,'');
    if(!name)continue;
    const rows=[];let page=1;
    while(true){const catalog=await session.read({operation:'SEARCH',tableName:name,page,pageSize:100,includeColumns:false});rows.push(...catalog.items);
      if(isCancelled())return [];if(!catalog.hasMore)break;page++;}
    const table=resolveSchemaTable(reference.text,rows);
    const alias=reference._alias?.text;
    const referenceKey=table && JSON.stringify([tableKey(table),alias||null]);
    if(!table || seen.has(referenceKey))continue;
    seen.add(referenceKey);
    if(alias && !qualifier)result.push({label:alias,insertText:alias+'.',category:'alias',detail:'当前 SQL 别名'});
    const args={operation:'DESCRIBE',tableName:table.tableName,page:1,pageSize:1};if(table.database!=null)args.database=table.database;if(table.schema!=null)args.schema=table.schema;
    const response=await session.read(args);
    if(isCancelled())return [];
    for(const column of response.items[0]?.columns||[]) {
      const field=quoteSqlIdentifier(column.name,dialect);
      result.push({label:column.name,insertText:qualifier?field:references.length>1?quoteSqlIdentifier(normalize(alias)||table.tableName,dialect)+'.'+field:field,
        category:'field',detail:`${table.schema||table.database||''}.${table.tableName} · ${column.dataType||''} · 平台注册元数据`,tableRef:table.tableRef});
    }
  }
  return result;
}

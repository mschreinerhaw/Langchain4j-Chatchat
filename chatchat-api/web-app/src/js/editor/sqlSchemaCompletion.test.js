import {describe,it,expect,vi} from 'vitest';
import {sqlCompletionContext,resolveSchemaTable,quoteSqlIdentifier,qualifiedSqlTable,schemaSuggestions} from './sqlSchemaCompletion';
describe('Schema completion scope',()=>{
  it('uses the existing SQL grammar to find an alias at the caret',async()=>{
    const source='SELECT u. FROM public.users u';
    const context=await sqlCompletionContext(source,{lineNumber:1,column:10},'postgresql');
    expect(context.supported).toBe(true);
    expect(context.tables.some(e=>e.text==='public.users' && e._alias?.text==='u')).toBe(true);
  });
});
const users={database:'warehouse',schema:'public',tableName:'users',tableRef:'public-users'};
const other={...users,schema:'private',tableRef:'private-users'};
function sessionFor(tables=[users]) {
  return {read:vi.fn(async args=>({items:args.operation==='DESCRIBE'?[{...tables.find(t=>t.schema===args.schema),columns:[{name:args.schema==='private'?'private_id':'id',dataType:'int'}]}]:tables,hasMore:false}))};
}
describe('authorized schema proposals',()=>{
  it('resolves a Trino catalog-qualified reference',async()=>{
    const context=await sqlCompletionContext('SELECT o. FROM analytics.public.orders o',{lineNumber:1,column:10},'trino');
    expect(context.tables.length).toBeGreaterThan(0);
  });
  it.each(['postgresql','mysql','spark','hive','trino'])('uses %s grammar for caret-scoped table aliases',async dialect=>{
    const context=await sqlCompletionContext('SELECT u. FROM public.users u',{lineNumber:1,column:10},dialect);
    expect(context.supported).toBe(true);
    expect(context.tables.some(e=>e.text==='public.users' && e._alias?.text==='u')).toBe(true);
  });
  it('resolves full identities without guessing ambiguous names or merging case',()=>{
    expect(resolveSchemaTable('users',[users,other])).toBeNull();
    expect(resolveSchemaTable('public.users',[users,other])).toEqual(users);
    expect(resolveSchemaTable('warehouse.private.users',[users,other])).toEqual(other);
    expect(resolveSchemaTable('PUBLIC.users',[users])).toBeNull();
    expect(resolveSchemaTable('"sales.eu"."users"',[{...users,schema:'sales.eu'}])).toEqual({...users,schema:'sales.eu'});
  });
  it('quotes per dialect without dropping a table named like its schema',()=>{
    expect(quoteSqlIdentifier('a`b','mysql')).toBe('`a``b`');
    expect(quoteSqlIdentifier('a]b','tsql')).toBe('[a]]b]');
    expect(qualifiedSqlTable({database:'public',schema:'public',tableName:'public'},'postgresql')).toBe('"public"."public"');
    expect(qualifiedSqlTable(users,'trino')).toBe('"warehouse"."public"."users"');
  });
  it('offers table names without reading columns and replaces typed namespaces',async()=>{
    const session=sessionFor(),source='SELECT * FROM public.u';
    const result=await schemaSuggestions({source,position:{lineNumber:1,column:source.length+1},dialect:'postgresql',session});
    expect(result.find(x=>x.category==='table')).toMatchObject({label:'users',replaceLength:8});
    expect(session.read.mock.calls.every(([args])=>args.operation==='SEARCH' && args.includeColumns===false)).toBe(true);
  });
  it('describes only the qualified alias target and returns actual registered fields',async()=>{
    const session=sessionFor([users,other]);
    const result=await schemaSuggestions({source:'SELECT u. FROM public.users u',position:{lineNumber:1,column:10},dialect:'postgresql',session});
    expect(result.filter(x=>x.category==='field').map(x=>x.label)).toEqual(['id']);
    expect(session.read).toHaveBeenCalledWith(expect.objectContaining({operation:'DESCRIBE',database:'warehouse',schema:'public',tableName:'users'}));
    expect(result.find(x=>x.category==='field').detail).not.toMatch(/VERIFIED_SCHEMA/);
  });
  it('does not guess fields for ambiguous unqualified tables',async()=>{
    const session=sessionFor([users,other]);
    const result=await schemaSuggestions({source:'SELECT u. FROM users u',position:{lineNumber:1,column:10},dialect:'postgresql',session});
    expect(result.filter(x=>x.category==='field')).toEqual([]);
    expect(session.read.mock.calls.some(([args])=>args.operation==='DESCRIBE')).toBe(false);
  });
  it('keeps both aliases of a self join',async()=>{
    const source='SELECT  FROM public.users u JOIN public.users manager ON u.id=manager.id';
    const result=await schemaSuggestions({source,position:{lineNumber:1,column:8},dialect:'postgresql',session:sessionFor()});
    expect(result.filter(x=>x.category==='alias').map(x=>x.label).sort()).toEqual(['manager','u']);
  });
  it('discards cancellation before metadata access',async()=>{
    const session=sessionFor();expect(await schemaSuggestions({source:'SELECT * FROM ',position:{lineNumber:1,column:15},dialect:'mysql',session,isCancelled:()=>true})).toEqual([]);
    expect(session.read).not.toHaveBeenCalled();
  });
  it('does not treat a CTE shadowing a physical table as that table',async()=>{
    const session=sessionFor(),source='WITH users AS (SELECT 1 AS synthetic) SELECT c. FROM users c';
    const result=await schemaSuggestions({source,position:{lineNumber:1,column:source.indexOf('c.')+3},dialect:'postgresql',session});
    expect(result.filter(x=>x.category==='field')).toEqual([]);
  });
});

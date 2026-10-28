import {describe,it,expect} from 'vitest';
import {sqlMetadataProgress,sqlTaskStatus} from './sqlMetadataProgress';
const event=(id,operation,eventState,elapsedMs=0,extra={})=>({payload:JSON.stringify({payload:{metadata:{eventKind:'SQL_METADATA_EXPLORATION',operationId:id,operation,eventState,elapsedMs,...extra}}})});
describe('SQL metadata progress from backend facts',()=>{
  it('measures overall elapsed time from real predecessor events across retries',()=>{
    const previous=event('model-1','MODEL','FAILED',100);previous.createTime='2026-10-28T00:00:00Z';
    const result=sqlMetadataProgress([previous],{taskId:'retry',status:'SUCCESS',canonicalState:'SUCCEEDED',createTime:'2026-10-28T00:02:00Z',finishedAt:'2026-10-28T00:03:00Z'});
    expect(result.elapsed).toBe(180);
  });
  it('keeps operations from separate backend attempts and does not label a recovered task as failed',()=>{
    const first=event('model-1','MODEL','FAILED',100,{reasonCode:'MODEL_REQUEST_TIMEOUT'});first.attemptId='first';
    const second=event('model-1','MODEL','COMPLETED',20);second.attemptId='second';
    const result=sqlMetadataProgress([first,second],{taskId:'t',canonicalState:'SUCCEEDED',status:'SUCCESS'});
    expect(result.metadataGroups[0].count).toBe(2);expect(result.status).toBe('SUCCESS');expect(result.detail).not.toContain('超时');
  });
  it('deduplicates lifecycle updates and aggregates the same real operation',()=>{
    const result=sqlMetadataProgress([event('a','SEARCH','RUNNING'),event('a','SEARCH','COMPLETED',22),event('b','SEARCH','RUNNING',5)],{taskId:'task',canonicalState:'EXECUTING',status:'WAIT_TOOL'});
    expect(result.metadataGroups).toHaveLength(1);expect(result.metadataGroups[0]).toMatchObject({count:2,elapsedMs:27,state:'RUNNING'});expect(result.running).toBe(true);
  });
  it('does not infer task failure from a failed operation while backend retries',()=>{
    const result=sqlMetadataProgress([event('a','SEARCH','FAILED',10),event('b','SEARCH','RUNNING')],{taskId:'task',canonicalState:'REPAIRING',status:'FAILED'});
    expect(result.terminal).toBe(false);expect(result.running).toBe(true);expect(result.status).toBe('RETRYING');
  });
  it.each(['FAILED','CANCELLED','SUCCEEDED'])('stops all group spinners only on backend terminal %s',state=>{
    const result=sqlMetadataProgress([event('a','DESCRIBE','RUNNING')],{taskId:'task',canonicalState:state,status:'RUNNING'});
    expect(result.running).toBe(false);expect(result.metadataGroups[0].state).not.toBe('RUNNING');
  });
  it('keeps timeout and metadata availability causes distinct',()=>{
    const events=[event('a','MODEL','FAILED',300000,{reasonCode:'MODEL_REQUEST_TIMEOUT'})];
    expect(sqlMetadataProgress(events,{taskId:'t',status:'FAILED'}).detail).toBe('模型请求超时');
    expect(sqlMetadataProgress([event('a','SEARCH','FAILED',1,{reasonCode:'METADATA_UNAVAILABLE'})],{taskId:'t',status:'FAILED'}).detail).toContain('元数据索引不可用');
  });
  it('does not invent operation progress without observations or break existing Coding statuses',()=>{
    expect(sqlMetadataProgress([],{taskId:'t',status:'WAIT_MODEL'}).metadataGroups).toEqual([]);
    expect(sqlTaskStatus({canonicalState:'COMPLETED',status:'SUCCESS'})).toBe('SUCCESS');
    expect(sqlMetadataProgress(undefined,null).running).toBe(false);
  });
});

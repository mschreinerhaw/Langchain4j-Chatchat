import {describe,it,expect,vi,beforeEach} from 'vitest';
vi.mock('./api',()=>({apiRequest:vi.fn(),requestPythonCodeAssist:vi.fn()}));
import {apiRequest,requestPythonCodeAssist} from './api';
import {runPythonCoding,cancelPythonCoding} from './pythonCoding';
const request={action:'fix',sourceCode:'print(1)',planId:'accepted-plan',modelName:'configured'};
const context={requestId:'turn-1',entryPath:'main.py',files:[{path:'main.py',content:'print(1)'}]};
beforeEach(()=>vi.resetAllMocks());
describe('shared Python Coding task lifecycle',()=>{
  it('fails closed when deployment has not enabled Cline',async()=>{
    apiRequest.mockResolvedValue({enabled:false});
    await expect(runPythonCoding(request,context)).rejects.toThrow('Cline');
    expect(requestPythonCodeAssist).not.toHaveBeenCalled();expect(apiRequest).toHaveBeenCalledOnce();
  });
  it.each(['continue','test'])('routes %s through the same Cline workspace',async action=>{
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task'})
      .mockResolvedValueOnce({status:'COMPLETED',output:{files:[{path:'main.py',content:'print(2)'}]}});
    await runPythonCoding({...request,action},context);
    expect(apiRequest.mock.calls[1][0]).toBe('/data-science/python/assist/coding');
    expect(requestPythonCodeAssist).not.toHaveBeenCalled();
  });
  it('retains all proposed files and evidence for user review without saving or executing source',async()=>{
    const files=[{path:'main.py',content:'print(2)'},{path:'helpers.py',content:'x=1'}];
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task',plan:{planId:'accepted-plan'}})
      .mockResolvedValueOnce({status:'COMPLETED',output:{files,notes:'Review: handle empty input before dividing.',validation:{codeExecuted:false}},outcome:{evidenceBundleRef:'bundle'}});
    const onTask=vi.fn();const result=await runPythonCoding(request,{...context,onTask});
    expect(onTask).toHaveBeenCalledWith('bound-task');expect(result.files).toEqual(files);expect(result.replaceSelection).toBe(false);
    expect(result.notes).toBe('Review: handle empty input before dividing.');
    expect(result.coding.outcome.evidenceBundleRef).toBe('bundle');expect(requestPythonCodeAssist).not.toHaveBeenCalled();
    expect(apiRequest.mock.calls.map(call=>call[0])).not.toContain('/data-science/python/scripts');
    expect(JSON.parse(apiRequest.mock.calls[1][1].body).request.planId).toBe('accepted-plan');
  });
  it('never falls back to a second unbudgeted generation after an accepted Coding failure',async()=>{
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task'})
      .mockResolvedValueOnce({status:'FAILED',outcome:{summary:'Runtime failure'}});
    await expect(runPythonCoding(request,context)).rejects.toThrow('Runtime failure');expect(requestPythonCodeAssist).not.toHaveBeenCalled();
  });
  it.each(['FAILED','CANCELLED','BUDGET_EXCEEDED','PARTIAL'])('stops polling and publishes the final %s state before rejecting',async status=>{
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task'})
      .mockResolvedValueOnce({status,outcome:{summary:'Task ended'}});
    const onProgress=vi.fn();
    await expect(runPythonCoding(request,{...context,onProgress})).rejects.toThrow('Task ended');
    expect(onProgress).toHaveBeenCalledWith(expect.objectContaining({status}));
    expect(apiRequest).toHaveBeenCalledTimes(3);
    expect(apiRequest.mock.calls.some(([path])=>path.endsWith('/cancel'))).toBe(false);
  });
  it('keeps unknown execution pending and sends cancellation only to the bound task',async()=>{
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task'})
      .mockResolvedValueOnce({status:'RUNNING',outcome:{executionControl:{reconciliationRequired:true}}});
    await expect(runPythonCoding(request,context)).rejects.toThrow('reconciliation');expect(requestPythonCodeAssist).not.toHaveBeenCalled();
    await cancelPythonCoding('bound-task');expect(apiRequest).toHaveBeenLastCalledWith('/data-science/python/assist/coding/bound-task/cancel',{method:'POST',body:'{}'});
  });
});

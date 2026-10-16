import {describe,it,expect,vi,beforeEach} from 'vitest';
vi.mock('./api',()=>({apiRequest:vi.fn(),requestPythonCodeAssist:vi.fn()}));
import {apiRequest,requestPythonCodeAssist} from './api';
import {runPythonCoding,cancelPythonCoding} from './pythonCoding';
const request={action:'fix',sourceCode:'print(1)',planId:'accepted-plan',modelName:'configured'};
const context={requestId:'turn-1',entryPath:'main.py',files:[{path:'main.py',content:'print(1)'}]};
beforeEach(()=>vi.resetAllMocks());
describe('shared Python Coding task lifecycle',()=>{
  it('uses the existing assistant when deployment has not enabled the profile',async()=>{
    apiRequest.mockResolvedValue({enabled:false});requestPythonCodeAssist.mockResolvedValue({code:'legacy'});
    expect(await runPythonCoding(request,context)).toEqual({code:'legacy'});
    expect(requestPythonCodeAssist).toHaveBeenCalledOnce();expect(apiRequest).toHaveBeenCalledOnce();
  });
  it('retains all proposed files and evidence for user review without saving or executing source',async()=>{
    const files=[{path:'main.py',content:'print(2)'},{path:'helpers.py',content:'x=1'}];
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task',plan:{planId:'accepted-plan'}})
      .mockResolvedValueOnce({status:'COMPLETED',output:{files,validation:{codeExecuted:false}},outcome:{evidenceBundleRef:'bundle'}});
    const onTask=vi.fn();const result=await runPythonCoding(request,{...context,onTask});
    expect(onTask).toHaveBeenCalledWith('bound-task');expect(result.files).toEqual(files);expect(result.replaceSelection).toBe(false);
    expect(result.coding.outcome.evidenceBundleRef).toBe('bundle');expect(requestPythonCodeAssist).not.toHaveBeenCalled();
    expect(apiRequest.mock.calls.map(call=>call[0])).not.toContain('/data-science/python/scripts');
    expect(JSON.parse(apiRequest.mock.calls[1][1].body).request.planId).toBe('accepted-plan');
  });
  it('never falls back to a second unbudgeted generation after an accepted Coding failure',async()=>{
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task'})
      .mockResolvedValueOnce({status:'FAILED',outcome:{summary:'Runtime failure'}});
    await expect(runPythonCoding(request,context)).rejects.toThrow('Runtime failure');expect(requestPythonCodeAssist).not.toHaveBeenCalled();
  });
  it('keeps unknown execution pending and sends cancellation only to the bound task',async()=>{
    apiRequest.mockResolvedValueOnce({enabled:true}).mockResolvedValueOnce({taskId:'bound-task'})
      .mockResolvedValueOnce({status:'RUNNING',outcome:{executionControl:{reconciliationRequired:true}}});
    await expect(runPythonCoding(request,context)).rejects.toThrow('reconciliation');expect(requestPythonCodeAssist).not.toHaveBeenCalled();
    await cancelPythonCoding('bound-task');expect(apiRequest).toHaveBeenLastCalledWith('/data-science/python/assist/coding/bound-task/cancel',{method:'POST',body:'{}'});
  });
});

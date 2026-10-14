import {describe,it,expect,vi,beforeEach} from 'vitest';
vi.mock('./api',()=>({requestPythonCodePlan:vi.fn(),requestPythonCodeAssist:vi.fn()}));
import {requestPythonCodePlan,requestPythonCodeAssist} from './api';
import {requestPlannedPythonAssist} from './studioCodePlanning';

const ticket={planId:'server-plan',status:'READY',interpretationPlan:{plan:{steps:[{id:1,input:{description:'检查选中函数'}}]}}};
beforeEach(()=>vi.resetAllMocks());
describe('Studio plan before exploration',()=>{
  it('waits for the server plan and displays it before submitting analysis',async()=>{
    let accept;
    requestPythonCodePlan.mockImplementation(()=>new Promise(resolve=>{accept=resolve;}));
    requestPythonCodeAssist.mockResolvedValue({code:'explained'});
    const display=vi.fn();
    const request={sourceCode:'original',skillIds:['approved']};
    const result=requestPlannedPythonAssist(request,display);
    request.sourceCode='changed';request.skillIds.push('later');
    expect(requestPythonCodeAssist).not.toHaveBeenCalled();expect(display).not.toHaveBeenCalled();
    accept(ticket);await result;
    expect(display).toHaveBeenCalledWith(ticket);
    expect(requestPythonCodeAssist).toHaveBeenCalledWith({sourceCode:'original',skillIds:['approved'],planId:'server-plan'});
    expect(display.mock.invocationCallOrder[0]).toBeLessThan(requestPythonCodeAssist.mock.invocationCallOrder[0]);
  });
  it('stops after plan failure or a malformed plan without starting generation',async()=>{
    requestPythonCodePlan.mockRejectedValueOnce(new Error('PLAN_FAILED'));
    await expect(requestPlannedPythonAssist({})).rejects.toThrow('PLAN_FAILED');
    requestPythonCodePlan.mockResolvedValueOnce({planId:'unstructured'});
    await expect(requestPlannedPythonAssist({})).rejects.toThrow('未收到有效代码计划');
    expect(requestPythonCodeAssist).not.toHaveBeenCalled();
  });
  it('requests a fresh plan for each new turn',async()=>{
    requestPythonCodePlan.mockResolvedValue(ticket);requestPythonCodeAssist.mockResolvedValue({});
    await requestPlannedPythonAssist({prompt:'first'});await requestPlannedPythonAssist({prompt:'follow-up'});
    expect(requestPythonCodePlan).toHaveBeenCalledTimes(2);
  });
});

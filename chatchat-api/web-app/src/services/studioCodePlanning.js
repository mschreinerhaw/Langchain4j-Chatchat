import { requestPythonCodePlan, requestPythonCodeAssist } from './api';

// Await the accepted server plan and publish it before requesting any analysis/code result.
export async function requestPlannedPythonAssist(payload, onPlan, run) {
  const request = {...payload,skillIds:[...(payload.skillIds || [])]};
  const plan = await requestPythonCodePlan(request);
  if (plan?.status!=='READY' || !plan?.planId || !plan.interpretationPlan?.plan?.steps?.length) throw new Error('未收到有效代码计划，尚未开始探索');
  await onPlan?.(plan);
  return (run || requestPythonCodeAssist)({...request,planId:plan.planId});
}

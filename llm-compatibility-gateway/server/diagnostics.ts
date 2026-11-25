import { isObject, type JsonObject } from '../protocol/rules.js';
import { GatewayError, type Model, type Service } from './config.js';
import { proxy, translate, upstreamModels } from './proxy.js';

const tool = (name: string) => ({
  type: 'function', function: { name, description: 'Return a diagnostic marker', parameters: { type: 'object',
    properties: { value: { type: 'string' } }, required: ['value'], additionalProperties: false } },
});
const forced = { type: 'function', function: { name: 'gateway_echo' } };
const prompt = (content: string) => [{ role: 'user', content }];
const jsonSchema = { type: 'object', properties: { result: { type: 'string', enum: ['OK'] } }, required: ['result'], additionalProperties: false };
const text = (data?: JsonObject) => {
  const choice = Array.isArray(data?.choices) ? data.choices[0] : undefined;
  return isObject(choice?.message) && typeof choice.message.content === 'string' ? choice.message.content : '';
};
const matchesJson = (data?: JsonObject) => {
  try { const parsed = JSON.parse(text(data)); return isObject(parsed) && parsed.result === 'OK'; }
  catch { return false; }
};
type Verdict = 'PASS' | 'FAIL' | 'UNSUPPORTED' | 'UNVERIFIED' | 'WARNING';
type Finding = { test: string; status: Verdict; reason: string; httpStatus?: number | null;
  suggestion?: string; clineImpact?: string; evidence?: unknown[] };
const firstChoice = (data?: JsonObject) => Array.isArray(data?.choices) ? data.choices[0] : undefined;
const message = (data?: JsonObject) => firstChoice(data)?.message;
const authStatuses = [401, 403];

export async function diagnose(model: Model, service: Service, signal: AbortSignal, secrets: string[], emit: (finding: Finding) => Promise<void>) {
  const results: Finding[] = [];
  const add = async (finding: Finding) => {
    const { evidence: _evidence, ...summary } = finding;
    results.push(summary);
    await emit(finding);
  };
  let listStatus: number | null = null;
  let basicStatus: number | null = null;
  try {
    const list = await upstreamModels(service, signal);
    listStatus = list.status;
    const valid = isObject(list.data) && Array.isArray(list.data.data);
    await add({ test: '模型列表接口', status: list.status === 404 || list.status === 405 ? 'UNSUPPORTED'
      : list.status >= 200 && list.status < 300 && valid ? 'PASS' : 'FAIL', httpStatus: list.status,
      reason: '检查上游 HTTP 状态及列表结构', suggestion: '缺少列表接口时可在模型映射中手动录入真实 ID' });
  } catch (error) {
    await add({ test: '模型列表接口', status: 'FAIL', reason: translate(error).message });
  }
  const run = async (test: string, request: JsonObject, validate: (data?: JsonObject, events?: JsonObject[]) => boolean,
    suggestion?: string, clineImpact?: string, expectInvalidRequest = false) => {
    if (signal.aborted) throw signal.reason;
    const evidence: unknown[] = [];
    const events: JsonObject[] = [];
    let eventSize = 0;
    const metrics: JsonObject = {};
    try {
      const result = await proxy({ body: { model: model.alias, messages: prompt('Reply with OK.'), ...request },
        model, service, signal, metrics, secrets,
        trace: async (stage, value) => {
          if (evidence.length < 30) evidence.push({ stage, value: JSON.stringify(value)?.slice(0, 8_000) });
        },
        writer: { start() {}, async write(event) {
          const line = event.split('\n').find(value => value.startsWith('data: '));
          if (line && line !== 'data: [DONE]' && eventSize + line.length <= 512_000 && events.length < 4096) {
            eventSize += line.length;
            events.push(JSON.parse(line.slice(6)));
          }
        } },
      });
      const passed = validate(result.data, events);
      await add({ test, status: passed ? 'PASS' : 'WARNING', reason: passed ? '实测满足检查条件' : '收到响应，未观察到预期行为',
        httpStatus: result.status, suggestion, clineImpact, evidence });
      return result.data;
    } catch (error) {
      if (signal.aborted) throw error;
      const failure = translate(error);
      const rejected = expectInvalidRequest && [400, 422].includes(Number(metrics.upstreamStatus))
        && failure.code === 'upstream_http_error';
      await add({ test, status: rejected ? 'PASS' : failure.code === 'tools_unsupported' ? 'UNSUPPORTED' : 'FAIL',
        reason: rejected ? '上游返回预期 4xx，网关生成标准错误对象' : failure.message,
        httpStatus: typeof metrics.upstreamStatus === 'number' ? metrics.upstreamStatus : null,
        suggestion, clineImpact, evidence });
    } finally {
      if (test === '普通 Chat Completions') basicStatus = typeof metrics.upstreamStatus === 'number' ? metrics.upstreamStatus : null;
    }
  };
  const basic = await run('普通 Chat Completions', {}, data => Boolean(data), '核对 Chat 路径、认证与真实模型 ID', '影响基础对话');
  const network = basicStatus ?? listStatus;
  await add({ test: '网络连通性', status: network === null ? 'FAIL' : 'PASS', reason: network === null ? '未收到上游 HTTP 响应' : '收到上游 HTTP 响应', httpStatus: network });
  const authStatus = basicStatus ?? (listStatus !== 404 && listStatus !== 405 ? listStatus : null);
  await add({ test: '认证', status: authStatus === null ? 'UNVERIFIED' : authStatuses.includes(authStatus) ? 'FAIL'
    : basic && service.apiKey ? 'PASS' : 'UNVERIFIED', reason: '依据配置凭据及真实 HTTP 响应判断', httpStatus: authStatus });
  await add({ test: '模型 ID', status: basic ? 'PASS' : 'UNVERIFIED', reason: basic ? '上游接受此真实 ID 并返回对话' : '普通对话未成功' });
  if (basic) {
    await run('SSE 流式协议', { stream: true }, (_data, events) => Boolean(events?.length), '核对 SSE Content-Type、Event 边界和结束策略', '影响流式任务');
    await run('中文 UTF-8', { messages: prompt('仅回复“网关正常”。') }, data => text(data).includes('网关正常'));
    await run('System Message', { messages: [{ role: 'system', content: 'Always reply with gateway-system-263.' }, ...prompt('What should you say?')] },
      data => text(data).includes('gateway-system-263'));
    await run('多轮对话', { messages: [...prompt('记住编号 gateway-381'), { role: 'assistant', content: '好的' }, ...prompt('之前的编号是什么？')] },
      data => text(data).includes('gateway-381'));
    const toolRequest = { messages: prompt('Call gateway_echo with value gateway-check.'), tools: [tool('gateway_echo')], tool_choice: forced };
    const toolResult = await run('Native Tool Calling', toolRequest, data => firstChoice(data)?.finish_reason === 'tool_calls'
      && Boolean(message(data)?.tool_calls?.some((call: JsonObject) => {
        if (!isObject(call.function) || call.function.name !== 'gateway_echo' || typeof call.id !== 'string') return false;
        try { return JSON.parse(String(call.function.arguments)).value === 'gateway-check'; } catch { return false; }
      })), '核对上游 Tool Calling 模板和 tool_choice 支持', '影响 Cline 原生工具调用');
    await run('流式 Tool Calling', { ...toolRequest, stream: true }, (_data, events) => {
      const calls = new Map<number, { name: string; args: string; id: string }>();
      let finished = false;
      for (const event of events ?? []) for (const choice of Array.isArray(event.choices) ? event.choices : []) {
        if (choice.finish_reason === 'tool_calls') finished = true;
        for (const call of choice.delta?.tool_calls ?? []) {
          if (!Number.isInteger(call.index)) continue;
          const previous = calls.get(call.index) ?? { name: '', args: '', id: '' };
          previous.name += call.function?.name ?? '';
          previous.args += call.function?.arguments ?? '';
          previous.id += call.id ?? '';
          calls.set(call.index, previous);
        }
      }
      return finished && [...calls.values()].some(call => {
        try { return Boolean(call.id) && call.name === 'gateway_echo' && JSON.parse(call.args).value === 'gateway-check'; }
        catch { return false; }
      });
    }, '核对 delta.tool_calls、index、arguments 分片', '影响 Cline 流式工具调用');
    await run('多工具调用', { messages: prompt('Call two tools gateway_echo and gateway_check in the same response.'),
      tools: [tool('gateway_echo'), tool('gateway_check')], parallel_tool_calls: true }, data => {
      const names = new Set(message(data)?.tool_calls?.map((call: { function?: { name?: string } }) => call.function?.name));
      return names.has('gateway_echo') && names.has('gateway_check');
    });
    const assistant = message(toolResult);
    const calls = assistant?.tool_calls;
    if (Array.isArray(calls) && calls.length && calls.every(call => typeof call.id === 'string' && call.function?.name === 'gateway_echo')) {
      await run('Tool Result 回传', { messages: [...toolRequest.messages, assistant,
        ...calls.map(call => ({ role: 'tool', tool_call_id: call.id, content: 'gateway-probe-4917' })),
        ...prompt('Reply exactly with the value returned by the tool.')], tools: toolRequest.tools }, data => text(data).includes('gateway-probe-4917'),
      undefined, '影响后续工具结果回传');
    } else await add({ test: 'Tool Result 回传', status: 'UNVERIFIED', reason: '未获得可用的上游工具调用 ID' });
    await run('Reasoning Content', { messages: prompt('Explain why 17 is prime.') }, data => typeof message(data)?.reasoning_content === 'string'
      && message(data).reasoning_content.length > 0);
    await run('JSON Mode', { messages: prompt('Reply with JSON object {"result":"OK"}.'), response_format: { type: 'json_object' } }, matchesJson);
    await run('Structured Output', { messages: prompt('Reply with JSON object with a result field.'),
      response_format: { type: 'json_schema', json_schema: { name: 'gateway_probe', strict: true, schema: jsonSchema } } }, matchesJson);
    await run('最大输出 Token 参数', { messages: prompt('List twenty different animals.'), max_completion_tokens: 1 },
      data => firstChoice(data)?.finish_reason === 'length', '若上游明确只接受 max_tokens，可预览重命名规则');
    await add({ test: 'Token Usage', status: isObject(basic.usage) ? 'PASS' : 'WARNING', reason: isObject(basic.usage) ? '上游提供 usage' : '上游未提供 usage' });
    await add({ test: 'Finish Reason', status: typeof firstChoice(basic)?.finish_reason === 'string' ? 'PASS' : 'WARNING', reason: '检查普通响应中的 finish_reason' });
    await run('异常响应结构', { messages: [{}] }, () => false, '检查上游对无效 messages 的 HTTP 状态及错误体', undefined, true);
    const cancel = new AbortController();
    let requested = false;
    let completed = false;
    try {
      await proxy({ model, service, body: { model: model.alias, messages: prompt('List twenty different animals.'), stream: true },
        signal: AbortSignal.any([signal, cancel.signal]), metrics: {}, writer: { start() {}, async write(value) {
          if (value.includes('[DONE]')) completed = true;
          if (value.startsWith('data: ') && !completed) {
            requested = true;
            cancel.abort();
            throw cancel.signal.reason;
          }
        } } });
    } catch (error) {
      if (signal.aborted) throw error;
    }
    await add({ test: '请求取消', status: requested ? 'PASS' : 'UNVERIFIED', reason: requested
      ? '在首个数据事件后触发网关上游取消；上游服务端回收需现场监控' : '取消前未收到可用数据事件' });
  } else {
    for (const test of ['SSE 流式协议', '中文 UTF-8', 'System Message', '多轮对话', 'Native Tool Calling', '流式 Tool Calling', '多工具调用',
      'Tool Result 回传', 'Reasoning Content', 'JSON Mode', 'Structured Output', '最大输出 Token 参数', 'Token Usage', 'Finish Reason', '异常响应结构', '请求取消']) {
      await add({ test, status: 'UNVERIFIED', reason: '普通 Chat Completions 未通过' });
    }
  }
  await add({ test: 'Cline 完整任务', status: 'UNVERIFIED', reason: '需由实际 Cline 客户端完成任务并核对产物与工具执行' });
  return results;
}

import { Ajv, type ValidateFunction } from 'ajv';
import { Ajv2019 } from 'ajv/dist/2019.js';
import { Ajv2020 } from 'ajv/dist/2020.js';
import { randomUUID } from 'node:crypto';
import { GatewayError } from '../server/config.js';
import { isObject, type JsonObject } from './rules.js';
import { protocolError } from './sse.js';

const toolName = /^[A-Za-z0-9_-]{1,64}$/;
const legacyLimit = 65_536;
const requestError = (message: string) => new GatewayError(422, 'unsupported_tool_protocol', message);
const schemaOptions = { strict:false, addUsedSchema:false, validateFormats:false };
const schemaEngine = new Ajv(schemaOptions);
const schema2019 = new Ajv2019(schemaOptions);
const schema2020 = new Ajv2020(schemaOptions);

export function legacyRequest(body: JsonObject): JsonObject {
  const requested = Array.isArray(body.tools) ? body.tools : [];
  if (Object.hasOwn(body, 'functions') || Object.hasOwn(body, 'function_call'))
    throw requestError('网关的旧版工具适配需要标准 tools 和 tool_choice 请求');
  if (requested.length && body.parallel_tool_calls === true) throw requestError('旧版 function_call 无法表达并行工具调用');
  const names = new Set<string>();
  const functions = requested.map(tool => {
    const declaration = tool?.function;
    if (tool?.type !== 'function' || !isObject(declaration) || typeof declaration.name !== 'string'
        || !toolName.test(declaration.name) || names.has(declaration.name)) throw requestError('旧版工具协议需要唯一、合法的函数名');
    if (declaration.parameters !== undefined && !isObject(declaration.parameters)) throw requestError('函数参数必须是 JSON Schema 对象');
    names.add(declaration.name);
    const { strict: _strict, ...legacy } = declaration;
    return legacy;
  });
  let selection = body.tool_choice;
  if (selection === 'required') {
    if (names.size !== 1) throw requestError('旧版协议无法表示从多个工具中强制选择一个');
    selection = { name: functions[0].name };
  } else if (isObject(selection)) {
    const selected = selection.function;
    if (selection.type !== 'function' || !isObject(selected) || typeof selected.name !== 'string' || !names.has(selected.name))
      throw requestError('指定的工具不在当前请求的工具集合内');
    selection = { name: selected.name };
  } else if (selection !== undefined && selection !== 'auto' && selection !== 'none') {
    throw requestError('无法转换 tool_choice');
  }
  const pending = new Map<string, string>();
  const usedIds = new Set<string>();
  const messages = (body.messages as JsonObject[]).map(message => {
    if (!isObject(message) || message.role === 'function' || Object.hasOwn(message, 'function_call'))
      throw requestError('工具历史必须使用标准角色和工具调用字段');
    if (message.role === 'tool') {
      const id = message.tool_call_id;
      const name = typeof id === 'string' ? pending.get(id) : undefined;
      if (!name || typeof message.content !== 'string') throw requestError('工具结果缺少匹配的调用 ID 或非文本结果无法转换');
      pending.delete(id as string);
      return { role:'function', name, content:message.content };
    }
    if (pending.size) throw requestError('工具调用结果必须紧跟对应的 assistant 调用');
    if (message.tool_calls === undefined) return message;
    const { tool_calls: calls, ...rest } = message;
    if (message.role === 'assistant' && Array.isArray(calls) && !calls.length) return rest;
    if (message.role !== 'assistant' || !Array.isArray(message.tool_calls) || message.tool_calls.length !== 1)
      throw requestError('旧版协议每条 assistant 消息只允许一个工具调用');
    const [call] = message.tool_calls;
    if (call?.type !== 'function' || typeof call.id !== 'string' || !call.id || usedIds.has(call.id)
        || typeof call.function?.name !== 'string' || !toolName.test(call.function.name) || typeof call.function?.arguments !== 'string')
      throw requestError('工具历史中的调用不完整或 ID 重复');
    usedIds.add(call.id);
    pending.set(call.id, call.function.name);
    return { ...rest, function_call:{ name:call.function.name, arguments:call.function.arguments } };
  });
  if (pending.size) throw requestError('请求缺少历史工具调用的结果');
  const { tools: _tools, tool_choice: _choice, parallel_tool_calls: _parallel, ...rest } = body;
  return { ...rest, messages, ...(functions.length ? { functions } : {}), ...(selection !== undefined ? { function_call:selection } : {}) };
}

export class LegacyToolResponse {
  private readonly id = randomUUID().replaceAll('-', '');
  private readonly validators = new Map<string, ValidateFunction>();
  private readonly arguments = new Map<number, { name: string; text: string }>();

  constructor(request: JsonObject) {
    if (!Array.isArray(request.functions) || request.function_call === 'none') return;
    const selected = isObject(request.function_call) ? request.function_call.name : undefined;
    for (const entry of request.functions as JsonObject[]) {
      if (selected !== undefined && entry.name !== selected) continue;
      const schema = (entry.parameters ?? { type:'object' }) as JsonObject;
      const dialect = typeof schema.$schema === 'string' ? schema.$schema.replace(/#$/, '') : undefined;
      const engine = dialect === 'https://json-schema.org/draft/2020-12/schema' ? schema2020
        : dialect === 'https://json-schema.org/draft/2019-09/schema' ? schema2019 : schemaEngine;
      try {
        if (Buffer.byteLength(JSON.stringify(schema)) > legacyLimit) throw requestError('工具 JSON Schema 超过适配上限');
        this.validators.set(String(entry.name), engine.compile(schema));
      } catch {
        throw requestError('无法校验工具 JSON Schema');
      } finally { engine.removeSchema(schema); }
    }
  }

  convert(choice: JsonObject, message: JsonObject) {
    const index = choice.index;
    if (!Number.isSafeInteger(index) || (index as number) < 0) throw protocolError('上游旧版工具响应缺少合法 choice.index');
    const id = index as number;
    if (message.tool_calls !== undefined) throw protocolError('上游同时使用了新版工具协议，请核对网关适配配置');
    const fragment = message.function_call;
    if (fragment != null) {
      if (!isObject(fragment) || (fragment.name !== undefined && typeof fragment.name !== 'string')
          || (fragment.arguments !== undefined && typeof fragment.arguments !== 'string'))
        throw protocolError('上游 function_call 分片格式不正确');
      const existing = this.arguments.get(id);
      if (!existing && this.arguments.size >= 16) throw protocolError('上游同时返回过多工具调用');
      const state = existing ?? { name:'', text:'' };
      state.name += fragment.name ?? '';
      state.text += fragment.arguments ?? '';
      if (state.name.length > 64 || Buffer.byteLength(state.text) > legacyLimit) throw protocolError('上游工具调用参数过长');
      this.arguments.set(id, state);
    }
    delete message.function_call;
    const call = this.arguments.get(id);
    if (choice.finish_reason !== 'function_call') {
      if (choice.finish_reason && call) throw protocolError('上游发送了未完成的工具调用');
      return;
    }
    if (!call) throw protocolError('上游报告工具调用结束但缺少调用内容');
    const validate = this.validators.get(call.name);
    if (!validate) throw protocolError('上游调用了当前请求未允许的函数');
    let args: unknown;
    try { args = JSON.parse(call.text); }
    catch { throw protocolError('上游工具参数不是完整 JSON'); }
    let valid = false;
    try { valid = isObject(args) && validate(args); }
    catch { /* Validator failure is also a protocol error. */ }
    if (!valid) throw protocolError('上游工具参数不符合 JSON Schema');
    this.arguments.delete(id);
    const normalized = { id:`call_${this.id}_${id}`, type:'function', function:{ name:call.name, arguments:call.text } };
    message.tool_calls = [Object.hasOwn(choice, 'delta') ? { index:0, ...normalized } : normalized];
    choice.finish_reason = 'tool_calls';
  }

  assertFinished() {
    if (this.arguments.size) throw protocolError('上游在工具调用结束前断开');
  }
}

import { GatewayError } from '../server/config.js';
import type { Protocol } from './compat-schema.js';
import { LegacyToolResponse, legacyRequest } from './legacy-tools.js';
import { isObject, type JsonObject } from './rules.js';
import { protocolError } from './sse.js';

export function protocolRequest(body: JsonObject, protocol: Protocol): JsonObject {
  let result = protocol.tools === 'legacy-functions' ? legacyRequest(body) : body;
  if (protocol.images !== 'flat-image-url') return result;
  const messages = (result.messages as JsonObject[]).map(message => {
    if (!Array.isArray(message?.content)) return message;
    return { ...message, content: message.content.map(part => {
      if (part?.type !== 'image_url') return part;
      if (!isObject(part.image_url) || typeof part.image_url.url !== 'string'
          || !/^(https?:\/\/|data:image\/[-+\w.]+;base64,)/i.test(part.image_url.url)
          || Object.keys(part.image_url).some(key => key !== 'url') || Object.hasOwn(part, 'detail'))
        throw new GatewayError(422, 'unsupported_image_protocol', '图片地址、格式或 detail 无法保真转换');
      return { ...part, image_url:part.image_url.url };
    }) };
  });
  result = { ...result, messages };
  return result;
}

const opening = '<think>';
const closing = '</think>';
type Think = { mode: 'prefix' | 'thinking' | 'text'; pending: string };
function thinkDelta(state: Think, fragment: string, ending: boolean) {
  let input = state.pending + fragment;
  state.pending = '';
  if (state.mode === 'prefix') {
    if (!ending && opening.startsWith(input) && input !== opening) {
      state.pending = input;
      return { content:'', reasoning:'' };
    }
    state.mode = input.startsWith(opening) ? 'thinking' : 'text';
    if (state.mode === 'thinking') input = input.slice(opening.length);
  }
  if (state.mode === 'text') return { content:input, reasoning:'' };
  const close = input.indexOf(closing);
  if (close >= 0) {
    state.mode = 'text';
    return { content:input.slice(close + closing.length), reasoning:input.slice(0, close) };
  }
  if (ending) throw protocolError('上游在推理标签闭合前结束');
  for (let length = Math.min(input.length, closing.length - 1); length > 0; length--) {
    if (input.endsWith(closing.slice(0, length))) {
      state.pending = input.slice(-length);
      input = input.slice(0, -length);
      break;
    }
  }
  return { content:'', reasoning:input };
}

function readPath(input: JsonObject, path: string) {
  let value: unknown = input;
  for (const segment of path.split('.')) {
    if (!isObject(value) || !Object.hasOwn(value, segment)) return;
    value = value[segment];
  }
  return value;
}

function mapUsage(data: JsonObject, mapping: NonNullable<Protocol['usage']>) {
  const usage = isObject(data.usage) ? { ...data.usage } : {};
  let mapped = false;
  for (const [field, path] of Object.entries(mapping)) {
    if (!path) continue;
    const count = readPath(data, path);
    if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0) continue;
    if (field === 'cached_tokens' || field === 'reasoning_tokens') {
      const key = field === 'cached_tokens' ? 'prompt_tokens_details' : 'completion_tokens_details';
      const details = isObject(usage[key]) ? usage[key] : {};
      usage[key] = { ...details, [field]:details[field] ?? count };
    } else usage[field] ??= count;
    mapped = true;
  }
  if (mapped) data.usage = usage;
  return mapped;
}

export class ResponseProtocol {
  private readonly legacy?: LegacyToolResponse;
  private readonly thinking = new Map<number, Think>();

  constructor(private readonly config: Protocol, payload: JsonObject) {
    if (config.tools === 'legacy-functions') this.legacy = new LegacyToolResponse(payload);
  }

  normalize(data: JsonObject, stream: boolean, eventName?: string) {
    const mapped = this.config.usage ? mapUsage(data, this.config.usage) : false;
    if (eventName && eventName === this.config.usageEvent && !Array.isArray(data.choices)) {
      if (!mapped && !isObject(data.usage)) throw protocolError('上游用量事件没有可确认的 usage 字段');
      data.choices = [];
    }
    if (!Array.isArray(data.choices)) return data;
    for (const choice of data.choices) {
      if (!isObject(choice)) continue;
      const item = choice[stream ? 'delta' : 'message'];
      if (!isObject(item)) continue;
      this.legacy?.convert(choice, item);
      this.normalizeReasoning(choice, item, stream);
    }
    return data;
  }

  private normalizeReasoning(choice: JsonObject, item: JsonObject, stream: boolean) {
    if (this.config.reasoning === 'reasoning-field' || this.config.reasoning === 'thinking-field') {
      const field = this.config.reasoning === 'reasoning-field' ? 'reasoning' : 'thinking';
      if (item[field] == null) return;
      if (typeof item[field] !== 'string' || (item.reasoning_content != null && item.reasoning_content !== item[field]))
        throw protocolError('上游推理字段格式无效或内容冲突');
      item.reasoning_content = item[field];
      delete item[field];
    } else if (this.config.reasoning === 'think-tags') {
      const index = choice.index;
      if (typeof index !== 'number' || !Number.isSafeInteger(index) || index < 0) throw protocolError('推理分片缺少合法 choice.index');
      const existing = this.thinking.get(index);
      if (!existing && item.content == null && !choice.finish_reason) return;
      if (!existing && this.thinking.size >= 16) throw protocolError('上游同时返回过多推理序列');
      const state: Think = existing ?? { mode:'prefix', pending:'' };
      if (item.content != null && typeof item.content !== 'string') throw protocolError('上游推理内容必须是文本');
      const ending = !stream || Boolean(choice.finish_reason);
      const delta = thinkDelta(state, (item.content as string | null | undefined) ?? '', ending);
      if (delta.reasoning) {
        if (item.reasoning_content != null && item.reasoning_content !== delta.reasoning)
          throw protocolError('上游推理标签与推理字段内容冲突');
        item.reasoning_content = delta.reasoning;
      }
      if (typeof item.content === 'string' || delta.content) item.content = delta.content;
      if (ending) this.thinking.delete(index);
      else this.thinking.set(index, state);
    }
  }

  assertFinished() {
    this.legacy?.assertFinished();
    for (const state of this.thinking.values()) {
      if (state.pending || state.mode === 'thinking') throw protocolError('上游在推理分片结束前断开');
    }
  }
}

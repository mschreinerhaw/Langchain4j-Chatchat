import { isObject, type JsonObject } from '../protocol/rules.js';

const masked = '[已遮蔽]';
const omitted = '[已截断]';
const secretKey = /authorization|api[-_]?key|password|secret|cookie|credential|access_token|refresh_token/i;
const wireEnums: Record<string, readonly string[]> = {
  role: ['system', 'developer', 'user', 'assistant', 'tool', 'function'],
  type: ['text', 'input_text', 'image_url', 'input_image', 'input_audio', 'function', 'json_object', 'json_schema',
    'object', 'array', 'string', 'number', 'integer', 'boolean', 'null', 'enabled', 'disabled'],
  tool_choice: ['auto', 'none', 'required'],
  reasoning_effort: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
  effort: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
};
const structuralId = /^[A-Za-z0-9_.:/-]{1,128}$/;
const numericFields = new Set(['max_tokens', 'max_completion_tokens', 'max_output_tokens', 'thinking_budget', 'temperature',
  'top_p', 'top_k', 'min_p', 'n', 'frequency_penalty', 'presence_penalty', 'seed', 'logprobs', 'top_logprobs',
  'minimum', 'maximum', 'minLength', 'maxLength', 'minItems', 'maxItems']);

function preview(body: JsonObject, knownModel: string, secrets: string[]) {
  let nodes = 800;
  let truncated = false;
  function limit() { truncated = true; return omitted; }
  function visit(value: unknown, path: string[], depth: number): unknown {
    if (--nodes < 0 || depth > 12) return limit();
    const key = path.at(-1) ?? '';
    if (secretKey.test(key)) return masked;
    if (['arguments', 'reasoning_content', 'input_audio', 'file_data', 'description', 'default', 'examples',
      'metadata', 'user', 'image_url', 'text', 'tool_call_id'].includes(key)) return masked;
    if (key === 'content' && !Array.isArray(value) && !isObject(value) && value !== null) return masked;
    if (typeof value === 'string') {
      if (path.length === 1 && key === 'model') return value === knownModel ? knownModel : masked;
      if (Object.hasOwn(wireEnums, key) && wireEnums[key].includes(value)) return value;
      if (key === 'name' && path.includes('function') && structuralId.test(value)
           && !secrets.some(secret => secret && value.includes(secret))) return value;
      return masked;
    }
    if (typeof value === 'number' && (!numericFields.has(key) || path.includes('messages'))) return masked;
    if (Array.isArray(value)) {
      const items = value.slice(0, 40).map(item => visit(item, path, depth + 1));
      if (value.length > items.length) items.push(limit());
      return items;
    }
    if (isObject(value)) {
      const fields: [string, unknown][] = [];
      for (const [name, child] of Object.entries(value)) {
        if (fields.length >= 75 || nodes < 1) { fields.push([omitted, limit()]); break; }
        if (!structuralId.test(name) || secrets.some(secret => secret && name.includes(secret))) {
          truncated = true;
          continue;
        }
        fields.push([name, visit(child, [...path, name], depth + 1)]);
      }
      return Object.fromEntries(fields);
    }
    return value;
  }
  const result = visit(body, [], 0);
  return { body: result, truncated };
}

export class RequestCapture {
  private current?: { owner: string; alias: string; clientId?: string; expiresAt: number; evidence?: JsonObject };
  private timer?: ReturnType<typeof setTimeout>;

  private expireAfter(ms: number) {
    clearTimeout(this.timer);
    this.current!.expiresAt = Date.now() + ms;
    this.timer = setTimeout(() => { this.current = undefined; }, ms).unref();
  }

  arm(owner: string, alias: string, clientId?: string) {
    if (this.current && this.current.owner !== owner) return false;
    this.current = { owner, alias, clientId, expiresAt: 0 };
    this.expireAfter(120_000);
    return true;
  }

  status(owner: string) {
    if (this.current?.owner !== owner) return { state: 'idle' };
    const { owner: _owner, evidence, ...scope } = this.current;
    return { state: evidence ? 'captured' : 'armed', ...scope, evidence };
  }

  clear(owner: string) {
    if (this.current?.owner !== owner) return;
    clearTimeout(this.timer);
    this.current = undefined;
  }

  claim(alias: string, clientId: string, details: JsonObject, body: JsonObject, secrets: string[]) {
    if (!this.current || this.current.expiresAt <= Date.now() || this.current.evidence || this.current.alias !== alias
        || (this.current.clientId && this.current.clientId !== clientId)) return;
    const evidence: JsonObject = { ...details, capturedAt: new Date().toISOString(), inbound: preview(body, alias, secrets) };
    this.current.evidence = evidence;
    this.expireAfter(300_000);
    return {
      forwarded: (body: JsonObject, upstreamModel: string, origin: string, path: string) => {
        evidence.forwarded = { origin, path, ...preview(body, upstreamModel, secrets) };
      },
      finished: (details: JsonObject) => { evidence.result = details; },
    };
  }
}

import { z } from 'zod';

const blocked = new Set(['__proto__', 'constructor', 'prototype']);
const forbiddenHeaders = new Set(['host', 'content-length', 'transfer-encoding', 'connection', 'upgrade',
  'te', 'trailer', 'keep-alive', 'cookie', 'set-cookie', 'expect', 'forwarded', 'accept-encoding', 'content-encoding']);
export const headerName = z.string().regex(/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/).transform(value => value.toLowerCase())
  .refine(value => !blocked.has(value) && !forbiddenHeaders.has(value) && !/^(proxy-|x-forwarded-|sec-)/.test(value));
const field = z.string().max(256).refine(value => value.split('.').every(part => /^[-\w]+$/.test(part) && !blocked.has(part)));
const common = z.object({
  stage: z.enum(['request', 'response', 'sse', 'headers']),
  path: field,
});
export const ruleSchema = z.discriminatedUnion('op', [
  common.extend({ op: z.enum(['set', 'default']), value: z.json() }),
  common.extend({ op: z.literal('delete') }),
  common.extend({ op: z.literal('rename'), to: field }),
  common.extend({ op: z.literal('cast'), type: z.enum(['string', 'number', 'boolean']) }),
  common.extend({ op: z.literal('map'), values: z.record(z.string(), z.json()) }),
  common.extend({ op: z.literal('clamp'), min: z.number(), max: z.number() }),
]).superRefine((rule, ctx) => {
  const paths = rule.op === 'rename' ? [rule.path, rule.to] : [rule.path];
  const invalid = (message: string) => ctx.addIssue({ code: 'custom', message });
  if (rule.op === 'clamp' && rule.min > rule.max) invalid('min must not exceed max');
  if (rule.op === 'rename' && (rule.to === rule.path || rule.to.startsWith(rule.path + '.') || rule.path.startsWith(rule.to + '.'))) invalid('Overlapping rename');
  if (rule.stage === 'request' && paths.some(path => /^(model|stream|messages|tools|tool_choice)(\.|$)/.test(path))) {
    invalid('Core request fields are protected');
  }
  if (['response', 'sse'].includes(rule.stage) && paths.some(path => /^(model|usage|error)(\.|$)/.test(path))) {
    invalid('Model, usage and error fields are protected');
  }
  if (rule.stage === 'headers' && paths.some(path => !headerName.safeParse(path).success || path !== path.toLowerCase()
    || ['authorization', 'content-type', 'accept', 'x-api-key'].includes(path))) invalid('Unsafe or reserved header');
});
export type Rule = z.infer<typeof ruleSchema>;
export type JsonObject = Record<string, unknown>;
export function isObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function fieldOwner(root: JsonObject, path: string, create: boolean) {
  const segments = path.split('.');
  const key = segments.pop()!;
  let parent: JsonObject = root;
  for (const segment of segments) {
    if (!Object.hasOwn(parent, segment)) {
      if (!create) return undefined;
      if (Array.isArray(parent)) throw new Error('Rule array index is out of range');
      parent[segment] = {};
    }
    const next = parent[segment];
    if (!isObject(next) && !Array.isArray(next)) {
      if (!create) return undefined;
      throw new Error('Rule path crosses a non-object field');
    }
    parent = next as JsonObject;
  }
  if (Array.isArray(parent) && (!/^(0|[1-9]\d*)$/.test(key) || Number(key) >= parent.length)) {
    if (!create) return undefined;
    throw new Error('Rule array index is out of range');
  }
  return { parent, key };
}

function write(root: JsonObject, path: string, value: unknown) {
  const owner = fieldOwner(root, path, true)!;
  owner.parent[owner.key] = value;
}

export function applyRules(input: JsonObject, rules: Rule[], stage: Rule['stage']): JsonObject {
  const result = structuredClone(input);
  for (const rule of rules) {
    if (rule.stage !== stage) continue;
    const owner = fieldOwner(result, rule.path, false);
    const exists = owner && Object.hasOwn(owner.parent, owner.key);
    const value = exists ? owner.parent[owner.key] : undefined;
    if (rule.op === 'set' || (rule.op === 'default' && !exists)) write(result, rule.path, structuredClone(rule.value));
    if (!exists) continue;
    switch (rule.op) {
      case 'rename':
      case 'delete':
        if (Array.isArray(owner.parent)) throw new Error('Rules cannot remove array elements');
        if (rule.op === 'rename') {
          const target = fieldOwner(result, rule.to, false);
          if (target && Object.hasOwn(target.parent, target.key)) throw new Error('Rename target already exists');
          write(result, rule.to, value);
        }
        delete owner.parent[owner.key];
        break;
      case 'map':
        if ((typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
          && Object.hasOwn(rule.values, String(value))) write(result, rule.path, structuredClone(rule.values[String(value)]));
        break;
      case 'clamp':
        if (typeof value !== 'number') throw new Error('Clamp requires a number');
        write(result, rule.path, Math.min(rule.max, Math.max(rule.min, value)));
        break;
      case 'cast': {
        if (!['string', 'number', 'boolean'].includes(typeof value)) throw new Error('Cast requires a scalar');
        const converted = rule.type === 'string' ? String(value) : rule.type === 'number'
          ? Number(value) : [true, 'true', 1, '1'].includes(value as string) ? true
            : [false, 'false', 0, '0'].includes(value as string) ? false : undefined;
        if (converted === undefined || (typeof converted === 'number' && (!Number.isFinite(converted)
          || (typeof value === 'string' && !value.trim())))) throw new Error('Invalid cast');
        write(result, rule.path, converted);
        break;
      }
    }
  }
  return result;
}

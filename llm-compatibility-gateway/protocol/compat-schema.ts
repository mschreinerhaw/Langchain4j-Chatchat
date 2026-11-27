import { z } from 'zod';

const eventName = z.string().regex(/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/).refine(value => value !== 'error');
const usagePath = z.string().max(128).refine(value =>
  value.split('.').every(key => /^[A-Za-z][A-Za-z0-9_]*$/.test(key) && !['prototype', 'constructor'].includes(key)));
export const protocolSchema = z.object({
  tools: z.enum(['openai', 'legacy-functions']).optional(),
  reasoning: z.enum(['openai', 'reasoning-field', 'thinking-field', 'think-tags']).optional(),
  images: z.enum(['openai', 'flat-image-url']).optional(),
  doneEvent: eventName.nullable().optional(),
  usageEvent: eventName.nullable().optional(),
  usage: z.object({
    prompt_tokens: usagePath.optional(), completion_tokens: usagePath.optional(), total_tokens: usagePath.optional(),
    cached_tokens: usagePath.optional(), reasoning_tokens: usagePath.optional(),
  }).nullable().optional(),
}).strict();
export type Protocol = z.infer<typeof protocolSchema>;

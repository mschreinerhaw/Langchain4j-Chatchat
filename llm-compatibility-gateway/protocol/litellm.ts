import { GatewayError } from '../server/config.js';
import { type JsonObject } from './rules.js';

// The framework receives Chat Completions fields only. A caller cannot
// override its provider, API host, credentials, fallback or retry policy.
const chatFields = new Set([
  'model', 'messages', 'stream', 'stream_options', 'max_tokens', 'max_completion_tokens',
  'tools', 'tool_choice', 'parallel_tool_calls', 'functions', 'function_call',
  'temperature', 'top_p', 'top_k', 'min_p', 'n', 'stop', 'seed', 'frequency_penalty',
  'presence_penalty', 'logit_bias', 'logprobs', 'top_logprobs', 'response_format',
  'reasoning_effort', 'reasoning', 'thinking', 'enable_thinking', 'thinking_budget', 'chat_template_kwargs',
]);

export function litellmRequest(body: JsonObject) {
  if (Object.keys(body).some(key => !chatFields.has(key))) {
    throw new GatewayError(422, 'framework_parameter_unsupported', 'LiteLLM 路由只接受已允许的模型请求字段');
  }
}

export function litellmUsageBoundary(response: JsonObject) {
  // LiteLLM's stream chunk builder can fill absent usage from local estimates.
  // Chat Completions offers no per-frame wire provenance for this distinction.
  // Do not present the proxy's totals as consumption confirmed by the provider.
  delete response.usage;
  return response;
}

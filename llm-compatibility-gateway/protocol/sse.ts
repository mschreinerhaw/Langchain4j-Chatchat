import { GatewayError } from '../server/config.js';

export type Event = { data?: string; other: string[] };

export async function* readEvents(body: AsyncIterable<Uint8Array>): AsyncGenerator<Event> {
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let pending = '';
  let data: string[] = [];
  let other: string[] = [];
  let size = 0;
  function* consume(atEof: boolean) {
    let offset = 0;
    const newline = /\r\n|\r|\n/g;
    let match;
    while ((match = newline.exec(pending))) {
      if (!atEof && match[0] === '\r' && newline.lastIndex === pending.length) break;
      const line = pending.slice(offset, match.index);
      offset = newline.lastIndex;
      size += line.length + 1;
      if (size > 2_000_000) throw protocolError('SSE Event 超过大小限制');
      if (line === '') {
        if (data.length || other.length) yield { data: data.length ? data.join('\n') : undefined, other };
        data = [];
        other = [];
        size = 0;
      } else if (line === 'data' || line.startsWith('data:')) {
        data.push(line === 'data' ? '' : line.slice(5).replace(/^ /, ''));
      } else {
        other.push(line);
      }
    }
    pending = pending.slice(offset);
    if (pending.length + size > 2_000_000) throw protocolError('SSE Event 超过大小限制');
  }
  for await (const chunk of body) {
    pending += decoder.decode(chunk, { stream: true });
    yield* consume(false);
  }
  pending += decoder.decode();
  yield* consume(true);
  if (pending || data.length || other.length) throw protocolError('上游在 SSE Event 结束前断开');
}

export function eventText(event: Event) {
  return [...event.other, ...(event.data === undefined ? [] : event.data.split('\n').map(line => 'data: ' + line))]
    .join('\n') + '\n\n';
}

export function protocolError(message: string) {
  return new GatewayError(502, 'upstream_protocol_error', message);
}

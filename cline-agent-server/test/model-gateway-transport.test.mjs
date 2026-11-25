import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';
import { requestModelGateway } from '../src/model-gateway-transport.mjs';
import { modelReceipt } from '../src/model-receipt.mjs';

test('model broker waits after connection and destroys the Gateway response on Runtime cancellation', async t => {
 const controller = new AbortController();
 let disconnected;
 const observedClose = new Promise(resolve => { disconnected = resolve; });
 const server = createServer(async (request, reply) => {
  reply.on('close', disconnected);
  await delay(100);
  reply.writeHead(200, {'content-type':'text/event-stream', 'x-request-id':'gateway-trace'});
  reply.write('data: {"choices":[]}\n\n');
 });
 await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
 t.after(() => { server.closeAllConnections(); server.close(); });
 const result = await requestModelGateway('http://127.0.0.1:'+server.address().port, {
  signal:controller.signal, headers:{'x-runtime-request-id':'broker-trace'}, body:'{}',
 }, 75);
 assert.equal(result.headers.get('x-request-id'),'gateway-trace');
 const reader = result.body[Symbol.asyncIterator]();
 assert.equal((await reader.next()).done,false);
 controller.abort(Error('Runtime stopped'));
 await assert.rejects(reader.next());
 await observedClose;
});

test('HTTP 200 SSE error remains a failed model receipt with the Gateway trace', () => {
 const events=[];
 const receipt=modelReceipt({model:'published/alias:tools',request:'{}',emit:(_type, value)=>events.push(value)});
 receipt.headers('gw-id');
 receipt.observe(Buffer.from('data: {"error":{"code":"runtime_deadline_exceeded","message":"detail"}}\n\n'));
 receipt.finish({httpStatus:200});
 assert.equal(events[1].status,'FAILED');
 assert.equal(events[1].errorCode,'GATEWAY_RUNTIME_DEADLINE_EXCEEDED');
 assert.equal(events[1].gatewayRequestId,'gw-id');
});

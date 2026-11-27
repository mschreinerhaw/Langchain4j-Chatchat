import assert from 'node:assert/strict';
import { createServer } from 'node:http';

createServer(async (req, res) => {
  try {
    assert.equal(req.method, 'POST');
    assert.equal(req.url, '/v1/messages');
    assert.equal(req.headers['x-api-key'], 'anthropic-fixture-key');
    assert.ok(req.headers['anthropic-version']);
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.equal(body.model, 'claude-sonnet-4-5');
    assert.equal(body.system[0].text, 'Use the fixture tool.');
    assert.equal(body.tools[0].input_schema.properties.amount.type, 'integer');
    assert.equal(body.stream, true);
    const continued = body.messages.some(message => message.content.some(block => block.type === 'tool_result'));
    if (continued) {
      const toolUse = body.messages.flatMap(message => message.content).find(block => block.type === 'tool_use');
      const toolResult = body.messages.flatMap(message => message.content).find(block => block.type === 'tool_result');
      assert.equal(toolUse.id, 'toolu_fixture');
      assert.deepEqual(toolUse.input, { amount:3 });
      assert.equal(toolResult.tool_use_id, 'toolu_fixture');
      assert.match(JSON.stringify(toolResult.content), /count=3/);
    }
    console.log(JSON.stringify({ path:req.url, providerModel:body.model, toolResultVerified:continued }));
    res.writeHead(200, { 'content-type':'text/event-stream' });
    const event = (type, fields = {}) => res.write('event: ' + type + '\ndata: ' + JSON.stringify({ type, ...fields }) + '\n\n');
    event('message_start', { message:{ id:'msg_fixture', type:'message', role:'assistant', content:[], model:body.model,
      stop_reason:null, stop_sequence:null, usage:{ input_tokens:17, output_tokens:1 } } });
    if (continued) {
      event('content_block_start', { index:0, content_block:{ type:'text', text:'' } });
      event('content_block_delta', { index:0, delta:{ type:'text_delta', text:'Fixture count is 3.' } });
    } else {
      event('content_block_start', { index:0, content_block:{ type:'tool_use', id:'toolu_fixture', name:'count', input:{} } });
      event('content_block_delta', { index:0, delta:{ type:'input_json_delta', partial_json:'{"amount":' } });
      event('content_block_delta', { index:0, delta:{ type:'input_json_delta', partial_json:'3}' } });
    }
    event('content_block_stop', { index:0 });
    event('message_delta', { delta:{ stop_reason:continued ? 'end_turn' : 'tool_use', stop_sequence:null }, usage:{ output_tokens:6 } });
    event('message_stop');
    res.end();
  } catch (error) {
    console.error(error.message);
    if (!res.headersSent) res.writeHead(400, { 'content-type':'application/json' });
    res.end(JSON.stringify({ type:'error', error:{ type:'invalid_request_error', message:error.message } }));
  }
}).listen(8000, '0.0.0.0');

import http from 'node:http';
import https from 'node:https';

// The managed Gateway receives the Runtime deadline. The broker only bounds
// DNS/TCP/TLS setup and forwards the same cancellation signal to the response.
export function requestModelGateway(url, {headers, body, signal}, connectMs = 10_000) {
 const endpoint = new URL(url);
 const transport = endpoint.protocol === 'https:' ? https : http;
 return new Promise((resolve, reject) => {
  const request = transport.request(endpoint, {method:'POST', headers, signal, agent:false}, response => {
   clearTimeout(timer);
   if(response.statusCode >= 300 && response.statusCode < 400) {
    response.destroy();
    reject(Object.assign(Error('Model Gateway redirect rejected'), {code:'GATEWAY_REDIRECT'}));
    return;
   }
   resolve({status:response.statusCode, headers:{get:name=>response.headers[name.toLowerCase()]}, body:response});
  });
  const timer = setTimeout(() => request.destroy(Object.assign(Error('Model Gateway connect timeout'),{code:'ETIMEDOUT'})),connectMs);
  timer.unref();
  request.once('socket', socket => {
   socket.once(endpoint.protocol === 'https:' ? 'secureConnect' : 'connect', () => clearTimeout(timer));
  });
  request.once('error', error => {clearTimeout(timer);reject(error);});
  request.end(body);
 });
}

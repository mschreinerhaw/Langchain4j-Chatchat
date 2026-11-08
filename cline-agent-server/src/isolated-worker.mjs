// The Agent has no network interface except loopback. Only its own broker socket
// is mounted. This proxy transports bytes; all native model/tool decisions remain in Cline.
import http from 'node:http';
const proxy=http.createServer((req,res)=>{
 const upstream=http.request({socketPath:'/poc/bridge/broker.sock',path:req.url,method:req.method,headers:{...req.headers,host:'broker.local'}},reply=>{
  res.writeHead(reply.statusCode,reply.headers);reply.pipe(res);reply.on('error',()=>res.destroy());
 });upstream.on('error',()=>{if(!res.headersSent)res.writeHead(503);res.end();});req.pipe(upstream);res.on('close',()=>upstream.destroy());
});
await new Promise((resolve,reject)=>{proxy.once('error',reject);proxy.listen(18329,'127.0.0.1',resolve);});
await import('./server.mjs');

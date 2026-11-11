"""NDJSON client for the UNMODIFIED official CLI's ACP interface.

Only protocol transport, raw evidence and task tool grants live here. No model
requests, Python programs, generated tool output or Agent iteration loop.
"""
import json, re, threading, time

class OfficialAcpClient:
    def __init__(self,process,journal,allowed_tools):
        self.process=process;self.journal=journal;self.allowed=frozenset(allowed_tools)
        self.pending={};self.ordinal=0;self.lock=threading.RLock();self.failure=None
        self.reader=threading.Thread(target=self._read,daemon=True);self.reader.start()

    def _log(self,direction,value):
        with self.lock:
            self.journal.write(json.dumps({'observedAtNs':time.time_ns(),'direction':direction,'message':value},ensure_ascii=False)+'\n');self.journal.flush()

    def _send(self,message):
        with self.lock:
            self._log('client-to-official-cli',message)
            self.process.stdin.write(json.dumps(message,ensure_ascii=False)+'\n');self.process.stdin.flush()

    def _read(self):
        try:
            for line in self.process.stdout:
                try:message=json.loads(line)
                except ValueError:
                    self._log('non-protocol-stdout',{'text':line});continue
                self._log('official-cli-to-client',message)
                if 'method' in message:
                    if 'id' not in message:continue
                    if message['method']=='session/request_permission':
                        params=message.get('params',{});title=params.get('toolCall',{}).get('title','')
                        match=re.match(r'^([A-Za-z_][A-Za-z0-9_-]*)(?:: |$)',title)
                        allowed=bool(match and match.group(1) in self.allowed)
                        kind='allow_once' if allowed else 'reject_once'
                        choices=[o for o in params.get('options',[]) if o.get('kind')==kind]
                        outcome={'outcome':'selected','optionId':choices[0]['optionId']} if choices else {'outcome':'cancelled'}
                        self._send({'jsonrpc':'2.0','id':message['id'],'result':{'outcome':outcome}})
                    else:
                        # Client fs/terminal capabilities are not advertised.
                        # Official local Read/Edit/Shell executors own the work.
                        self._send({'jsonrpc':'2.0','id':message['id'],'error':{'code':-32601,'message':'Client capability not available in the fixed local-tool profile'}})
                elif 'id' in message:
                    with self.lock:
                        waiting=self.pending.get(message['id'])
                        if waiting:waiting['response']=message;waiting['event'].set()
        except Exception as error:self.failure=error
        finally:
            with self.lock:
                self.failure=self.failure or RuntimeError('Official ACP stream closed')
                for waiting in self.pending.values():waiting['event'].set()

    def call(self,method,params,timeout):
        with self.lock:
            self.ordinal+=1;ident='control-'+str(self.ordinal)
            waiting={'event':threading.Event()};self.pending[ident]=waiting
            self._send({'jsonrpc':'2.0','id':ident,'method':method,'params':params})
        try:
            if not waiting['event'].wait(timeout):raise TimeoutError('Official ACP '+method+' deadline exceeded')
            response=waiting.get('response')
            if response is None:raise RuntimeError('Official ACP connection failed') from self.failure
            if 'error' in response:raise RuntimeError('Official ACP returned error: '+json.dumps(response['error']))
            return response.get('result',{})
        finally:
            with self.lock:self.pending.pop(ident,None)

    def notify(self,method,params):self._send({'jsonrpc':'2.0','method':method,'params':params})

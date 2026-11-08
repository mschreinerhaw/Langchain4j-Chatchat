// Only host-boundary instrumentation. Core, tools, prompts and Agent loop stay unchanged.
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
const root=resolve(process.argv[2]??'');
const hash=b=>createHash('sha256').update(b).digest('hex');
const pin=JSON.parse(readFileSync(new URL('../upstream-pin.json',import.meta.url),'utf8'));
for(const file of pin.files)if(hash(readFileSync(join(root,file.path)))!==file.sha256)throw Error('Fixed upstream fingerprint mismatch: '+file.path);
if(JSON.parse(readFileSync(join(root,'apps/vscode/package.json'),'utf8')).version!=='4.1.26')throw Error('Expected v4.1.26 source');
const changes=[];
function patch(path,transform){
 const file=join(root,'apps/vscode',path),before=readFileSync(file,'utf8');
 if(before.includes('CLINE_SERVER_POC'))throw Error('Patch already applied: '+path);
 const after=transform(before);
 if(after===before)throw Error('Patch anchor not found: '+path);
 writeFileSync(file,after);changes.push({path:'apps/vscode/'+path,beforeSha256:hash(before),afterSha256:hash(after)});
}
patch('src/standalone/cline-core.ts',s=>s.replace('log(`Environment variables: ${JSON.stringify(process.env)}`)','log("CLINE_SERVER_POC: environment values omitted")').replace('log("All services started successfully")','log("All services started successfully")\n\t\tif (process.connected) process.send?.({ kind: "native.ready" }, () => {})'));
patch('src/sdk/sdk-session-event-coordinator.ts',s=>s.replace('async handleSessionEvent(event: CoreSessionEvent): Promise<void> {',`async handleSessionEvent(event: CoreSessionEvent): Promise<void> {
        // CLINE_SERVER_POC: observe before UI projection, never alter native dispatch.
        if (process.connected) process.send?.({ kind: "native.event", event }, () => {})`));
patch('src/sdk/sdk-interaction-coordinator.ts',s=>{
 s='import { createHash as serverHash } from "node:crypto"\n'+s;
 const anchor='\n\t\tvoid Promise.resolve()\n\t\t\t.then(() => this.options.postStateToWebview())';
 const bridge=`
        // CLINE_SERVER_POC: correlate an HTTP decision to this exact native resolver.
        if (process.connected) {
            const inputHash = serverHash("sha256").update(JSON.stringify(request.input)).digest("hex")
            const epoch = process.env.CLINE_SERVER_EPOCH
            const listener = (message: any) => {
                if (message?.kind !== "permission.respond" || message.epoch !== epoch ||
                    message.toolCallId !== request.toolCallId || message.inputHash !== inputHash) return
                if (this.pendingToolApprovalResolve !== resolveApproval) return
                const applied = this.resolvePendingToolApproval(message.reason,
                    message.decision === "approve" ? "yesButtonClicked" : "noButtonClicked")
                if (process.connected) process.send?.({ kind: "permission.ack", commandId: message.commandId, applied }, () => {})
            }
            process.on("message", listener)
            void approval.finally(() => process.off("message", listener))
            process.send?.({ kind: "native.permission", epoch, inputHash, request }, () => {})
        }
`;
 if(!s.includes(anchor))throw Error('Approval anchor missing');
 s=s.replace(anchor,'\n'+bridge+anchor);
 const questionAnchor='this.pendingAskResolve = resolveAnswer';
 return s.replace(questionAnchor,questionAnchor+`
        // CLINE_SERVER_POC: preserve the product ask_question resolver with correlation.
        if (process.connected) {
            const questionId = serverHash("sha256").update(String(askMessage.ts) + question).digest("hex")
            const epoch = process.env.CLINE_SERVER_EPOCH
            const listener = (message: any) => {
                if (message?.kind !== "question.respond" || message.epoch !== epoch || message.questionId !== questionId) return
                if (this.pendingAskResolve !== resolveAnswer || !message.text?.trim()) return
                this.resolvePendingAskQuestion(message.text)
            }
            process.on("message", listener)
            void answer.finally(() => process.off("message", listener)).catch(() => {})
            process.send?.({ kind: "native.question", questionId, epoch, question, options }, () => {})
        }
`);
});
patch('src/sdk/vscode-session-host.ts',s=>s.replace('const toolExecutors: Partial<ToolExecutors> = {}',`const toolExecutors: Partial<ToolExecutors> = {}
        // CLINE_SERVER_POC: the official completion tool verifies real Runtime receipts.
        if (process.env.CLINE_SERVER_REQUIRE_SUBMIT === "1") {
            toolExecutors.submit = async (summary, verified, context) => {
                const response = await fetch("http://127.0.0.1:18329/native/submit", {
                    method: "POST",
                    headers: { authorization: "Bearer " + process.env.CLINE_SERVER_BROKER_TOKEN, "content-type": "application/json" },
                    body: JSON.stringify({ summary, verified, nativeContext: { nativeSessionId: context?.sessionId, nativeToolCallId: context?.toolCallId, epoch: process.env.CLINE_SERVER_EPOCH } }),
                    signal: context?.signal,
                })
                if (!response.ok) throw new Error("Runtime completion verification rejected: " + response.status + " " + await response.text())
                return await response.text()
            }
        }`).replace('extraTools: [...(inputWithRemoteConfig.config.extraTools ?? []), ...extraTools],',`extraTools: [...(inputWithRemoteConfig.config.extraTools ?? []), ...extraTools],
                // CLINE_SERVER_POC: select official tools through the public host routing config.
                toolRoutingRules: [
                    ...(inputWithRemoteConfig.config.toolRoutingRules ?? []),
                    ...(process.env.CLINE_SERVER_EDIT_TOOL === "apply_patch" ? [{ mode: "act" as const, enableTools: ["apply_patch" as const], disableTools: ["editor" as const] }] : []),
                    ...(process.env.CLINE_SERVER_REQUIRE_SUBMIT === "1" ? [{ mode: "act" as const, enableTools: ["submit_and_exit" as const] }] : []),
                ],`));
// ask_question uses the existing product response path; no new agent or tool executor.
mkdirSync(join(root,'server-poc-evidence'),{recursive:true});
writeFileSync(join(root,'server-poc-evidence/host-patches.json'),JSON.stringify(changes,null,2)+'\n');
console.log(JSON.stringify({patched:changes}));

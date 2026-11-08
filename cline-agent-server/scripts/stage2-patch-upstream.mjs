// Two host-boundary additions only. Never change Agent Core or tool execution.
import {readFileSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
const root=process.argv[2],changes=[];
function patch(path,before,after){const p=join(root,path),s=readFileSync(p,'utf8');if(!s.includes(before))throw Error('Pinned stage2 anchor missing: '+path);const next=s.replace(before,after);writeFileSync(p,next);changes.push({path,beforeSha256:createHash('sha256').update(s).digest('hex'),afterSha256:createHash('sha256').update(next).digest('hex')});}
patch('apps/vscode/src/sdk/vscode-runtime-builder.ts',
'return this.mcpHub.callTool(request.serverName, request.toolName, request.arguments ?? {}, ulid, request.context?.signal)',
`return this.mcpHub.callTool(request.serverName, request.toolName, request.arguments ?? {}, ulid, request.context?.signal,
            process.env.CLINE_SERVER_OPERATION_CONTEXT === "1" ? { runtimeNative: {
                nativeSessionId: request.context?.sessionId,
                nativeToolCallId: request.context?.toolCallId,
                epoch: process.env.CLINE_SERVER_EPOCH,
            } } : undefined)`);
patch('apps/vscode/src/services/mcp/McpHub.ts',
'\t\tulid: string,\n\t\tsignal?: AbortSignal,\n\t): Promise<McpToolCallResponse>',
'\t\tulid: string,\n\t\tsignal?: AbortSignal,\n\t\toperationContext?: Record<string, unknown>,\n\t): Promise<McpToolCallResponse>');
patch('apps/vscode/src/services/mcp/McpHub.ts','\t\t\t\t\t\targuments: toolArguments ?? {},','\t\t\t\t\t\targuments: toolArguments ?? {},\n\t\t\t\t\t\t...(operationContext ? { _meta: operationContext } : {}),');
writeFileSync(join(root,'stage2-host-patches.json'),JSON.stringify(changes,null,2));
console.log(JSON.stringify({hostPatches:changes.length,agentCoreChanges:0}));

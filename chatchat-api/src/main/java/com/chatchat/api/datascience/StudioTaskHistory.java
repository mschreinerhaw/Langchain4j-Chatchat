package com.chatchat.api.datascience;

import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Projection into the existing owner-scoped conversation store, never a second task store. */
@Service @RequiredArgsConstructor
public class StudioTaskHistory {
    private final ConversationService conversations;
    public record Request(String conversationId, String language, String question, String modelName,
                          Map<String,Object> plan) { }

    public void project(KernelDataScope scope, String task, Request request, CodingTaskOutcome outcome,
                        Map<String,Object> output, List<Map<String,Object>> events) {
        if(scope.tenantId()==null || scope.userId()==null || request.question()==null || request.question().isBlank()
                || !Set.of("sql","python").contains(request.language()))
            throw new SecurityException("Authenticated Studio history required");
        String session=request.conversationId();
        if(session==null || session.isBlank()) session=UUID.nameUUIDFromBytes(
            (scope.tenantId()+"\0"+scope.userId()+"\0"+task).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var existing=conversations.getConversation(scope.tenantId(),session);
        if(existing.isPresent() && !scope.userId().equals(existing.get().getUserId()))
            throw new SecurityException("Studio history owner differs");
        String status=outcome==null?"running":switch(outcome.status()) {
            case CREATED,RUNNING -> "running";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            default -> "failed";
        };
        String label=request.language().equals("sql")?"SQLStudio":"PythonStudio";
        String answer=outcome==null?"任务已交付 Cline，等待生成结果。":outcome.summary();
        if(output!=null && !output.isEmpty()) {
            var text=new StringBuilder(Objects.toString(output.get("notes"),""));
            if(output.get("files") instanceof List<?> files) for(Object item:files) {
                if(item instanceof Map<?,?> file) text.append("\n\n").append(file.get("path")).append("\n````")
                    .append(request.language()).append('\n').append(file.get("content")).append("\n````");
            }
            answer=text.append("\n\n校验仅涉及语法及已交付元数据；未执行生成的代码或 SQL，变更需用户审查。" ).toString();
        }
        var steps=request.plan()==null?List.<Map<String,Object>>of():List.of(request.plan());
        var now=LocalDateTime.now();
        String messageIdentity=scope.tenantId()+"\0"+scope.userId()+"\0"+task+"\0";
        var user=Conversation.Message.builder().id(messageId(messageIdentity+"user")).role("user").content(request.question())
            .timestamp(now).taskId(task).modelName(request.modelName()).build();
        var assistant=Conversation.Message.builder().id(messageId(messageIdentity+"assistant")).role("assistant").content(answer)
            .timestamp(now).taskId(task).status(status).streaming(false).modelName(request.modelName())
            .agentName(label).steps(steps).traces(events)
            .memoryContext(Map.of("taskOrigin","studio-coding","codingTaskId",task,"language",request.language(),
                "codingTaskProtocol",com.chatchat.common.runtime.coding.CodingProtocol.WORKSPACE_VERSION,
                "codingResultFormat","WORKSPACE_FILES")).build();
        conversations.updateConversationSummary(scope.tenantId(),session,scope.userId(),
            label+": "+request.question(),status,null,request.modelName(),"llm_chat",label);
        conversations.mergeMessages(scope.tenantId(),session,scope.userId(),List.of(user,assistant));
    }
    private static String messageId(String identity){
        return UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}

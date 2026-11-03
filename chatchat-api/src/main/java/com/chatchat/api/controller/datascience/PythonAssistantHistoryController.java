package com.chatchat.api.controller.datascience;

import com.chatchat.api.datascience.python.PythonCodeAssistantService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/python/assist/history")
public class PythonAssistantHistoryController {
    private final ConversationService conversations;
    private final ObjectAccessService access;
    private final PythonCodeAssistantService assistant;

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request,@RequestParam(value="page",defaultValue="1") int page){
        return ApiResponse.success(conversations.listStudioConversationSummaryPage(access.tenant(request),access.ownerAliases(request),"PYTHON_STUDIO","",page,30));
    }
    private Conversation owned(HttpServletRequest request,String id){
        access.requireConversation(request,id,false);
        var conversation=conversations.getConversation(access.tenant(request),id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在"));
        if(!"PYTHON_STUDIO".equals(conversation.getSourceChannel()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
        return conversation;
    }
    @GetMapping("/{id}") public ApiResponse<?> detail(HttpServletRequest request,@PathVariable("id") String id){return ApiResponse.success(owned(request,id));}
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(HttpServletRequest request,@PathVariable("id") String id){
        var conversation=owned(request,id);
        for(var message:conversation.getMessages()){
            var memory=message.getMemoryContext();
            if(memory!=null && memory.get("codingTaskId") instanceof String task){
                String status=String.valueOf(assistant.codingResult(access.tenant(request),access.user(request),task,false).get("status"));
                if(java.util.Set.of("CREATED","RUNNING").contains(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"请等待任务完成或停止后再删除会话");
            }
        }
        conversations.deleteConversation(access.tenant(request),id);
        return ApiResponse.success(true);
    }
}

package com.chatchat.api.controller.chat;

import com.chatchat.api.assistance.QuestionIdeaService;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only suggestions for the current owned Zhice conversation. Never creates a chat or task. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/interactions")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class ChatIdeaController {
    private final WorkspaceService workspaces;
    private final ObjectAccessService access;
    private final ConversationService conversations;
    private final SkillCatalogService catalog;
    private final EnterpriseAdminService identities;
    private final QuestionIdeaService ideas;
    public record Request(String conversationId,String draft,String agentId,List<String> fileIds) {}
    @PostMapping("/ideas") public ApiResponse<?> generate(HttpServletRequest http,@RequestBody Request body) throws Exception {
        workspaces.requireAvailable(http,"zhice");
        var owner=new UserAssetService.Owner(access.tenant(http),access.user(http));
        if(body==null||body.draft()!=null&&body.draft().length()>200000)throw new IllegalArgumentException("问题草稿过长");
        var context=new LinkedHashMap<String,Object>();context.put("workspace","灵动智策");
        context.put("draft",QuestionIdeaService.clip(body.draft(),8000));
        context.put("draftTruncated",body.draft()!=null&&body.draft().length()>8000);
        String alias=null;
        if(body.agentId()!=null&&!body.agentId().isBlank()){
            var agent=catalog.resolve(body.agentId());
            if(agent==null||!body.agentId().equals(agent.id())||!"published".equalsIgnoreCase(agent.marketStatus())
                ||!identities.canAccessAgent(owner.user(),agent.id()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前 Agent 未发布或未获授权");
            alias=agent.modelName();context.put("agent",agent.label());
        }
        if(body.conversationId()!=null&&!body.conversationId().isBlank()){
            access.requireConversation(http,body.conversationId(),false);
            var conversation=conversations.getConversation(owner.tenant(),body.conversationId())
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"当前对话不存在"));
            access.requireOwner(http,conversation.getTenantId(),conversation.getUserId(),false);
            String channel=conversation.getSourceChannel();
            if(channel!=null&&!channel.isBlank()&&!"PLATFORM_UI".equals(channel))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请选择智策空间的对话");
            var history=new ArrayList<Map<String,String>>();
            var messages=conversation.getMessages();int remaining=12000;
            if(messages!=null)for(int i=messages.size()-1;i>=0&&history.size()<8&&remaining>0;i--){
                var message=messages.get(i);
                if(message==null||!Set.of("user","assistant").contains(Objects.toString(message.getRole(),""))
                    ||Boolean.TRUE.equals(message.getStreaming())||Set.of("running","streaming","pending").contains(Objects.toString(message.getStatus(),"").toLowerCase(Locale.ROOT)))continue;
                String content=Objects.toString(message.getContent(),"").replaceAll("(?s)<think>.*?(?:</think>|$)","").trim();
                if(content.isBlank())continue;
                content=QuestionIdeaService.clip(content,Math.min(2000,remaining));remaining-=content.length();
                history.add(Map.of("role",message.getRole(),"content",content));
            }
            Collections.reverse(history);context.put("conversation",Map.of("title",QuestionIdeaService.clip(conversation.getTitle(),200),"messages",history));
        }
        return ApiResponse.success(ideas.generate(owner,alias,context,body.fileIds()==null?List.of():body.fileIds()));
    }
}

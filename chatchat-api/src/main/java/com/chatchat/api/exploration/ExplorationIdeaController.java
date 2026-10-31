package com.chatchat.api.exploration;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

/** Suggestions only: one bounded model call with owner-loaded context, without tools or task submission. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/explorations")
@ConditionalOnProperty(prefix="chatchat.coding.analysis",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class ExplorationIdeaController {
    private final WorkspaceService workspaces;
    private final ObjectAccessService access;
    private final ExplorationWorkspaceStore store;
    private final ExplorationAgentBridge agents;
    private final ObjectMapper json;
    private final com.chatchat.api.assistance.QuestionIdeaService ideas;
    public record Request(String workspaceId,String nodeId,String draft,String agentId,List<String> fileIds) {}
    @PostMapping("/ideas") public ApiResponse<?> ideas(HttpServletRequest http,@RequestBody Request body) throws Exception {
        workspaces.requireAvailable(http,"data-exploration");
        var owner=new UserAssetService.Owner(access.tenant(http),access.user(http));
        if(body==null||body.draft()!=null&&body.draft().length()>200000)throw new IllegalArgumentException("问题草稿过长");
        var context=new LinkedHashMap<String,Object>();context.put("draft",clip(body.draft(),8000));
        context.put("draftTruncated",body.draft()!=null&&body.draft().length()>8000);
        String alias=null;
        if(body.agentId()!=null&&!body.agentId().isBlank()) {
            var skill=agents.authorize(owner,body.agentId());alias=skill.modelName();context.put("agent",skill.label());
        }
        var ids=new LinkedHashSet<String>();
        if(body.workspaceId()!=null&&!body.workspaceId().isBlank()){
            var project=store.get(owner,body.workspaceId());context.put("projectTitle",project.path("title").asText());
            if(body.nodeId()!=null&&!body.nodeId().isBlank()){
                var current=java.util.stream.StreamSupport.stream(project.withArray("nodes").spliterator(),false)
                    .filter(n->body.nodeId().equals(n.path("id").asText())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"分析节点不存在"));
                var receipt=current.path("receipt");var report=receipt.path("visualReport");
                context.put("currentAnalysis",Map.of("question",clip(current.path("question").asText(),4000),
                    "summary",clip(report.path("summary").asText(receipt.path("answer").asText()),6000),
                    "tablesPreview",clip(report.path("tables").toString(),8000),"resultAvailable",receipt.path("resultAvailable").asBoolean()));
                current.path("fileIds").forEach(id->ids.add(id.asText()));
            }
        }else if(body.nodeId()!=null&&!body.nodeId().isBlank())throw new IllegalArgumentException("请选择分析项目");
        if(body.fileIds()!=null)ids.addAll(body.fileIds());
        return ApiResponse.success(ideas.generate(owner,alias,context,ids));
    }
    private static String clip(String value,int limit){return value==null?"":value.substring(0,Math.min(limit,value.length()));}
}

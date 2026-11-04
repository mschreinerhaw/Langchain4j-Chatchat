package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Current authorization applies to history/result JSON as well as active tools. Internal callbacks bind separately. */
@RestControllerAdvice
public class KnowledgeResponseAdvice implements ResponseBodyAdvice<Object> {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(KnowledgeResponseAdvice.class);
    private final KnowledgeDisclosureGuard guard;
    private final ObjectMapper json;
    public KnowledgeResponseAdvice(KnowledgeDisclosureGuard guard,ObjectMapper json){this.guard=guard;this.json=json;}
    @Override public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> c){return true;}
    @Override public Object beforeBodyWrite(Object body,MethodParameter p,MediaType type,Class<? extends HttpMessageConverter<?>> c,
            ServerHttpRequest request,ServerHttpResponse response) {
        if(body==null||!(request instanceof ServletServerHttpRequest servlet)
                ||servlet.getServletRequest().getRequestURI().startsWith("/internal/")||body instanceof byte[])return body;
        HttpServletRequest actor=servlet.getServletRequest();
        // This concrete, allowlisted DTO contains journal state/counters only. Its controller
        // independently authorizes owner or tenant-wide task monitors. Rich responses still
        // require current source authorization below; JSON markers cannot opt out.
        if (body instanceof com.chatchat.common.response.ApiResponse<?> envelope
                && envelope.getData() instanceof com.chatchat.api.controller.agent.CodingOperationalStatus) {
            response.getHeaders().setCacheControl("no-store");
            return body;
        }
        try {
            // Run-scoped monitors can truncate a model summary before its first citation.
            // Bind provenance to the decoded route, not just markers remaining in the JSON body.
            String prefix="/api/v1/agent/runtime/runs/",uri=actor.getRequestURI();
            if(uri.startsWith(prefix)) {
                String tail=uri.substring(prefix.length());int slash=tail.indexOf('/');
                String run=org.springframework.web.util.UriUtils.decode(slash<0?tail:tail.substring(0,slash),java.nio.charset.StandardCharsets.UTF_8);
                if(guard.checkRun((String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),
                        (String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),run))response.getHeaders().setCacheControl("no-store");
            }
            String value=body instanceof String text?text:json.writeValueAsString(body);
            // Only the concrete owner-authorized conversation endpoint may return
            // message placeholders. Other source/result endpoints remain fail-closed.
            if("GET".equals(actor.getMethod())&&uri.matches("/api/v1/conversations/[^/]+")
                    &&body instanceof com.chatchat.common.response.ApiResponse<?> envelope
                    &&envelope.getData() instanceof com.chatchat.chat.conversation.model.Conversation) {
                var projected=ConversationContentDisclosure.sanitize(json.readTree(value),node->validateContent(actor,node));
                if(projected!=null){response.getHeaders().setCacheControl("no-store");return projected;}
            }
            var collection=TaskCollectionDisclosure.sanitize(actor.getMethod(),uri,json.readTree(value),task->{
                guard.check((String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),
                    (String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),task.toString());
                guard.checkRun((String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),
                    (String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),task.path("attemptId").asText(null));
            });
            if(collection!=null) {
                response.getHeaders().setCacheControl("no-store");
                return body instanceof String ? json.writeValueAsString(collection) : collection;
            }
            if(value.contains("runtime-evidence:knowledge:")) {
                response.getHeaders().setCacheControl("no-store");
                guard.check((String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),
                    (String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),value);
            }
            if(value.contains("attemptId")||value.contains("runId")) {
                var pending=new java.util.ArrayDeque<com.fasterxml.jackson.databind.JsonNode>();pending.add(json.readTree(value));
                var checked=new java.util.HashSet<String>();
                while(!pending.isEmpty()) {
                    var node=pending.removeFirst();String run=null;
                    if(node.isObject()) {
                        if(node.has("canonicalState")&&node.has("taskId"))run=node.path("attemptId").asText(null);
                        else if(node.has("runId")&&(node.has("request")&&node.has("result")||node.has("eventId")&&node.has("payload")))run=node.path("runId").asText(null);
                        if(run!=null&&checked.add(run)&&guard.checkRun((String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID),
                                (String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID),run))response.getHeaders().setCacheControl("no-store");
                    }
                    if(node.isContainerNode())node.elements().forEachRemaining(child->{if(child.isContainerNode())pending.add(child);});
                }
            }
            return body;
        }catch(SecurityException denied){
            // Fixed reason vocabulary only; never log response bodies, model arguments or source data.
            String reason=switch(java.util.Objects.toString(denied.getMessage(),"")) {
                case "Source cannot be revalidated; retained content is withheld" -> "SOURCE_UNAVAILABLE";
                case "Resource source changed; start a new task to acquire current evidence" -> "SOURCE_CHANGED";
                case "Knowledge reference unavailable to current user" -> "REFERENCE_NOT_OWNED";
                case "Data resource authorization was revoked or not requested" -> "RESOURCE_REVOKED";
                case "Agent authorization was revoked" -> "AGENT_REVOKED";
                default -> "PROVENANCE_OR_AUTHORIZATION";
            };
            log.warn("Knowledge disclosure withheld reason={} cause={}",reason,denied.getCause()==null?"none":denied.getCause().getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Document evidence authorization or version is no longer valid");
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return body;}
    }

    private void validateContent(HttpServletRequest actor,com.fasterxml.jackson.databind.JsonNode node) {
        String tenant=(String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String user=(String)actor.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        String value=node.toString();
        if(value.contains("runtime-evidence:knowledge:"))guard.check(tenant,user,value);
        var pending=new java.util.ArrayDeque<com.fasterxml.jackson.databind.JsonNode>();pending.add(node);
        var checked=new java.util.HashSet<String>();
        while(!pending.isEmpty()) {
            var current=pending.removeFirst();String run=null;
            if(current.isObject()) {
                if(current.has("canonicalState")&&current.has("taskId"))run=current.path("attemptId").asText(null);
                else if(current.has("runId")&&(current.has("request")&&current.has("result")||current.has("eventId")&&current.has("payload")))run=current.path("runId").asText(null);
                if(run!=null&&checked.add(run))guard.checkRun(tenant,user,run);
            }
            if(current.isContainerNode())current.elements().forEachRemaining(child->{if(child.isContainerNode())pending.add(child);});
        }
    }
}

package com.chatchat.api.runtime.coding.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.function.Consumer;

/** Response-only projection: unavailable sources withhold one message, never rewrite history. */
final class ConversationContentDisclosure {
    private static final Set<String> HEADERS=Set.of("id","tenantId","userId","title","status","sourceChannel",
        "skillId","modelName","mode","agentName","createdAt","updatedAt","missingMessageCount");
    private static final Set<String> MESSAGE_METADATA=Set.of("id","role","timestamp","agentName","modelName");
    private ConversationContentDisclosure() {}

    static JsonNode sanitize(JsonNode body,Consumer<JsonNode> validate) {
        if(!body.isObject()||body.path("code").asInt()!=200||!body.path("data").isObject()
                ||!(body.path("data").path("messages") instanceof ArrayNode))return null;
        ObjectNode result=body.deepCopy(),data=(ObjectNode)result.path("data");
        ObjectNode envelope=result.deepCopy();envelope.remove("data");validate.accept(envelope);
        ObjectNode header=data.deepCopy();header.remove(java.util.List.of("messages","analysisTree"));validate.accept(header);
        ArrayNode messages=(ArrayNode)data.path("messages");boolean restricted=false;
        for(int i=0;i<messages.size();i++) {
            JsonNode message=messages.get(i);
            try {validate.accept(message);}
            catch(SecurityException denied) {
                restricted=true;ObjectNode safe=data.objectNode();
                MESSAGE_METADATA.forEach(key->{if(message.has(key))safe.set(key,message.get(key));});
                String reason=java.util.Objects.toString(denied.getMessage(),"");
                String code=reason.equals("Source cannot be revalidated; retained content is withheld")?"SOURCE_UNAVAILABLE"
                    :reason.equals("Resource source changed; start a new task to acquire current evidence")?"SOURCE_CHANGED":"CONTENT_ACCESS_DENIED";
                safe.put("contentRestricted",true);safe.put("contentRestrictionReason",code);
                safe.put("content",switch(code) {
                    case "SOURCE_UNAVAILABLE" -> "来源服务暂时无法完成复核，此条历史回答暂不可显示，请稍后重试。";
                    case "SOURCE_CHANGED" -> "此条历史回答引用的来源已更新，当前无法确认旧版本内容，请重新检索生成报告。";
                    default -> "此条历史回答未通过当前来源权限或版本校验，内容已隐藏。请确认访问权限后重试。";
                });
                safe.put("status","completed");safe.put("streaming",false);
                safe.putArray("sources");safe.putArray("traces");safe.putArray("steps");
                messages.set(i,safe);
            }
        }
        boolean treeDenied=false;
        if(data.hasNonNull("analysisTree"))try{validate.accept(data.get("analysisTree"));}catch(SecurityException denied){treeDenied=true;}
        if(restricted||treeDenied) {
            data.retain(HEADERS);data.set("messages",messages);data.putObject("analysisTree");data.put("contentRestricted",true);
        }
        return result;
    }
}

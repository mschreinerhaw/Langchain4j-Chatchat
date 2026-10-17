package com.chatchat.api.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Transient transport binding to the parent's already metered model. No execution/usage authority. */
@Component
@ConditionalOnProperty(prefix="chatchat.coding", name="enabled", havingValue="true")
public final class CodingParentModels {
    public static final String REQUIRED_REF="runtime-coding:parent-metering.v1";
    private final ConcurrentHashMap<String, ChatModel> models = new ConcurrentHashMap<>();
    private static String key(KernelDataScope scope, String task) {
        return scope.tenantId()+"\0"+scope.userId()+"\0"+scope.runId()+"\0"+task;
    }
    public AutoCloseable bind(KernelDataScope scope, String task, ChatModel model) {
        String key=key(scope,task);
        if (model==null || models.size()>=128 || models.putIfAbsent(key,model)!=null)
            throw new IllegalStateException("Coding parent model binding is occupied or at capacity");
        return () -> models.remove(key,model);
    }
    public ChatModel find(KernelDataScope scope, String task) { return models.get(key(scope,task)); }
}

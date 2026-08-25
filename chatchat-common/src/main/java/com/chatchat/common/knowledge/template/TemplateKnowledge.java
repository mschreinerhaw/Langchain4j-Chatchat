package com.chatchat.common.knowledge.template;

import com.chatchat.common.knowledge.KnowledgeDocument;

import java.util.List;
import java.util.Map;

/** Canonical knowledge document describing one governed executable template. */
public interface TemplateKnowledge extends KnowledgeDocument {
    default String schemaVersion() { return StandardTemplateKnowledge.SCHEMA_VERSION; }
    String templateId();
    String templateType();
    String executorTool();
    Map<String, Object> parameterSchema();
    Map<String, Object> outputSchema();
    List<String> requiredParameters();

    @Override default String documentId() { return templateId(); }
    @Override default String documentType() { return "mcp_template"; }
}

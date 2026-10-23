package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingRepository;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Snapshot legacy publications without enabling bindings, adding grants or changing template scope. */
@Component @RequiredArgsConstructor
public class ToolReleaseLegacyMigration {
    private final ToolReleaseRepository releases;
    private final TemplateQueryBindingRepository bindings;
    private final TemplateQueryMcpToolPublisher publisher;
    private final ObjectMapper json;
    @EventListener(ApplicationReadyEvent.class) @Order(200) @Transactional
    public void migrate() throws Exception {
        for (var binding:bindings.findAllByOrderByUpdatedAtDesc()) {
            String id=UUID.nameUUIDFromBytes(("legacy-tool-release:"+binding.getId()).getBytes(StandardCharsets.UTF_8)).toString();
            if(releases.existsById(id) || releases.existsByBindingId(binding.getId()))continue;
            var row=new ToolRelease();row.setId(id);row.setLogicalId(id);row.setTenantId(binding.getTenantId());
            row.setActorId("legacy-migration");row.setReleaseNumber(1);row.setKind("CONTROLLED_TEMPLATE");
            row.setTitle(Objects.toString(binding.getChineseAlias(),binding.getDomainCode()));
            row.setState(binding.isEnabled()?"RUNNING":"OFFLINE");row.setBindingId(binding.getId());
            row.setCreatedAt(binding.getCreatedAt());row.setPublishedAt(binding.getUpdatedAt());
            List<String> keys=json.readValue(binding.getTemplateKeysJson(),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
            row.setDefinitionJson(json.writeValueAsString(new TemplateQueryBindingService.UpsertRequest(
                binding.getParentToolName(),binding.getRoleId(),binding.getSubjectType(),
                "USER".equals(binding.getSubjectType())?binding.getSubjectId():null,binding.getDomainCode(),keys,false,null,binding.getChineseAlias())));
            row.setInputSchemaJson(json.writeValueAsString(publisher.defaultInputSchema()));
            row.setOutputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
            row.setTestEvidenceJson("{\"type\":\"LEGACY_PRESERVED_NOT_RETESTED\"}");
            releases.save(row);
        }
        releases.flush();
    }
}

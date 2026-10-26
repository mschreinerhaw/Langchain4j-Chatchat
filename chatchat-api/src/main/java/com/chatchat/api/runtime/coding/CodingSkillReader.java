package com.chatchat.api.runtime.coding;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.resource.SkillResourceRequest;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Progressive disclosure through the existing Skill catalog; no model or execution authority. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class CodingSkillReader {
    private final SkillResolver resolver;
    private final SkillExecutionScopePort scopes;
    private final DurableCodingTaskService tasks;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public CodingSkillReader(SkillResolver resolver, SkillExecutionScopePort scopes,
            DurableCodingTaskService tasks, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.resolver=resolver; this.scopes=scopes; this.tasks=tasks; this.json=json;
    }
    public Map<String,Object> read(CodingTaskContract contract, CodingJournal.Lease lease, String id, String path) {
        if (!CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
                || !contract.authorization().allowedTools().contains("skill_read")) throw denied();
        Object raw=contract.runtimeContext().get("skills");
        if (!(raw instanceof Map<?,?> catalog) || !(catalog.get("skills") instanceof List<?> cards)) throw denied();
        var matches=cards.stream().filter(Map.class::isInstance).map(Map.class::cast)
            .filter(card -> id.equals(card.get("id"))).toList();
        if (matches.size()!=1) throw denied();
        Map<?,?> card=matches.get(0);
        if (!(card.get("agentId") instanceof String agentId) || agentId.isBlank()
                || !(card.get("version") instanceof String version) || version.isBlank()) throw denied();
        var current=scopes.resolve(contract.scope().tenantId(),contract.scope().userId(),agentId,List.of(),List.of());
        if (!current.skillAllowed()) throw denied();
        var identity=new SkillRoleContext(contract.scope().tenantId(),contract.scope().userId(),current.roles(),
            List.of(),Map.of("agentId",agentId));
        var resolution=resolver.resolve(new SkillResolutionRequest(id,version,identity));
        if (!resolution.resolved() || !id.equals(resolution.skill().descriptor().id())
                || !version.equals(resolution.skill().descriptor().version())) throw denied();
        var skill=resolution.skill();
        String hash=ModelProtocolJson.sha256Hex(skill.instructions());
        if (card.get("contentSha256") instanceof String expected && !expected.equals(hash)) throw denied();
        // First read freezes the content manifest. Resume/repeated reads cannot silently change it.
        String key="skill-content:"+ModelProtocolJson.sha256Hex(id);
        var manifest=json.valueToTree(Map.of("instructionsSha256",hash,"resources",skill.resources()));
        var frozen=tasks.privateState(contract.scope(),contract.taskId(),key);
        if (frozen!=null && !frozen.equals(manifest)) throw denied();
        if (frozen==null) tasks.privateState(contract.scope(),contract.taskId(),lease,key,null,manifest);
        if (path==null || path.isBlank()) {
            if (skill.instructions().length()>72000 || skill.resources().size()>64)
                throw new IllegalArgumentException("Skill content exceeds disclosure budget");
            return Map.of("skillId",id,"version",version,"contentSha256",hash,"instructions",skill.instructions(),
                "resources",skill.resources().stream().filter(r -> !r.relativePath().startsWith("scripts/"))
                    .map(r -> Map.of("path",r.relativePath(),"sha256",r.digest())).toList(),
                "authority","METHODOLOGY_ONLY");
        }
        if (path.length()>512 || path.startsWith("/") || path.contains("\\") || path.contains("..")
                || path.startsWith("scripts/")) throw denied();
        var declared=skill.resources().stream().filter(r -> path.equals(r.relativePath())).findFirst().orElseThrow(CodingSkillReader::denied);
        var resource=resolver.readResource(new SkillResourceRequest(id,declared.resourceId(),identity)).orElseThrow(CodingSkillReader::denied);
        byte[] bytes=resource.content();
        try {
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length>24000 || !declared.digest().equals(digest)
                    || !declared.digest().equals(resource.descriptor().digest())) throw denied();
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return Map.of("skillId",id,"version",version,"path",path,"sha256",digest,"content",text,"authority","METHODOLOGY_ONLY");
        } catch (java.nio.charset.CharacterCodingException | java.security.NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("Skill resource cannot be disclosed",invalid);
        }
    }
    private static SecurityException denied() { return new SecurityException("Skill is outside the task catalog, revoked or changed"); }
}

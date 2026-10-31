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
    /** Pin this Agent's assigned packages before its first model call, without
     * disclosing their bodies to Cline or changing the dynamic artifact source. */
    public void freezeCatalog(CodingTaskContract contract, CodingJournal.Lease lease) {
        if (!CodingProtocol.ANALYSIS_VERSION.equals(contract.schemaVersion())
                || !contract.authorization().allowedTools().contains("skill_read")) return;
        if (contract.runtimeContext().get("skills") instanceof Map<?,?> catalog
                && catalog.get("skills") instanceof List<?> cards)
            for (Object item : cards) if (item instanceof Map<?,?> card && card.get("id") instanceof String id) {
                // Mirror the image's reserved-name rule. A rejected collision must
                // not load an unused package or block the image default at admission.
                if (com.chatchat.chat.skills.runtime.BuiltinCodingSkills.catalog().stream().anyMatch(builtin ->
                        id.equalsIgnoreCase(String.valueOf(builtin.get("id")))
                        || Objects.toString(card.get("name"),"").trim().equalsIgnoreCase(String.valueOf(builtin.get("name"))))) continue;
                String key="skill-snapshot:"+ModelProtocolJson.sha256Hex(id);
                if (tasks.privateState(contract.scope(),contract.taskId(),key)==null) read(contract,lease,id,"");
            }
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
        String snapshotKey="skill-snapshot:"+ModelProtocolJson.sha256Hex(id);
        var snapshot=tasks.privateState(contract.scope(),contract.taskId(),snapshotKey);
        // Recheck live authorization, while retaining the content already acquired by
        // this task. Publishing a new version affects subsequent tasks only.
        var resolution=resolver.resolve(new SkillResolutionRequest(id,snapshot==null?version:"",identity));
        if (!resolution.resolved() || !id.equals(resolution.skill().descriptor().id())
                || snapshot==null && !version.equals(resolution.skill().descriptor().version())) throw denied();
        if (snapshot!=null) return frozenRead(snapshot,id,version,path);
        var skill=resolution.skill();
        String hash=ModelProtocolJson.sha256Hex(skill.instructions());
        if (card.get("contentSha256") instanceof String expected && !expected.equals(hash)) throw denied();
        // First read freezes the content manifest. Resume/repeated reads cannot silently change it.
        String key="skill-content:"+ModelProtocolJson.sha256Hex(id);
        var manifest=json.valueToTree(Map.of("instructionsSha256",hash,"resources",skill.resources()));
        var frozen=tasks.privateState(contract.scope(),contract.taskId(),key);
        if (frozen!=null && !frozen.equals(manifest)) throw denied();
        if (frozen==null) tasks.privateState(contract.scope(),contract.taskId(),lease,key,null,manifest);
        if (skill.instructions().length()>72000 || skill.resources().size()>64)
            throw new IllegalArgumentException("Skill content exceeds disclosure budget");
        var resources=new ArrayList<Map<String,Object>>();
        for (var declared : skill.resources()) {
            String relative=declared.relativePath();
            if (relative.startsWith("scripts/")) continue;
            if (!(declared.mediaType().startsWith("text/") || relative.matches(".*\\.(md|txt|json|ya?ml|csv)$"))) continue;
            if (relative.length()>512 || relative.startsWith("/") || relative.contains("\\") || relative.contains("..")) throw denied();
            var resource=resolver.readResource(new SkillResourceRequest(id,declared.resourceId(),identity)).orElseThrow(CodingSkillReader::denied);
            byte[] bytes=resource.content();
            try {
                String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                if (bytes.length>24000 || !declared.digest().equals(digest)
                        || !declared.digest().equals(resource.descriptor().digest())) throw denied();
                String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                resources.add(Map.of("path",relative,"sha256",digest,"content",text));
            } catch (java.nio.charset.CharacterCodingException | java.security.NoSuchAlgorithmException invalid) {
                throw new IllegalArgumentException("Skill resource cannot be disclosed",invalid);
            }
        }
        snapshot=json.valueToTree(Map.of("instructions",skill.instructions(),"contentSha256",hash,"resources",resources));
        if (snapshot.toString().length()>250000) throw new IllegalArgumentException("Skill snapshot exceeds disclosure budget");
        tasks.privateState(contract.scope(),contract.taskId(),lease,snapshotKey,null,snapshot);
        return frozenRead(snapshot,id,version,path);
    }
    private Map<String,Object> frozenRead(com.fasterxml.jackson.databind.JsonNode snapshot,String id,String version,String path) {
        if (path==null || path.isBlank()) {
            var resources=new ArrayList<Map<String,Object>>();
            snapshot.path("resources").forEach(r -> resources.add(Map.of("path",r.path("path").asText(),"sha256",r.path("sha256").asText())));
            return Map.of("skillId",id,"version",version,"contentSha256",snapshot.path("contentSha256").asText(),
                "instructions",snapshot.path("instructions").asText(),"resources",resources,
                "authority","METHODOLOGY_ONLY");
        }
        if (path.length()>512 || path.startsWith("/") || path.contains("\\") || path.contains("..")
                || path.startsWith("scripts/")) throw denied();
        for (var resource : snapshot.path("resources")) if (path.equals(resource.path("path").asText()))
            return Map.of("skillId",id,"version",version,"path",path,"sha256",resource.path("sha256").asText(),
                "content",resource.path("content").asText(),"authority","METHODOLOGY_ONLY");
        throw denied();
    }
    private static SecurityException denied() { return new SecurityException("Skill is outside the task catalog, revoked or changed"); }
}

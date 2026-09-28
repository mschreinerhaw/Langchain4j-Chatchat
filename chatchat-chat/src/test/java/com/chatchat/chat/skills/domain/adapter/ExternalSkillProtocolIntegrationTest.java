package com.chatchat.chat.skills.domain.adapter;
import com.chatchat.chat.skills.domain.artifact.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalSkillProtocolIntegrationTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ExternalSkillCompiler compiler=source -> new RuntimeSkillIr(RuntimeSkillIr.SCHEMA_VERSION,
        source.name(),source.description(),source.instructions(),List.of("model-advice"),List.of(),"TEST");
    private ExternalSkillAdapterGateway gateway() {
        return new ExternalSkillAdapterGateway(List.of(new StructuredExternalSkillAdapter(mapper),
            new SkillMdExternalSkillAdapter(new SkillFormatDetector())),compiler,mapper);
    }
    private final String markdown="""
        ---
        name: revenue
        runtime:
          schemaVersion: skill_protocol.v1
          capabilities: [sales.summary]
          requiresCapabilities: [sales.prepare]
          workflows: [fixed-data]
          requiredData:
            - id: sales
              contractId: sales.rows.v1
              parameters: {customerId: customer}
              requiredFor: [total]
          analysisSteps:
            - id: total
              operator: SUM
              datasetId: sales
              field: amount
        allowed-tools: [shell]
        ---
        Summarize sales using the supplied data.
        """;
    @Test void actualImportPreservesExecutableDeclarationsThroughStoredPublishedIr() throws Exception {
        var result=gateway().adaptAndCompile(new ExternalSkillSource("SKILL.md","MARKDOWN","test",markdown,new byte[0]));
        var ir=mapper.readValue(result.skillIrJson(),RuntimeSkillIr.class);
        assertThat(ir.execution().requirements().data()).singleElement().satisfies(data -> assertThat(data.contractId()).isEqualTo("sales.rows.v1"));
        assertThat(ir.execution().requirements().steps()).singleElement().satisfies(step -> assertThat(step.operator()).isEqualTo("SUM"));
        assertThat(ir.execution().requirements().mcpToolIds()).isEmpty();
        assertThat(ir.execution().requiredCapabilities()).containsExactly("sales.prepare");
        var sources=mock(DomainSkillSourceArtifactRepository.class);
        var compilations=mock(DomainSkillCompilationRepository.class);
        var source=new DomainSkillSourceArtifactEntity();source.setId("source");source.setTenantId("tenant");
        source.setSkillId("skill");source.setSourceType("MARKDOWN");source.setOriginalArtifact(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var compilation=new DomainSkillCompilationEntity();compilation.setId("published");compilation.setTenantId("tenant");
        compilation.setSkillId("skill");compilation.setSourceId("source");compilation.setSkillIrJson(result.skillIrJson());
        when(compilations.findById("published")).thenReturn(Optional.of(compilation));
        when(sources.findById("source")).thenReturn(Optional.of(source));
        var reader=new DomainSkillPackageReader(sources,mapper,compilations);
        assertThat(reader.readPublished("tenant","skill","published").requirements()).isEqualTo(ir.execution().requirements());
        assertThatThrownBy(() -> reader.readPublished("another-tenant","skill","published")).isInstanceOf(IllegalArgumentException.class);
        verify(sources,never()).findFirstBySkillIdAndTenantIdOrderByCreatedAtDesc(anyString(),anyString());
    }
    @Test void jsonAndYamlExportsUseTheSameProtocolAndRejectAmbiguousDeclarations() {
        var yaml=new ExternalSkillSource("agent.yaml","YAML","test","""
            name: sales
            instructions: Summarize data.
            runtime:
              schemaVersion: skill_protocol.v1
              capabilities: [sales.summary]
              workflows: [fixed-data]
            tools: [shell]
            """,new byte[0]);
        var compiled=gateway().adaptAndCompile(yaml).skillIr();
        assertThat(compiled.capabilities()).containsExactly("sales.summary");
        assertThat(compiled.execution().requirements().mcpToolIds()).isEmpty();
        assertThat(compiled.execution().requiredCapabilities()).isEmpty();
        var json=gateway().adaptAndCompile(new ExternalSkillSource("agent.json","JSON","test",
            "{\"name\":\"sales\",\"systemPrompt\":\"Summarize.\",\"runtime\":{\"schemaVersion\":\"skill_protocol.v1\",\"capabilities\":[\"sales.summary\"]}}",
            new byte[0]));
        assertThat(json.skillIr().capabilities()).containsExactly("sales.summary");
        assertThatThrownBy(() -> gateway().adaptAndCompile(new ExternalSkillSource("agent.json","JSON","test",
            "{\"name\":\"a\",\"name\":\"b\",\"instructions\":\"x\"}",new byte[0]))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidStepGraphFailsCompilationInsteadOfFallingBackToProse() {
        assertThatThrownBy(() -> gateway().adaptAndCompile(new ExternalSkillSource("SKILL.md","MARKDOWN","test",
            markdown.replace("datasetId: sales","datasetId: missing"),new byte[0])))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway().adaptAndCompile(new ExternalSkillSource("SKILL.md","MARKDOWN","test",
            markdown.replace("field: amount","field: amount\n      dependsOn: [total]"),new byte[0])))
            .isInstanceOf(IllegalArgumentException.class);
    }
}


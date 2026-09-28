package com.chatchat.chat.skills.runtime;

import com.chatchat.runtime.skill.application.DefaultSkillResolver;
import com.chatchat.runtime.skill.application.DefaultSkillRouter;
import com.chatchat.runtime.skill.application.DefaultWorkflowResolver;
import com.chatchat.runtime.skill.application.DefaultAgentRuntimeDispatcher;
import com.chatchat.runtime.skill.application.ExternalEngineAgentRuntimeAdapter;
import com.chatchat.runtime.skill.application.DefaultSkillRuntime;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.port.inbound.AgentRuntimeDispatcher;
import com.chatchat.runtime.skill.port.outbound.ExternalAgentEngine;
import com.chatchat.runtime.skill.port.outbound.SkillPolicy;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.chatchat.runtime.skill.port.inbound.SkillRouter;
import com.chatchat.runtime.skill.port.outbound.SkillSource;
import com.chatchat.runtime.skill.port.inbound.SkillRuntime;
import com.chatchat.runtime.skill.port.inbound.WorkflowResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SkillRuntimeConfiguration {
    @Bean
    com.chatchat.runtime.skill.application.SkillCompositionRuntime skillCompositionRuntime(SkillRouter router,
            SkillResolver resolver, WorkflowResolver workflows, SkillRuntime runtime) {
        return new com.chatchat.runtime.skill.application.SkillCompositionRuntime(router, resolver, workflows, runtime);
    }
    @Bean
    SkillRouter runtimeSkillRouter(List<SkillSource> sources, SkillPolicy policy) {
        return new DefaultSkillRouter(sources, policy);
    }

    @Bean
    SkillResolver runtimeSkillResolver(List<SkillSource> sources, SkillPolicy policy) {
        return new DefaultSkillResolver(sources, policy);
    }

    @Bean
    WorkflowResolver runtimeSkillWorkflowResolver() { return new DefaultWorkflowResolver(); }

    @Bean
    AgentRuntimeAdapter externalEngineSkillRuntimeAdapter(List<ExternalAgentEngine> engines) {
        return new ExternalEngineAgentRuntimeAdapter(engines);
    }

    @Bean
    AgentRuntimeDispatcher runtimeSkillAgentDispatcher(List<AgentRuntimeAdapter> adapters) {
        return new DefaultAgentRuntimeDispatcher(adapters);
    }

    @Bean
    SkillRuntime runtimeSkillRuntime(SkillRouter router, SkillResolver resolver,
                                     WorkflowResolver workflows, AgentRuntimeDispatcher agents,
                                     org.springframework.beans.factory.ObjectProvider<com.chatchat.runtime.skill.port.outbound.SkillDataWorkflow> dataWorkflows,
                                     org.springframework.beans.factory.ObjectProvider<com.chatchat.runtime.skill.port.outbound.SkillAnalysisOperator> operators) {
        return new DefaultSkillRuntime(router, resolver, workflows, agents,
            new com.chatchat.runtime.skill.application.SkillDataAcquisition(() -> dataWorkflows.orderedStream().toList()),
            new com.chatchat.runtime.skill.application.SkillAnalysisExecutor(() -> operators.orderedStream().toList()));
    }
}

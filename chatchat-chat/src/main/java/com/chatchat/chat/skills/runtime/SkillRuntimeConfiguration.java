package com.chatchat.chat.skills.runtime;

import com.chatchat.runtime.skill.core.DefaultSkillResolver;
import com.chatchat.runtime.skill.core.DefaultSkillRouter;
import com.chatchat.runtime.skill.core.DefaultWorkflowResolver;
import com.chatchat.runtime.skill.core.DefaultAgentRuntimeDispatcher;
import com.chatchat.runtime.skill.core.ExternalEngineAgentRuntimeAdapter;
import com.chatchat.runtime.skill.core.DefaultSkillRuntime;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.AgentRuntimeDispatcher;
import com.chatchat.runtime.skill.spi.ExternalAgentEngine;
import com.chatchat.runtime.skill.spi.SkillPolicy;
import com.chatchat.runtime.skill.spi.SkillResolver;
import com.chatchat.runtime.skill.spi.SkillRouter;
import com.chatchat.runtime.skill.spi.SkillSource;
import com.chatchat.runtime.skill.spi.SkillRuntime;
import com.chatchat.runtime.skill.spi.WorkflowResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SkillRuntimeConfiguration {
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
                                     WorkflowResolver workflows, AgentRuntimeDispatcher agents) {
        return new DefaultSkillRuntime(router, resolver, workflows, agents);
    }
}

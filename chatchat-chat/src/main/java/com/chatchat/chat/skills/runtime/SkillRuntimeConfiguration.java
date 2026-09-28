package com.chatchat.chat.skills.runtime;

import com.chatchat.runtime.skill.core.DefaultSkillResolver;
import com.chatchat.runtime.skill.core.DefaultSkillRouter;
import com.chatchat.runtime.skill.spi.SkillPolicy;
import com.chatchat.runtime.skill.spi.SkillResolver;
import com.chatchat.runtime.skill.spi.SkillRouter;
import com.chatchat.runtime.skill.spi.SkillSource;
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
}

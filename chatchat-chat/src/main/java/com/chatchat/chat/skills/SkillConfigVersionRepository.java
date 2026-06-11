package com.chatchat.chat.skills;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillConfigVersionRepository extends JpaRepository<SkillConfigVersionEntity, String> {

    /**
     * Finds the top30 by skill id order by created at desc.
     *
     * @param skillId the skill id value
     * @return the matching top30 by skill id order by created at desc
     */
    List<SkillConfigVersionEntity> findTop30BySkillIdOrderByCreatedAtDesc(String skillId);
}

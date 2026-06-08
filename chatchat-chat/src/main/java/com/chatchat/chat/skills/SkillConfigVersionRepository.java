package com.chatchat.chat.skills;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillConfigVersionRepository extends JpaRepository<SkillConfigVersionEntity, String> {

    List<SkillConfigVersionEntity> findTop30BySkillIdOrderByCreatedAtDesc(String skillId);
}
